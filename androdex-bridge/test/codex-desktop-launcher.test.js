const test = require("node:test");
const assert = require("node:assert/strict");

const { createDesktopLaunchPlan } = require("../src/codex-desktop-launcher");

test("darwin launch plans open the bundle for deep links and the app for empty targets", () => {
  const deepLinkPlan = createDesktopLaunchPlan({
    targetUrl: "codex://threads/thread-123",
    bundleId: "com.example.codex",
    appPath: "/Applications/Codex.app",
    platform: "darwin",
  });
  const appPlan = createDesktopLaunchPlan({
    targetUrl: "",
    bundleId: "com.example.codex",
    appPath: "/Applications/Codex.app",
    platform: "darwin",
  });

  assert.deepEqual(deepLinkPlan, {
    command: "open",
    args: ["-b", "com.example.codex", "codex://threads/thread-123"],
    options: { stdio: "ignore" },
  });
  assert.deepEqual(appPlan, {
    command: "open",
    args: ["-a", "/Applications/Codex.app"],
    options: { stdio: "ignore" },
  });
});

test("non-mac launch plans still require a concrete target URL", () => {
  assert.throws(
    () => createDesktopLaunchPlan({ platform: "linux", targetUrl: "" }),
    /target URL is required/
  );
});

test("windows launch plans pass a stable remote debugging port to the official app helper", () => {
  const launchPlan = createDesktopLaunchPlan({
    targetUrl: "codex://threads/thread-123",
    platform: "win32",
    windowsRemoteDebuggingPort: 9444,
  });

  assert.equal(launchPlan.command, "powershell.exe");
  assert.equal(launchPlan.args[0], "-NoProfile");
  assert.equal(launchPlan.args[3], "-File");
  assert.equal(launchPlan.args[5], "-TargetUrl");
  assert.equal(launchPlan.args[6], "codex://threads/thread-123");
  assert.deepEqual(launchPlan.args.slice(-2), [
    "-RemoteDebuggingPort",
    "9444",
  ]);
});
