// FILE: workspace-handler.test.js
// Purpose: Verifies workspace browsing and activation RPC behavior.
// Layer: Unit test
// Exports: node:test suite
// Depends on: node:test, node:assert/strict, ../src/workspace-browser, ../src/workspace-handler

const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("path");

const { createWorkspaceBrowser } = require("../src/workspace-browser");
const { handleWorkspaceRequest } = require("../src/workspace-handler");

function flushMicrotasks() {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

test("workspace browser root view lists drives, home, and recent workspaces on Windows", () => {
  const browser = createWorkspaceBrowser({
    platform: "win32",
    pathModule: path.win32,
    osModule: { homedir: () => "C:\\Users\\rober" },
    fsModule: {
      existsSync(candidate) {
        return candidate === "C:\\" || candidate === "D:\\";
      },
      statSync() {
        return { isDirectory: () => true };
      },
    },
  });

  const result = browser.listDirectory({
    activeCwd: "D:\\Client\\SiteB",
    recentWorkspaces: ["D:\\Client\\SiteB", "C:\\Projects\\AppA"],
  });

  assert.deepEqual(
    result.rootEntries.map((entry) => entry.path),
    ["C:\\", "D:\\", "C:\\Users\\rober", "D:\\Client\\SiteB", "C:\\Projects\\AppA"]
  );
  assert.equal(result.rootEntries.find((entry) => entry.path === "D:\\Client\\SiteB")?.isActive, true);
});

test("workspace browser child listing returns only directories", () => {
  const browser = createWorkspaceBrowser({
    platform: "win32",
    pathModule: path.win32,
    fsModule: {
      statSync() {
        return { isDirectory: () => true };
      },
      readdirSync() {
        return [
          { name: "beta", isDirectory: () => true },
          { name: "notes.txt", isDirectory: () => false },
          { name: "alpha", isDirectory: () => true },
        ];
      },
    },
  });

  const result = browser.listDirectory({
    requestedPath: "C:\\Projects",
    activeCwd: "C:\\Projects\\alpha",
    recentWorkspaces: [],
  });

  assert.equal(result.requestedPath, "C:\\Projects");
  assert.equal(result.parentPath, "C:\\");
  assert.deepEqual(result.entries.map((entry) => entry.path), [
    "C:\\Projects\\alpha",
    "C:\\Projects\\beta",
  ]);
  assert.equal(result.entries[0].isActive, true);
});

test("workspace/listRecent marks the active workspace", async () => {
  const responses = [];

  handleWorkspaceRequest(
    JSON.stringify({
      id: "1",
      method: "workspace/listRecent",
      params: {},
    }),
    (response) => responses.push(JSON.parse(response)),
    {
      platform: "win32",
      workspaceBrowser: createWorkspaceBrowser({
        platform: "win32",
        pathModule: path.win32,
      }),
      getWorkspaceState: () => ({
        activeCwd: "C:\\Projects\\AppA",
        recentWorkspaces: ["C:\\Projects\\AppA", "D:\\Client\\SiteB"],
      }),
    }
  );

  await flushMicrotasks();

  assert.deepEqual(
    responses[0].result.recentWorkspaces.map((entry) => ({
      path: entry.path,
      isActive: entry.isActive,
    })),
    [
      { path: "C:\\Projects\\AppA", isActive: true },
      { path: "D:\\Client\\SiteB", isActive: false },
    ]
  );
});

test("workspace/activate delegates to the runtime activator", async () => {
  const responses = [];
  let activatedPath = "";

  handleWorkspaceRequest(
    JSON.stringify({
      id: "2",
      method: "workspace/activate",
      params: { cwd: "C:\\Projects\\AppA" },
    }),
    (response) => responses.push(JSON.parse(response)),
    {
      platform: "win32",
      activateWorkspace: async ({ cwd }) => {
        activatedPath = cwd;
        return { currentCwd: cwd, workspaceActive: true };
      },
      workspaceBrowser: createWorkspaceBrowser({
        platform: "win32",
        pathModule: path.win32,
        fsModule: {
          statSync() {
            return { isDirectory: () => true };
          },
        },
      }),
    }
  );

  await flushMicrotasks();

  assert.equal(activatedPath, "C:\\Projects\\AppA");
  assert.equal(responses[0].result.currentCwd, "C:\\Projects\\AppA");
});

test("workspace/activate rejects non-absolute paths", async () => {
  const responses = [];

  handleWorkspaceRequest(
    JSON.stringify({
      id: "3",
      method: "workspace/activate",
      params: { cwd: "relative\\folder" },
    }),
    (response) => responses.push(JSON.parse(response)),
    {
      platform: "win32",
      workspaceBrowser: createWorkspaceBrowser({
        platform: "win32",
        pathModule: path.win32,
      }),
      activateWorkspace: async ({ cwd }) => ({ currentCwd: cwd }),
    }
  );

  await flushMicrotasks();

  assert.equal(responses[0].error.data.errorCode, "workspace_path_not_absolute");
});
