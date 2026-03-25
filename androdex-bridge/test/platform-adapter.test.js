const test = require("node:test");
const assert = require("node:assert/strict");

const { createHostPlatform } = require("../src/platform");

test("createHostPlatform returns the Windows adapter with refresh enabled", () => {
  const platform = createHostPlatform({ processPlatform: "win32" });

  assert.equal(platform.getPlatformId(), "win32");
  assert.equal(platform.getRefreshDefaults().enabled, true);
});

test("createHostPlatform returns the macOS adapter with refresh disabled", () => {
  const platform = createHostPlatform({ processPlatform: "darwin" });

  assert.equal(platform.getPlatformId(), "darwin");
  assert.equal(platform.getRefreshDefaults().enabled, false);
});
