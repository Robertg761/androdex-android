const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const { arePathsEqual, createWorkspaceBrowser } = require("../src/workspace-browser");

test("arePathsEqual keeps macOS path comparisons case-sensitive", () => {
  assert.equal(arePathsEqual("/Users/rober/Work", "/users/rober/work", "darwin"), false);
});

test("root browsing dedupes repeated recent workspaces on macOS", () => {
  const browser = createWorkspaceBrowser({
    platform: "darwin",
    pathModule: path.posix,
    osModule: { homedir: () => "/Users/rober" },
  });

  const result = browser.listDirectory({
    activeCwd: "/Users/rober/Projects/AppA",
    recentWorkspaces: [
      "/Users/rober/Projects/AppA",
      "/Users/rober/Projects/AppA",
      "/Users/rober/Client/SiteB",
    ],
  });

  assert.deepEqual(
    result.rootEntries.map((entry) => entry.path),
    ["/", "/Users/rober", "/Users/rober/Projects/AppA", "/Users/rober/Client/SiteB"]
  );
  assert.equal(result.rootEntries.find((entry) => entry.path === "/Users/rober/Projects/AppA")?.isActive, true);
});
