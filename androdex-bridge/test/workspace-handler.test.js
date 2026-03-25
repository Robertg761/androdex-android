// FILE: workspace-handler.test.js
// Purpose: Verifies workspace browsing and activation RPC behavior.
// Layer: Unit test
// Exports: node:test suite
// Depends on: node:test, node:assert/strict, ../src/workspace-browser, ../src/workspace-handler

const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("path");
const fs = require("node:fs");
const os = require("node:os");
const { execFileSync } = require("node:child_process");

const { createWorkspaceBrowser } = require("../src/workspace-browser");
const { handleWorkspaceRequest } = require("../src/workspace-handler");

function flushMicrotasks() {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

async function waitForResponses(responses, minimumCount = 1) {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (responses.length >= minimumCount) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  throw new Error("Timed out waiting for workspace handler response.");
}

function git(cwd, ...args) {
  return execFileSync("git", args, {
    cwd,
    encoding: "utf8",
  }).trim();
}

function makeRepo() {
  const repoDir = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-workspace-handler-"));
  git(repoDir, "init", "-b", "main");
  git(repoDir, "config", "user.name", "Androdex Tests");
  git(repoDir, "config", "user.email", "tests@example.com");
  fs.writeFileSync(path.join(repoDir, "README.md"), "# Test\n");
  git(repoDir, "add", "README.md");
  git(repoDir, "commit", "-m", "Initial commit");
  return repoDir;
}

function cleanupDirectory(candidatePath) {
  for (let attempt = 0; attempt < 5; attempt += 1) {
    try {
      fs.rmSync(candidatePath, { recursive: true, force: true });
      return;
    } catch (error) {
      if (error?.code !== "EBUSY" || attempt === 4) {
        throw error;
      }
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 50);
    }
  }
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

test("workspace/revertPatchPreview reports staged-file conflicts", async () => {
  const repoDir = makeRepo();
  const responses = [];

  try {
    fs.writeFileSync(path.join(repoDir, "README.md"), "# Test\nupdated\n");
    git(repoDir, "add", "README.md");

    handleWorkspaceRequest(
      JSON.stringify({
        id: "4",
        method: "workspace/revertPatchPreview",
        params: {
          cwd: repoDir,
          forwardPatch: [
            "diff --git a/README.md b/README.md",
            "--- a/README.md",
            "+++ b/README.md",
            "@@ -1 +1 @@",
            "-# Test",
            "+# Test updated",
          ].join("\n"),
        },
      }),
      (response) => responses.push(JSON.parse(response))
    );

    await waitForResponses(responses);

    assert.equal(responses[0].result.canRevert, false);
    assert.deepEqual(responses[0].result.stagedFiles, ["README.md"]);
  } finally {
    cleanupDirectory(repoDir);
  }
});

test("workspace/revertPatchApply reverse-applies a clean patch and returns git status", async () => {
  const repoDir = makeRepo();
  const responses = [];

  try {
    const original = fs.readFileSync(path.join(repoDir, "README.md"), "utf8");
    fs.writeFileSync(path.join(repoDir, "README.md"), "# Test updated\n");

    const forwardPatch = git(repoDir, "diff", "--binary", "--find-renames", "HEAD");

    handleWorkspaceRequest(
      JSON.stringify({
        id: "5",
        method: "workspace/revertPatchApply",
        params: {
          cwd: repoDir,
          forwardPatch,
        },
      }),
      (response) => responses.push(JSON.parse(response))
    );

    await waitForResponses(responses);

    assert.equal(responses[0].result.success, true);
    assert.equal(fs.readFileSync(path.join(repoDir, "README.md"), "utf8"), original);
    assert.equal(responses[0].result.status.dirty, false);
  } finally {
    cleanupDirectory(repoDir);
  }
});
