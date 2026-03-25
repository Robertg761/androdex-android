// FILE: index.js
// Purpose: Selects the host platform adapter used by the bridge runtime.
// Layer: CLI helper
// Exports: createHostPlatform
// Depends on: child_process, fs, os, path, ./windows, ./macos

const fs = require("fs");
const os = require("os");
const path = require("path");
const { execFile, execFileSync } = require("child_process");
const { createWindowsHostPlatform } = require("./windows");
const { createMacOSHostPlatform } = require("./macos");

function createHostPlatform({
  env = process.env,
  processPlatform = process.platform,
  execFileFn,
  execFileSyncFn,
  spawnFn,
} = {}) {
  if (processPlatform === "win32") {
    return createWindowsHostPlatform({ env, execFileFn, execFileSyncFn, spawnFn });
  }

  if (processPlatform === "darwin") {
    return createMacOSHostPlatform({ env, execFileFn, execFileSyncFn });
  }

  return createGenericHostPlatform({ env, processPlatform, execFileFn, execFileSyncFn });
}

function createGenericHostPlatform({
  env = process.env,
  processPlatform = process.platform,
  execFileFn = execFile,
  execFileSyncFn = execFileSync,
} = {}) {
  const storeDir = path.join(os.homedir(), ".androdex");
  const storeFile = path.join(storeDir, "device-state.json");
  return {
    getPlatformId() {
      return processPlatform;
    },
    getRefreshDefaults() {
      return { enabled: false };
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
    createDesktopLaunchPlan({ targetUrl = "" } = {}) {
      const safeTargetUrl = typeof targetUrl === "string" ? targetUrl.trim() : "";
      if (!safeTargetUrl) {
        throw new Error("Codex desktop target URL is required on this platform.");
      }
      return {
        command: "xdg-open",
        args: [safeTargetUrl],
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
      if (!fs.existsSync(storeFile)) {
        return null;
      }
      try {
        return fs.readFileSync(storeFile, "utf8");
      } catch {
        return null;
      }
    },
    writeSecureBridgeState(serialized) {
      fs.mkdirSync(storeDir, { recursive: true });
      fs.writeFileSync(storeFile, serialized, { mode: 0o600 });
      try {
        fs.chmodSync(storeFile, 0o600);
      } catch {
        // Best effort on filesystems with limited POSIX mode support.
      }
      return true;
    },
    deleteSecureBridgeState() {
      const existed = fs.existsSync(storeFile);
      try {
        fs.rmSync(storeFile, { force: true });
        return {
          hadState: existed,
          removedFileState: existed,
          removedKeychainState: false,
        };
      } catch {
        return {
          hadState: false,
          removedFileState: false,
          removedKeychainState: false,
        };
      }
    },
    getDesktopDefaults() {
      return {
        bundleId: "com.openai.codex",
        appPath: "/Applications/Codex.app",
      };
    },
  };
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
  createHostPlatform,
};
