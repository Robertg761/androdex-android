const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");
const childProcess = require("child_process");

const modulePath = path.resolve(__dirname, "../src/secure-device-state.js");

function withTempHome(optionsOrRun, maybeRun) {
  const options = typeof optionsOrRun === "function" ? {} : (optionsOrRun || {});
  const run = typeof optionsOrRun === "function" ? optionsOrRun : maybeRun;
  const tempHome = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-device-state-"));
  const previousHome = process.env.HOME;
  const previousUserProfile = process.env.USERPROFILE;
  const previousExecFileSync = childProcess.execFileSync;
  const previousPlatformDescriptor = Object.getOwnPropertyDescriptor(process, "platform");
  const effectivePlatform = options.platform || process.platform;
  process.env.HOME = tempHome;
  process.env.USERPROFILE = tempHome;
  childProcess.execFileSync = (...args) => {
    if (typeof options.execFileSyncHandler === "function") {
      return options.execFileSyncHandler(previousExecFileSync, ...args);
    }

    const [command, commandArgs] = args;
    if (effectivePlatform === "darwin" && command === "security") {
      if (Array.isArray(commandArgs) && commandArgs[0] === "find-generic-password") {
        return "";
      }
      return "";
    }

    return previousExecFileSync(...args);
  };
  if (options.platform) {
    Object.defineProperty(process, "platform", {
      configurable: true,
      value: options.platform,
    });
  }
  delete require.cache[modulePath];

  try {
    return run({
      tempHome,
      secureDeviceState: require(modulePath),
    });
  } finally {
    delete require.cache[modulePath];
    childProcess.execFileSync = previousExecFileSync;
    if (options.platform) {
      Object.defineProperty(process, "platform", previousPlatformDescriptor);
    }
    if (previousHome == null) {
      delete process.env.HOME;
    } else {
      process.env.HOME = previousHome;
    }
    if (previousUserProfile == null) {
      delete process.env.USERPROFILE;
    } else {
      process.env.USERPROFILE = previousUserProfile;
    }
    fs.rmSync(tempHome, { recursive: true, force: true });
  }
}

function getStatePaths(tempHome) {
  const storeDir = path.join(tempHome, ".androdex");
  return {
    storeDir,
    primaryFile: path.join(storeDir, "device-state.json"),
  };
}

test("trusted phone state is reloaded from persisted bridge device state", () => {
  withTempHome(({ tempHome, secureDeviceState }) => {
    const initialState = secureDeviceState.loadOrCreateBridgeDeviceState();
    const updatedState = secureDeviceState.rememberTrustedPhone(
      initialState,
      "phone-1",
      "public-key-1"
    );

    delete require.cache[modulePath];
    const reloadedModule = require(modulePath);
    const reloadedState = reloadedModule.loadOrCreateBridgeDeviceState();

    assert.equal(reloadedState.macDeviceId, updatedState.macDeviceId);
    assert.equal(
      reloadedModule.getTrustedPhonePublicKey(reloadedState, "phone-1"),
      "public-key-1"
    );
    const { primaryFile } = getStatePaths(tempHome);
    assert.equal(fs.existsSync(primaryFile), true);
  });
});

test("resetBridgeDeviceState removes persisted trust and regenerates a fresh identity", () => {
  withTempHome(({ tempHome, secureDeviceState }) => {
    const initialState = secureDeviceState.loadOrCreateBridgeDeviceState();
    secureDeviceState.rememberTrustedPhone(initialState, "phone-2", "public-key-2");

    const resetResult = secureDeviceState.resetBridgeDeviceState();
    assert.equal(resetResult.hadState, true);

    delete require.cache[modulePath];
    const reloadedModule = require(modulePath);
    const reloadedState = reloadedModule.loadOrCreateBridgeDeviceState();

    assert.notEqual(reloadedState.macDeviceId, initialState.macDeviceId);
    assert.equal(
      reloadedModule.getTrustedPhonePublicKey(reloadedState, "phone-2"),
      null
    );
    const { primaryFile } = getStatePaths(tempHome);
    assert.equal(fs.existsSync(primaryFile), true);
  });
});

test("corrupted canonical state is recovered from the legacy keychain mirror on darwin", () => {
  let mirroredState = "";

  withTempHome({
    platform: "darwin",
    execFileSyncHandler(execFileSync, command, args, options) {
      if (command === "security" && Array.isArray(args) && args[0] === "find-generic-password") {
        return mirroredState;
      }
      if (command === "security" && Array.isArray(args) && args[0] === "add-generic-password") {
        mirroredState = args.at(-1);
        return "";
      }
      if (command === "security" && Array.isArray(args) && args[0] === "delete-generic-password") {
        mirroredState = "";
        return "";
      }
      return execFileSync(command, args, options);
    },
  }, ({ tempHome, secureDeviceState }) => {
    const initialState = secureDeviceState.loadOrCreateBridgeDeviceState();
    const updatedState = secureDeviceState.rememberTrustedPhone(
      initialState,
      "phone-corrupt",
      "public-key-corrupt"
    );
    const { primaryFile } = getStatePaths(tempHome);
    mirroredState = JSON.stringify(updatedState, null, 2);

    fs.writeFileSync(primaryFile, "{not-json", "utf8");

    delete require.cache[modulePath];
    const reloadedModule = require(modulePath);
    const recoveredState = reloadedModule.loadOrCreateBridgeDeviceState();

    assert.equal(recoveredState.macDeviceId, updatedState.macDeviceId);
    assert.equal(
      reloadedModule.getTrustedPhonePublicKey(recoveredState, "phone-corrupt"),
      "public-key-corrupt"
    );
  });
});

test("corrupted canonical state without a recoverable mirror throws instead of minting a new identity", () => {
  withTempHome(({ tempHome, secureDeviceState }) => {
    const initialState = secureDeviceState.loadOrCreateBridgeDeviceState();
    const { primaryFile } = getStatePaths(tempHome);

    fs.writeFileSync(primaryFile, "{not-json", "utf8");

    delete require.cache[modulePath];
    const reloadedModule = require(modulePath);

    assert.throws(
      () => reloadedModule.loadOrCreateBridgeDeviceState(),
      /Saved bridge identity state is corrupted in device-state\.json/i
    );
    assert.equal(fs.readFileSync(primaryFile, "utf8"), "{not-json");
    assert.equal(initialState.macDeviceId.length > 0, true);
  });
});

test("resolveBridgeRelaySession issues a fresh relay session id without changing the host identity", () => {
  withTempHome(({ secureDeviceState }) => {
    const initialState = secureDeviceState.loadOrCreateBridgeDeviceState();
    const firstSession = secureDeviceState.resolveBridgeRelaySession(initialState);
    const secondSession = secureDeviceState.resolveBridgeRelaySession(initialState);

    assert.equal(firstSession.deviceState.macDeviceId, initialState.macDeviceId);
    assert.equal(secondSession.deviceState.macDeviceId, initialState.macDeviceId);
    assert.notEqual(firstSession.sessionId, secondSession.sessionId);
    assert.equal(firstSession.isPersistent, false);
  });
});

test("stableRelayHostIdForMacDeviceId derives a durable public host route from the mac device id", () => {
  withTempHome(({ secureDeviceState }) => {
    assert.equal(
      secureDeviceState.stableRelayHostIdForMacDeviceId("123e4567-e89b-12d3-a456-426614174000"),
      "mac.123e4567-e89b-12d3-a456-426614174000"
    );
  });
});

test("loadOrCreateBridgeDeviceState repairs the legacy keychain mirror when the canonical file exists", () => {
  let keychainReadAttempts = 0;

  withTempHome({
    platform: "darwin",
    execFileSyncHandler(execFileSync, command, args, options) {
      if (command === "security" && Array.isArray(args) && args[0] === "find-generic-password") {
        keychainReadAttempts += 1;
        throw new Error("simulated keychain stall");
      }
      if (command === "security" && Array.isArray(args) && args[0] === "add-generic-password") {
        return "";
      }
      return execFileSync(command, args, options);
    },
  }, ({ secureDeviceState }) => {
    const initialState = secureDeviceState.loadOrCreateBridgeDeviceState();
    assert.equal(keychainReadAttempts, 1);

    keychainReadAttempts = 0;
    const reloadedState = secureDeviceState.loadOrCreateBridgeDeviceState();

    assert.equal(reloadedState.macDeviceId, initialState.macDeviceId);
    assert.equal(keychainReadAttempts, 1);
  });
});

test("canonical file state repairs a stale keychain mirror on darwin", () => {
  let mirroredState = "";
  let keychainWrites = 0;

  withTempHome({
    platform: "darwin",
    execFileSyncHandler(execFileSync, command, args, options) {
      if (command === "security" && Array.isArray(args) && args[0] === "find-generic-password") {
        return mirroredState;
      }
      if (command === "security" && Array.isArray(args) && args[0] === "add-generic-password") {
        keychainWrites += 1;
        mirroredState = args.at(-1);
        return "";
      }
      return execFileSync(command, args, options);
    },
  }, ({ secureDeviceState }) => {
    const canonicalState = secureDeviceState.loadOrCreateBridgeDeviceState();
    mirroredState = JSON.stringify({
      ...canonicalState,
      macDeviceId: "stale-device",
    }, null, 2);

    const reloadedState = secureDeviceState.loadOrCreateBridgeDeviceState();

    assert.equal(reloadedState.macDeviceId, canonicalState.macDeviceId);
    assert.equal(JSON.parse(mirroredState).macDeviceId, canonicalState.macDeviceId);
    assert.equal(keychainWrites >= 1, true);
  });
});

test("rememberTrustedPhone trims identifiers and replaces older trusted phone entries", () => {
  withTempHome(({ secureDeviceState }) => {
    const initialState = secureDeviceState.loadOrCreateBridgeDeviceState();
    const updatedState = secureDeviceState.rememberTrustedPhone(
      initialState,
      "  phone-trimmed  ",
      "  public-key-trimmed  "
    );

    assert.equal(
      secureDeviceState.getTrustedPhonePublicKey(updatedState, "phone-trimmed"),
      "public-key-trimmed"
    );
    assert.deepEqual(Object.keys(updatedState.trustedPhones), ["phone-trimmed"]);
  });
});
