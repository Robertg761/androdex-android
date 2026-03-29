// FILE: macos.js
// Purpose: macOS host adapter for bridge launch, desktop open, and secure state operations.
// Layer: CLI helper
// Exports: createMacOSHostPlatform
// Depends on: child_process, fs, os, path

const fs = require("fs");
const os = require("os");
const path = require("path");
const { execFile, execFileSync } = require("child_process");

const STORE_DIR = path.join(os.homedir(), ".androdex");
const STORE_FILE = path.join(STORE_DIR, "device-state.json");
const KEYCHAIN_SERVICE = "io.androdex.bridge.device-state";
const KEYCHAIN_ACCOUNT = "default";
const DEFAULT_BUNDLE_ID = "com.openai.codex";
const DEFAULT_APP_PATH = "/Applications/Codex.app";

function createMacOSHostPlatform({
  env = process.env,
  execFileFn = execFile,
  execFileSyncFn = execFileSync,
} = {}) {
  return {
    getPlatformId() {
      return "darwin";
    },
    getRefreshDefaults() {
      return { enabled: true };
    },
    createCodexLaunchPlan({ cwd = "" } = {}) {
      return {
        command: "codex",
        args: ["app-server"],
        options: {
          stdio: ["pipe", "pipe", "pipe"],
          env: { ...env },
          cwd: cwd || process.cwd(),
        },
        description: "`codex app-server`",
      };
    },
    shutdownCodexProcess(child) {
      if (!child || child.killed || child.exitCode !== null) {
        return;
      }
      child.kill("SIGTERM");
    },
    createDesktopLaunchPlan({
      targetUrl = "",
      bundleId = DEFAULT_BUNDLE_ID,
      appPath = DEFAULT_APP_PATH,
    } = {}) {
      const safeTargetUrl = typeof targetUrl === "string" ? targetUrl.trim() : "";
      if (safeTargetUrl) {
        return {
          command: "open",
          args: ["-b", bundleId, safeTargetUrl],
          options: { stdio: "ignore" },
        };
      }
      return {
        command: "open",
        args: ["-a", appPath],
        options: { stdio: "ignore" },
      };
    },
    openDesktopTarget(options = {}) {
      const plan = this.createDesktopLaunchPlan(options);
      return execFilePromise(execFileFn, plan.command, plan.args, plan.options);
    },
    openDesktopTargetSync(options = {}) {
      const plan = this.createDesktopLaunchPlan(options);
      execFileSyncFn(plan.command, plan.args, plan.options);
    },
    readSecureBridgeState() {
      return readKeychainStateString(execFileSyncFn) || readFileStateString();
    },
    writeSecureBridgeState(serialized) {
      if (writeKeychainStateString(execFileSyncFn, serialized)) {
        return true;
      }
      fs.mkdirSync(STORE_DIR, { recursive: true });
      fs.writeFileSync(STORE_FILE, serialized, { mode: 0o600 });
      try {
        fs.chmodSync(STORE_FILE, 0o600);
      } catch {
        // Best effort on filesystems that support POSIX modes.
      }
      return true;
    },
    deleteSecureBridgeState() {
      const existed = fs.existsSync(STORE_FILE);
      let removedFileState = false;
      let removedKeychainState = false;
      try {
        fs.rmSync(STORE_FILE, { force: true });
        removedFileState = existed;
      } catch {
        removedFileState = false;
      }
      removedKeychainState = deleteKeychainStateString(execFileSyncFn);
      return {
        hadState: removedFileState || removedKeychainState,
        removedFileState,
        removedKeychainState,
      };
    },
    getDesktopDefaults() {
      return {
        bundleId: DEFAULT_BUNDLE_ID,
        appPath: DEFAULT_APP_PATH,
      };
    },
  };
}

function readFileStateString() {
  if (!fs.existsSync(STORE_FILE)) {
    return null;
  }
  try {
    return fs.readFileSync(STORE_FILE, "utf8");
  } catch {
    return null;
  }
}

function readKeychainStateString(execFileSyncFn) {
  try {
    return execFileSyncFn(
      "security",
      [
        "find-generic-password",
        "-s",
        KEYCHAIN_SERVICE,
        "-a",
        KEYCHAIN_ACCOUNT,
        "-w",
      ],
      { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }
    ).trim();
  } catch {
    return null;
  }
}

function writeKeychainStateString(execFileSyncFn, value) {
  try {
    execFileSyncFn(
      "security",
      [
        "add-generic-password",
        "-U",
        "-s",
        KEYCHAIN_SERVICE,
        "-a",
        KEYCHAIN_ACCOUNT,
        "-w",
        value,
      ],
      { stdio: ["ignore", "ignore", "ignore"] }
    );
    return true;
  } catch {
    return false;
  }
}

function deleteKeychainStateString(execFileSyncFn) {
  try {
    execFileSyncFn(
      "security",
      [
        "delete-generic-password",
        "-s",
        KEYCHAIN_SERVICE,
        "-a",
        KEYCHAIN_ACCOUNT,
      ],
      { stdio: ["ignore", "ignore", "ignore"] }
    );
    return true;
  } catch {
    return false;
  }
}

function execFilePromise(execFileFn, command, args, options) {
  return new Promise((resolve, reject) => {
    execFileFn(command, args, options, (error, stdout, stderr) => {
      if (error) {
        error.stdout = stdout;
        error.stderr = stderr;
        reject(error);
        return;
      }
      resolve({ stdout, stderr });
    });
  });
}

module.exports = {
  createMacOSHostPlatform,
};
