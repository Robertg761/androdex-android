const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");
const childProcess = require("child_process");

const modulePath = path.resolve(__dirname, "../src/secure-device-state.js");

function withTempHome(run) {
  const tempHome = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-device-state-"));
  const previousHome = process.env.HOME;
  const previousUserProfile = process.env.USERPROFILE;
  process.env.HOME = tempHome;
  process.env.USERPROFILE = tempHome;
  delete require.cache[modulePath];

  try {
    return run({
      tempHome,
      secureDeviceState: require(modulePath),
    });
  } finally {
    delete require.cache[modulePath];
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
    backupFile: path.join(storeDir, "device-state.backup.json"),
  };
}

function withMockPlatform(platform, run) {
  const descriptor = Object.getOwnPropertyDescriptor(process, "platform");
  Object.defineProperty(process, "platform", {
    configurable: true,
    value: platform,
  });
  try {
    return run();
  } finally {
    Object.defineProperty(process, "platform", descriptor);
  }
}

function withMockKeychain(handler, run) {
  const previousExecFileSync = childProcess.execFileSync;
  childProcess.execFileSync = (...args) => handler(previousExecFileSync, ...args);
  try {
    return run();
  } finally {
    childProcess.execFileSync = previousExecFileSync;
  }
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

    assert.equal(reloadedState.hostId, updatedState.hostId);
    assert.equal(reloadedState.macDeviceId, updatedState.macDeviceId);
    assert.equal(
      reloadedModule.getTrustedPhonePublicKey(reloadedState, "phone-1"),
      "public-key-1"
    );
    const { primaryFile, backupFile } = getStatePaths(tempHome);
    assert.equal(fs.existsSync(primaryFile), true);
    assert.equal(fs.existsSync(backupFile), true);
  });
});

test("resetBridgeDeviceState removes persisted trusted phone state", () => {
  withTempHome(({ tempHome, secureDeviceState }) => {
    const initialState = secureDeviceState.loadOrCreateBridgeDeviceState();
    secureDeviceState.rememberTrustedPhone(initialState, "phone-2", "public-key-2");

    const resetResult = secureDeviceState.resetBridgeDeviceState();
    assert.equal(resetResult.hadState, true);

    delete require.cache[modulePath];
    const reloadedModule = require(modulePath);
    const reloadedState = reloadedModule.loadOrCreateBridgeDeviceState();

    assert.equal(
      reloadedModule.getTrustedPhonePublicKey(reloadedState, "phone-2"),
      null
    );
    assert.deepEqual(reloadedState.trustedPhones, {});
    const { primaryFile, backupFile } = getStatePaths(tempHome);
    assert.equal(fs.existsSync(primaryFile), true);
    assert.equal(fs.existsSync(backupFile), true);
  });
});

test("reloads host identity and trusted phone state from the backup file when the primary file is missing", () => {
  withTempHome(({ tempHome, secureDeviceState }) => {
    const initialState = secureDeviceState.loadOrCreateBridgeDeviceState();
    const updatedState = secureDeviceState.rememberTrustedPhone(
      initialState,
      "phone-backup",
      "public-key-backup"
    );
    const { primaryFile, backupFile } = getStatePaths(tempHome);

    fs.rmSync(primaryFile, { force: true });
    assert.equal(fs.existsSync(backupFile), true);

    delete require.cache[modulePath];
    const reloadedModule = require(modulePath);
    const recoveredState = reloadedModule.loadOrCreateBridgeDeviceState();

    assert.equal(recoveredState.hostId, updatedState.hostId);
    assert.equal(recoveredState.macDeviceId, updatedState.macDeviceId);
    assert.equal(
      reloadedModule.getTrustedPhonePublicKey(recoveredState, "phone-backup"),
      "public-key-backup"
    );
  });
});

test("reloads host identity and trusted phone state from the backup file when the primary file is corrupted", () => {
  withTempHome(({ tempHome, secureDeviceState }) => {
    const initialState = secureDeviceState.loadOrCreateBridgeDeviceState();
    const updatedState = secureDeviceState.rememberTrustedPhone(
      initialState,
      "phone-corrupt",
      "public-key-corrupt"
    );
    const { primaryFile } = getStatePaths(tempHome);

    fs.writeFileSync(primaryFile, "{not-json", "utf8");

    delete require.cache[modulePath];
    const reloadedModule = require(modulePath);
    const recoveredState = reloadedModule.loadOrCreateBridgeDeviceState();

    assert.equal(recoveredState.hostId, updatedState.hostId);
    assert.equal(recoveredState.macDeviceId, updatedState.macDeviceId);
    assert.equal(
      reloadedModule.getTrustedPhonePublicKey(recoveredState, "phone-corrupt"),
      "public-key-corrupt"
    );
  });
});

test("darwin prefers fresher file state over stale keychain state", () => {
  withMockPlatform("darwin", () => {
    withTempHome(({ tempHome, secureDeviceState }) => {
      const initialState = secureDeviceState.loadOrCreateBridgeDeviceState();
      const updatedState = secureDeviceState.rememberTrustedPhone(
        initialState,
        "phone-file",
        "public-key-file"
      );
      const { primaryFile } = getStatePaths(tempHome);
      const staleState = {
        ...updatedState,
        trustedPhones: {
          "phone-stale": "public-key-stale",
        },
      };

      withMockKeychain((execFileSync, command, args, options) => {
        if (
          command === "security"
          && args[0] === "find-generic-password"
          && args.includes("-w")
        ) {
          return JSON.stringify(staleState);
        }
        if (
          command === "security"
          && (args[0] === "add-generic-password" || args[0] === "delete-generic-password")
        ) {
          return "";
        }
        return execFileSync(command, args, options);
      }, () => {
        fs.writeFileSync(primaryFile, JSON.stringify(updatedState, null, 2), "utf8");

        delete require.cache[modulePath];
        const reloadedModule = require(modulePath);
        const recoveredState = reloadedModule.loadOrCreateBridgeDeviceState();

        assert.equal(recoveredState.hostId, updatedState.hostId);
        assert.equal(
          reloadedModule.getTrustedPhonePublicKey(recoveredState, "phone-file"),
          "public-key-file"
        );
        assert.equal(
          reloadedModule.getTrustedPhonePublicKey(recoveredState, "phone-stale"),
          null
        );
      });
    });
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
    assert.equal(
      secureDeviceState.getTrustedPhonePublicKey(updatedState, "  phone-trimmed  "),
      "public-key-trimmed"
    );
    assert.deepEqual(Object.keys(updatedState.trustedPhones), ["phone-trimmed"]);
  });
});
