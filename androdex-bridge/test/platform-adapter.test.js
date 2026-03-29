const test = require("node:test");
const assert = require("node:assert/strict");

const { createHostPlatform } = require("../src/platform");
const { createDesktopLaunchPlan } = require("../src/codex-desktop-launcher");

test("createHostPlatform returns the Windows adapter with refresh enabled", () => {
  const platform = createHostPlatform({ processPlatform: "win32" });

  assert.equal(platform.getPlatformId(), "win32");
  assert.equal(platform.getRefreshDefaults().enabled, true);
});

test("createHostPlatform returns the macOS adapter with refresh enabled", () => {
  const platform = createHostPlatform({ processPlatform: "darwin" });

  assert.equal(platform.getPlatformId(), "darwin");
  assert.equal(platform.getRefreshDefaults().enabled, true);
});

test("Windows platform adapter preserves the remote debugging port launch plan", () => {
  const platform = createHostPlatform({ processPlatform: "win32" });

  const launchPlan = createDesktopLaunchPlan({
    targetUrl: "codex://threads/thread-123",
    platform: "win32",
    platformAdapter: platform,
    windowsRemoteDebuggingPort: 9444,
  });

  assert.equal(launchPlan.command, "powershell.exe");
  assert.deepEqual(launchPlan.args.slice(-2), [
    "-RemoteDebuggingPort",
    "9444",
  ]);
});
