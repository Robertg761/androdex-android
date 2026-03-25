// FILE: git-handler.test.js
// Purpose: Covers branch, status, and worktree behavior for the local git bridge.
// Layer: Unit Test
// Exports: node:test cases
// Depends on: node:test, assert, child_process, fs, os, path, git-handler

const assert = require("node:assert/strict");
const test = require("node:test");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const { __test, gitStatus } = require("../src/git-handler");

function git(cwd, ...args) {
  return execFileSync("git", args, {
    cwd,
    encoding: "utf8",
  }).trim();
}

function makeTempRepo() {
  const repoDir = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-git-handler-"));
  git(repoDir, "init", "-b", "main");
  git(repoDir, "config", "user.name", "Androdex Tests");
  git(repoDir, "config", "user.email", "tests@example.com");
  fs.writeFileSync(path.join(repoDir, "README.md"), "# Test\n");
  fs.mkdirSync(path.join(repoDir, "androdex-bridge", "src"), { recursive: true });
  fs.writeFileSync(path.join(repoDir, "androdex-bridge", "src", "index.js"), "export const ready = true;\n");
  git(repoDir, "add", "README.md");
  git(repoDir, "add", "androdex-bridge/src/index.js");
  git(repoDir, "commit", "-m", "Initial commit");
  git(repoDir, "branch", "feature/clean-switch");
  return repoDir;
}

function canonicalPath(candidatePath) {
  return fs.realpathSync.native(candidatePath);
}

function makeBareRemote() {
  const remoteDir = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-git-handler-remote-"));
  git(remoteDir, "init", "--bare");
  return remoteDir;
}

function pushRemoteOnlyBranch(repoDir, remoteDir, branchName) {
  git(repoDir, "remote", "add", "origin", remoteDir);
  git(repoDir, "push", "-u", "origin", "main");
  git(repoDir, "branch", branchName);
  git(repoDir, "push", "-u", "origin", branchName);
  git(repoDir, "branch", "-D", branchName);
}

test("normalizeBranchListEntry strips linked-worktree markers", () => {
  assert.deepEqual(__test.normalizeBranchListEntry("+ main"), {
    isCurrent: false,
    isCheckedOutElsewhere: true,
    name: "main",
  });
  assert.deepEqual(__test.normalizeBranchListEntry("* feature/mobile"), {
    isCurrent: true,
    isCheckedOutElsewhere: false,
    name: "feature/mobile",
  });
});

test("gitBranches reports branches checked out in another worktree", async () => {
  const repoDir = makeTempRepo();
  const siblingWorktree = path.join(path.dirname(repoDir), `${path.basename(repoDir)}-wt-feature`);

  try {
    git(repoDir, "worktree", "add", siblingWorktree, "feature/clean-switch");

    const result = await __test.gitBranches(repoDir);

    assert.deepEqual(result.branchesCheckedOutElsewhere, ["feature/clean-switch"]);
    assert.equal(result.worktreePathByBranch["feature/clean-switch"], canonicalPath(siblingWorktree));
  } finally {
    fs.rmSync(repoDir, { recursive: true, force: true });
    fs.rmSync(siblingWorktree, { recursive: true, force: true });
  }
});

test("gitBranches scopes worktree and local checkout paths to subprojects", async () => {
  const repoDir = makeTempRepo();
  const localProjectDir = path.join(repoDir, "androdex-bridge");
  const siblingWorktree = path.join(path.dirname(repoDir), `${path.basename(repoDir)}-wt-feature`);
  const siblingProjectDir = path.join(siblingWorktree, "androdex-bridge");

  try {
    git(repoDir, "worktree", "add", siblingWorktree, "feature/clean-switch");

    const result = await __test.gitBranches(siblingProjectDir);

    assert.equal(
      result.worktreePathByBranch["feature/clean-switch"],
      canonicalPath(siblingProjectDir)
    );
    assert.equal(result.localCheckoutPath, canonicalPath(localProjectDir));
  } finally {
    fs.rmSync(repoDir, { recursive: true, force: true });
    fs.rmSync(siblingWorktree, { recursive: true, force: true });
  }
});

test("gitCheckout switches branches and surfaces worktree conflicts", async () => {
  const repoDir = makeTempRepo();
  const siblingWorktree = path.join(path.dirname(repoDir), `${path.basename(repoDir)}-wt-feature`);

  try {
    const checkoutResult = await __test.gitCheckout(repoDir, { branch: "feature/clean-switch" });
    assert.equal(checkoutResult.current, "feature/clean-switch");

    git(repoDir, "checkout", "main");
    git(repoDir, "worktree", "add", siblingWorktree, "feature/clean-switch");

    await assert.rejects(
      __test.gitCheckout(repoDir, { branch: "feature/clean-switch" }),
      (error) => error?.errorCode === "checkout_branch_in_other_worktree"
    );
  } finally {
    fs.rmSync(repoDir, { recursive: true, force: true });
    fs.rmSync(siblingWorktree, { recursive: true, force: true });
  }
});

test("gitCreateBranch normalizes names and rejects invalid ones", async () => {
  const repoDir = makeTempRepo();

  try {
    const result = await __test.gitCreateBranch(repoDir, { name: "feature / login page" });
    assert.equal(result.branch, "remodex/feature/login-page");
    assert.equal(git(repoDir, "rev-parse", "--abbrev-ref", "HEAD"), "remodex/feature/login-page");

    await assert.rejects(
      __test.gitCreateBranch(repoDir, { name: "feature..oops" }),
      (error) => error?.errorCode === "invalid_branch_name"
    );
  } finally {
    fs.rmSync(repoDir, { recursive: true, force: true });
  }
});

test("gitCreateBranch rejects origin-only duplicates", async () => {
  const repoDir = makeTempRepo();
  const remoteDir = makeBareRemote();

  try {
    pushRemoteOnlyBranch(repoDir, remoteDir, "remodex/remote-only");

    await assert.rejects(
      __test.gitCreateBranch(repoDir, { name: "remote-only" }),
      (error) => error?.errorCode === "branch_exists"
    );
  } finally {
    fs.rmSync(repoDir, { recursive: true, force: true });
    fs.rmSync(remoteDir, { recursive: true, force: true });
  }
});

test("gitStatus reports local-only commits and publishedToRemote without upstream", async () => {
  const repoDir = makeTempRepo();
  const remoteDir = makeBareRemote();

  try {
    git(repoDir, "remote", "add", "origin", remoteDir);
    git(repoDir, "push", "-u", "origin", "main");

    fs.writeFileSync(path.join(repoDir, "README.md"), "# Test\n\nlocal\n");
    git(repoDir, "add", "README.md");
    git(repoDir, "commit", "-m", "Local commit");
    git(repoDir, "config", "--unset", "branch.main.remote");
    git(repoDir, "config", "--unset", "branch.main.merge");

    const localOnlyResult = await gitStatus(repoDir);
    assert.equal(localOnlyResult.localOnlyCommitCount, 1);
    assert.equal(localOnlyResult.state, "no_upstream");

    git(repoDir, "checkout", "-b", "remodex/published-no-upstream");
    git(repoDir, "push", "origin", "HEAD");
    const publishedResult = await gitStatus(repoDir);
    assert.equal(publishedResult.publishedToRemote, true);
  } finally {
    fs.rmSync(repoDir, { recursive: true, force: true });
    fs.rmSync(remoteDir, { recursive: true, force: true });
  }
});

test("gitCreateWorktree creates a managed worktree and can remove it", async () => {
  const repoDir = makeTempRepo();
  const projectDir = path.join(repoDir, "androdex-bridge");
  const codexHome = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-codex-home-"));
  const previousCodexHome = process.env.CODEX_HOME;

  process.env.CODEX_HOME = codexHome;

  try {
    const result = await __test.gitCreateWorktree(projectDir, {
      name: "new-worktree",
      baseBranch: "main",
    });

    assert.equal(result.branch, "remodex/new-worktree");
    assert.equal(result.alreadyExisted, false);
    assert.equal(path.basename(result.worktreePath), "androdex-bridge");
    assert.equal(git(result.worktreePath, "rev-parse", "--abbrev-ref", "HEAD"), "remodex/new-worktree");

    await __test.gitRemoveWorktree(result.worktreePath, { branch: result.branch });
    assert.equal(fs.existsSync(path.dirname(result.worktreePath)), false);
    assert.equal(git(repoDir, "branch", "--list", result.branch), "");
  } finally {
    if (previousCodexHome === undefined) {
      delete process.env.CODEX_HOME;
    } else {
      process.env.CODEX_HOME = previousCodexHome;
    }
    fs.rmSync(repoDir, { recursive: true, force: true });
    fs.rmSync(codexHome, { recursive: true, force: true });
  }
});

test("gitCreateWorktree reuses an existing worktree for the same branch", async () => {
  const repoDir = makeTempRepo();
  const projectDir = path.join(repoDir, "androdex-bridge");
  const codexHome = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-codex-home-"));
  const previousCodexHome = process.env.CODEX_HOME;
  const siblingWorktree = path.join(path.dirname(repoDir), `${path.basename(repoDir)}-wt-remodex-existing`);

  process.env.CODEX_HOME = codexHome;

  try {
    git(repoDir, "branch", "remodex/existing");
    git(repoDir, "worktree", "add", siblingWorktree, "remodex/existing");

    const result = await __test.gitCreateWorktree(projectDir, {
      name: "existing",
      baseBranch: "main",
    });

    assert.equal(result.alreadyExisted, true);
    assert.equal(result.worktreePath, canonicalPath(path.join(siblingWorktree, "androdex-bridge")));
  } finally {
    if (previousCodexHome === undefined) {
      delete process.env.CODEX_HOME;
    } else {
      process.env.CODEX_HOME = previousCodexHome;
    }
    fs.rmSync(repoDir, { recursive: true, force: true });
    fs.rmSync(siblingWorktree, { recursive: true, force: true });
    fs.rmSync(codexHome, { recursive: true, force: true });
  }
});

test("gitCreateWorktree moves tracked and untracked changes when base matches current branch", async () => {
  const repoDir = makeTempRepo();
  const projectDir = path.join(repoDir, "androdex-bridge");
  const codexHome = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-codex-home-"));
  const previousCodexHome = process.env.CODEX_HOME;

  process.env.CODEX_HOME = codexHome;

  try {
    fs.writeFileSync(path.join(repoDir, "README.md"), "# Test\nmoved\n");
    fs.writeFileSync(path.join(projectDir, "scratch.txt"), "carry me\n");

    const result = await __test.gitCreateWorktree(projectDir, {
      name: "dirty-worktree",
      baseBranch: "main",
    });

    assert.equal(fs.readFileSync(path.join(path.dirname(result.worktreePath), "README.md"), "utf8"), "# Test\nmoved\n");
    assert.equal(fs.readFileSync(path.join(result.worktreePath, "scratch.txt"), "utf8"), "carry me\n");
    assert.equal(git(repoDir, "status", "--short"), "");
  } finally {
    if (previousCodexHome === undefined) {
      delete process.env.CODEX_HOME;
    } else {
      process.env.CODEX_HOME = previousCodexHome;
    }
    fs.rmSync(repoDir, { recursive: true, force: true });
    fs.rmSync(codexHome, { recursive: true, force: true });
  }
});

test("gitCreateWorktree can copy changes without cleaning the local checkout", async () => {
  const repoDir = makeTempRepo();
  const projectDir = path.join(repoDir, "androdex-bridge");
  const codexHome = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-codex-home-"));
  const previousCodexHome = process.env.CODEX_HOME;

  process.env.CODEX_HOME = codexHome;

  try {
    fs.writeFileSync(path.join(repoDir, "README.md"), "# Test\ncopied\n");
    fs.writeFileSync(path.join(projectDir, "scratch.txt"), "keep me too\n");

    const result = await __test.gitCreateWorktree(projectDir, {
      name: "copied-worktree",
      baseBranch: "main",
      changeTransfer: "copy",
    });

    assert.equal(fs.readFileSync(path.join(path.dirname(result.worktreePath), "README.md"), "utf8"), "# Test\ncopied\n");
    assert.equal(fs.readFileSync(path.join(result.worktreePath, "scratch.txt"), "utf8"), "keep me too\n");
    assert.match(git(repoDir, "status", "--short"), /README\.md/);
    assert.equal(fs.readFileSync(path.join(projectDir, "scratch.txt"), "utf8"), "keep me too\n");
  } finally {
    if (previousCodexHome === undefined) {
      delete process.env.CODEX_HOME;
    } else {
      process.env.CODEX_HOME = previousCodexHome;
    }
    fs.rmSync(repoDir, { recursive: true, force: true });
    fs.rmSync(codexHome, { recursive: true, force: true });
  }
});

test("gitCreateWorktree rejects dirty handoff when the chosen base branch is not current", async () => {
  const repoDir = makeTempRepo();

  try {
    fs.writeFileSync(path.join(repoDir, "README.md"), "# Test\nmismatch\n");

    await assert.rejects(
      __test.gitCreateWorktree(repoDir, {
        name: "mismatch",
        baseBranch: "feature/clean-switch",
      }),
      (error) => error?.errorCode === "dirty_worktree_base_mismatch"
    );
  } finally {
    fs.rmSync(repoDir, { recursive: true, force: true });
  }
});
