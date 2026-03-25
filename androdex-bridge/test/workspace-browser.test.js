const test = require("node:test");
const assert = require("node:assert/strict");

const { arePathsEqual, createWorkspaceBrowser } = require("../src/workspace-browser");

test("arePathsEqual respects Windows case-insensitive paths and macOS case-sensitive paths", () => {
  assert.equal(arePathsEqual("C:\\Projects\\AppA", "c:\\projects\\appa", "win32"), true);
  assert.equal(arePathsEqual("/Users/rober/Work", "/users/rober/work", "darwin"), false);
});

test("Windows root browsing dedupes case-variant recent workspaces", () => {
  const browser = createWorkspaceBrowser({
    platform: "win32",
    osModule: { homedir: () => "C:\\Users\\rober" },
    fsModule: {
      existsSync(candidate) {
        return candidate === "C:\\";
      },
      statSync() {
        return { isDirectory: () => true };
      },
    },
  });

  const result = browser.listDirectory({
    activeCwd: "C:\\Projects\\AppA",
    recentWorkspaces: [
      "C:\\Projects\\AppA",
      "c:\\projects\\appa",
      "D:\\Client\\SiteB",
    ],
  });

  assert.deepEqual(
    result.rootEntries.map((entry) => entry.path),
    ["C:\\", "C:\\Users\\rober", "C:\\Projects\\AppA", "D:\\Client\\SiteB"]
  );
  assert.equal(result.rootEntries.find((entry) => entry.path === "C:\\Projects\\AppA")?.isActive, true);
});
