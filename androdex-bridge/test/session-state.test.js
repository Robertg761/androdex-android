const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");

test("openLastActiveThread builds the thread deep link and delegates desktop opening", () => {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-session-state-"));
  const stateDir = path.join(tempDir, ".androdex");
  fs.mkdirSync(stateDir, { recursive: true });
  fs.writeFileSync(path.join(stateDir, "last-thread.json"), JSON.stringify({
    threadId: "thread-abc",
    source: "phone",
    updatedAt: new Date().toISOString(),
  }));

  const originalHome = process.env.HOME;
  process.env.HOME = tempDir;

  const sessionStatePath = require.resolve("../src/session-state");
  delete require.cache[sessionStatePath];
  const { openLastActiveThread } = require("../src/session-state");

  let capturedTargetUrl = null;
  const state = openLastActiveThread({
    platformAdapter: {
      createDesktopLaunchPlan({ targetUrl }) {
        capturedTargetUrl = targetUrl;
        return {
          command: "/usr/bin/true",
          args: [],
          options: { stdio: "ignore" },
        };
      },
    },
  });

  process.env.HOME = originalHome;
  delete require.cache[sessionStatePath];

  assert.equal(capturedTargetUrl, "codex://threads/thread-abc");
  assert.equal(state.threadId, "thread-abc");
});
