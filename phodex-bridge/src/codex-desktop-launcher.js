// FILE: codex-desktop-launcher.js
// Purpose: Opens Codex desktop deep links across supported host platforms.
// Layer: CLI helper
// Exports: openCodexDesktopTarget, openCodexDesktopTargetSync
// Depends on: child_process

const { execFile, execFileSync } = require("child_process");

function openCodexDesktopTarget({
  targetUrl = "",
  bundleId = "com.openai.codex",
  appPath = "/Applications/Codex.app",
  platform = process.platform,
} = {}) {
  const plan = createDesktopLaunchPlan({ targetUrl, bundleId, appPath, platform });
  return execFilePromise(plan.command, plan.args, plan.options);
}

function openCodexDesktopTargetSync({
  targetUrl = "",
  bundleId = "com.openai.codex",
  appPath = "/Applications/Codex.app",
  platform = process.platform,
} = {}) {
  const plan = createDesktopLaunchPlan({ targetUrl, bundleId, appPath, platform });
  execFileSync(plan.command, plan.args, plan.options);
}

function createDesktopLaunchPlan({
  targetUrl = "",
  bundleId = "com.openai.codex",
  appPath = "/Applications/Codex.app",
  platform = process.platform,
} = {}) {
  const safeTargetUrl = typeof targetUrl === "string" ? targetUrl.trim() : "";

  if (platform === "darwin") {
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
  }

  if (!safeTargetUrl) {
    throw new Error("Codex desktop target URL is required on this platform.");
  }

  if (platform === "win32") {
    return {
      command: "cmd.exe",
      args: ["/d", "/c", "start", "\"\"", safeTargetUrl],
      options: {
        stdio: "ignore",
        windowsHide: true,
      },
    };
  }

  return {
    command: "xdg-open",
    args: [safeTargetUrl],
    options: { stdio: "ignore" },
  };
}

function execFilePromise(command, args, options) {
  return new Promise((resolve, reject) => {
    execFile(command, args, options, (error, stdout, stderr) => {
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
  createDesktopLaunchPlan,
  openCodexDesktopTarget,
  openCodexDesktopTargetSync,
};
