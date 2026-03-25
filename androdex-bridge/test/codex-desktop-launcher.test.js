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
