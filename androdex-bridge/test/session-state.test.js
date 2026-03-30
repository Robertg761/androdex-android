const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");
const childProcess = require("child_process");

test("openLastActiveThread opens the remembered deep link with open -b", () => {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-session-state-"));
  const stateDir = path.join(tempDir, ".androdex");
  fs.mkdirSync(stateDir, { recursive: true });
  fs.writeFileSync(path.join(stateDir, "last-thread.json"), JSON.stringify({
    threadId: "thread-abc",
    source: "phone",
    updatedAt: new Date().toISOString(),
  }));

  const originalHome = process.env.HOME;
  const originalExecFileSync = childProcess.execFileSync;
  process.env.HOME = tempDir;
  const calls = [];
  childProcess.execFileSync = (command, args) => {
    calls.push({ command, args });
    return "";
  };

  const sessionStatePath = require.resolve("../src/session-state");
  delete require.cache[sessionStatePath];
  const { openLastActiveThread } = require("../src/session-state");

  const state = openLastActiveThread();

  childProcess.execFileSync = originalExecFileSync;
  process.env.HOME = originalHome;
  delete require.cache[sessionStatePath];
  fs.rmSync(tempDir, { recursive: true, force: true });

  assert.deepEqual(calls, [{
    command: "open",
    args: ["-b", "com.openai.codex", "codex://threads/thread-abc"],
  }]);
  assert.equal(state.threadId, "thread-abc");
});
