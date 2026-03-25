// FILE: windows.js
// Purpose: Windows host adapter for bridge launch, desktop open, and secure state operations.
// Layer: CLI helper
// Exports: createWindowsHostPlatform
// Depends on: child_process, fs, os, path

const fs = require("fs");
const os = require("os");
const path = require("path");
const { execFile, execFileSync, spawn } = require("child_process");

const STORE_DIR = path.join(os.homedir(), ".androdex");
const STORE_FILE = path.join(STORE_DIR, "device-state.json");
const DEFAULT_BUNDLE_ID = "com.openai.codex";
const DEFAULT_APP_PATH = "/Applications/Codex.app";

function createWindowsHostPlatform({
  env = process.env,
  execFileFn = execFile,
  execFileSyncFn = execFileSync,
  spawnFn = spawn,
} = {}) {
  return {
    getPlatformId() {
      return "win32";
    },
    getRefreshDefaults() {
      return { enabled: true };
    },
    createCodexLaunchPlan({ cwd = "" } = {}) {
      return {
        command: env.ComSpec || "cmd.exe",
        args: ["/d", "/c", "codex app-server"],
        options: {
          stdio: ["pipe", "pipe", "pipe"],
          env: { ...env },
          cwd: cwd || process.cwd(),
          windowsHide: true,
        },
        description: "`cmd.exe /d /c codex app-server`",
      };
    },
    shutdownCodexProcess(child) {
      if (!child || child.killed || child.exitCode !== null) {
        return;
      }
      if (child.pid) {
        const killer = spawnFn("taskkill", ["/pid", String(child.pid), "/t", "/f"], {
          stdio: "ignore",
          windowsHide: true,
        });
        killer.on("error", () => {
          child.kill();
        });
        return;
      }
      child.kill();
    },
    createDesktopLaunchPlan({
      targetUrl = "",
    } = {}) {
      const safeTargetUrl = typeof targetUrl === "string" ? targetUrl.trim() : "";
      if (!safeTargetUrl) {
        throw new Error("Codex desktop target URL is required on this platform.");
      }
      return {
        command: env.ComSpec || "cmd.exe",
        args: ["/d", "/c", "start", "\"\"", safeTargetUrl],
        options: {
          stdio: "ignore",
          windowsHide: true,
        },
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
      return readFileStateString();
    },
    writeSecureBridgeState(serialized) {
      fs.mkdirSync(STORE_DIR, { recursive: true });
      fs.writeFileSync(STORE_FILE, serialized, { mode: 0o600 });
      try {
        fs.chmodSync(STORE_FILE, 0o600);
      } catch {
        // Best effort on filesystems with limited POSIX mode support.
      }
      return true;
    },
    deleteSecureBridgeState() {
      const existed = fs.existsSync(STORE_FILE);
      try {
        fs.rmSync(STORE_FILE, { force: true });
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
  createWindowsHostPlatform,
};
