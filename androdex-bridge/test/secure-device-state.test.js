const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");
const childProcess = require("child_process");

const modulePath = path.resolve(__dirname, "../src/pairing/device-state.js");
const PHONE_ID_RELOADED = "11111111-1111-4111-8111-111111111111";
const PHONE_ID_RESET = "22222222-2222-4222-8222-222222222222";
const PHONE_ID_CORRUPT = "33333333-3333-4333-8333-333333333333";
const PHONE_ID_TRIMMED = "44444444-4444-4444-8444-444444444444";
const PHONE_ID_OLD = "55555555-5555-4555-8555-555555555555";
const PHONE_ID_NEW = "66666666-6666-4666-8666-666666666666";
const STALE_MAC_ID = "77777777-7777-4777-8777-777777777777";

function withTempHome(optionsOrRun, maybeRun) {
  const options = typeof optionsOrRun === "function" ? {} : (optionsOrRun || {});
  const run = typeof optionsOrRun === "function" ? optionsOrRun : maybeRun;
  const tempHome = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-device-state-"));
  const previousHome = process.env.HOME;
  const previousUserProfile = process.env.USERPROFILE;
  const previousDeviceStateDir = process.env.ANDRODEX_DEVICE_STATE_DIR;
  const previousDeviceStateFile = process.env.ANDRODEX_DEVICE_STATE_FILE;
  const previousAllowSynthetic = process.env.ANDRODEX_ALLOW_SYNTHETIC_DEVICE_STATE;
  const previousExecFileSync = childProcess.execFileSync;
  const previousPlatformDescriptor = Object.getOwnPropertyDescriptor(process, "platform");
  const effectivePlatform = options.platform || process.platform;
  process.env.HOME = tempHome;
  process.env.USERPROFILE = tempHome;
  delete process.env.ANDRODEX_DEVICE_STATE_DIR;
  delete process.env.ANDRODEX_DEVICE_STATE_FILE;
  delete process.env.ANDRODEX_ALLOW_SYNTHETIC_DEVICE_STATE;
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
    if (previousDeviceStateDir == null) {
      delete process.env.ANDRODEX_DEVICE_STATE_DIR;
    } else {
      process.env.ANDRODEX_DEVICE_STATE_DIR = previousDeviceStateDir;
    }
    if (previousDeviceStateFile == null) {
      delete process.env.ANDRODEX_DEVICE_STATE_FILE;
    } else {
      process.env.ANDRODEX_DEVICE_STATE_FILE = previousDeviceStateFile;
    }
    if (previousAllowSynthetic == null) {
      delete process.env.ANDRODEX_ALLOW_SYNTHETIC_DEVICE_STATE;
    } else {
      process.env.ANDRODEX_ALLOW_SYNTHETIC_DEVICE_STATE = previousAllowSynthetic;
    }
    fs.rmSync(tempHome, { recursive: true, force: true });
  }
}

function getStatePaths(tempHome) {
  const storeDir = path.join(tempHome, ".androdex");
  return {
    storeDir,
    primaryFile: path.join(storeDir, "device-state.json"),
    backupFile: path.join(storeDir, "device-state.backup.json"),
  };
}

test("trusted phone state is reloaded from persisted bridge device state", () => {
  withTempHome(({ tempHome, secureDeviceState }) => {
    const initialState = secureDeviceState.loadOrCreateBridgeDeviceState();
    const updatedState = secureDeviceState.rememberTrustedPhone(
      initialState,
      PHONE_ID_RELOADED,
      "public-key-1"
    );

    delete require.cache[modulePath];
    const reloadedModule = require(modulePath);
    const reloadedState = reloadedModule.loadOrCreateBridgeDeviceState();

    assert.equal(reloadedState.macDeviceId, updatedState.macDeviceId);
    assert.equal(
      reloadedModule.getTrustedPhonePublicKey(reloadedState, PHONE_ID_RELOADED),
      "public-key-1"
    );
    const { primaryFile } = getStatePaths(tempHome);
    assert.equal(fs.existsSync(primaryFile), true);
  });
});

test("resetBridgeDeviceState removes persisted trust and regenerates a fresh identity", () => {
  withTempHome(({ tempHome, secureDeviceState }) => {
    const initialState = secureDeviceState.loadOrCreateBridgeDeviceState();
    secureDeviceState.rememberTrustedPhone(initialState, PHONE_ID_RESET, "public-key-2");

    const resetResult = secureDeviceState.resetBridgeDeviceState();
    assert.equal(resetResult.hadState, true);

    delete require.cache[modulePath];
    const reloadedModule = require(modulePath);
    const reloadedState = reloadedModule.loadOrCreateBridgeDeviceState();

    assert.notEqual(reloadedState.macDeviceId, initialState.macDeviceId);
    assert.equal(
      reloadedModule.getTrustedPhonePublicKey(reloadedState, PHONE_ID_RESET),
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
      PHONE_ID_CORRUPT,
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
      reloadedModule.getTrustedPhonePublicKey(recoveredState, PHONE_ID_CORRUPT),
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

test("synthetic canonical state is recovered from the last known-good backup", () => {
  withTempHome(({ tempHome, secureDeviceState }) => {
    const initialState = secureDeviceState.loadOrCreateBridgeDeviceState();
    const { primaryFile, backupFile } = getStatePaths(tempHome);

    fs.writeFileSync(backupFile, JSON.stringify(initialState, null, 2), "utf8");
    fs.writeFileSync(primaryFile, JSON.stringify({
      version: 1,
      macDeviceId: "mac-5",
      macIdentityPublicKey: "pub",
      macIdentityPrivateKey: "priv",
      trustedPhones: {
        [PHONE_ID_NEW]: "phone-pub",
      },
    }, null, 2), "utf8");

    delete require.cache[modulePath];
    const reloadedModule = require(modulePath);
    const recoveredState = reloadedModule.loadOrCreateBridgeDeviceState();

    assert.equal(recoveredState.macDeviceId, initialState.macDeviceId);
  });
});

test("synthetic canonical state without backup is rejected outside explicit synthetic-test mode", () => {
  withTempHome(({ tempHome }) => {
    const { primaryFile } = getStatePaths(tempHome);
    fs.mkdirSync(path.dirname(primaryFile), { recursive: true });
    fs.writeFileSync(primaryFile, JSON.stringify({
      version: 1,
      macDeviceId: "mac-5",
      macIdentityPublicKey: "pub",
      macIdentityPrivateKey: "priv",
      trustedPhones: {
        [PHONE_ID_NEW]: "phone-pub",
      },
    }, null, 2), "utf8");

    delete require.cache[modulePath];
    const reloadedModule = require(modulePath);

    assert.throws(
      () => reloadedModule.loadOrCreateBridgeDeviceState(),
      /Saved bridge identity state is corrupted in device-state\.json/i
    );
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
      macDeviceId: STALE_MAC_ID,
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
      `  ${PHONE_ID_TRIMMED}  `,
      "  public-key-trimmed  "
    );

    assert.equal(
      secureDeviceState.getTrustedPhonePublicKey(updatedState, PHONE_ID_TRIMMED),
      "public-key-trimmed"
    );
    assert.deepEqual(Object.keys(updatedState.trustedPhones), [PHONE_ID_TRIMMED]);
  });
});

test("rememberTrustedPhone preserves the recovery credential that authorized a rotation", () => {
  withTempHome(({ secureDeviceState }) => {
    const initialState = secureDeviceState.loadOrCreateBridgeDeviceState();
    const firstRecoveryIdentity = {
      recoveryIdentityPublicKey: "recovery-public-a",
      recoveryIdentityPrivateKey: "recovery-private-a",
    };
    const supersededRecoveryIdentity = {
      recoveryIdentityPublicKey: "recovery-public-b",
      recoveryIdentityPrivateKey: "recovery-private-b",
    };
    const nextRecoveryIdentity = {
      recoveryIdentityPublicKey: "recovery-public-c",
      recoveryIdentityPrivateKey: "recovery-private-c",
    };

    const trustedState = secureDeviceState.rememberTrustedPhone(
      initialState,
      PHONE_ID_OLD,
      "phone-public-old",
      {
        recoveryIdentity: supersededRecoveryIdentity,
        previousRecoveryIdentity: firstRecoveryIdentity,
      }
    );
    const rotatedState = secureDeviceState.rememberTrustedPhone(
      trustedState,
      PHONE_ID_NEW,
      "phone-public-new",
      {
        recoveryIdentity: nextRecoveryIdentity,
        previousRecoveryIdentity: firstRecoveryIdentity,
      }
    );

    assert.deepEqual(rotatedState.trustedPhones, {
      [PHONE_ID_NEW]: "phone-public-new",
    });
    assert.deepEqual(
      secureDeviceState.getTrustedPhoneRecoveryIdentities(rotatedState, PHONE_ID_NEW),
      [nextRecoveryIdentity, firstRecoveryIdentity]
    );
  });
});
