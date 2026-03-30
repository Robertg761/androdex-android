const test = require("node:test");
const assert = require("node:assert/strict");

const {
  createCodexLaunchPlan,
  shutdownCodexProcess,
} = require("../src/codex-transport");

test("createCodexLaunchPlan launches codex app-server in the selected workspace", () => {
  const launchPlan = createCodexLaunchPlan({
    env: { PATH: "/usr/bin" },
    cwd: "/tmp/workspace",
  });

  assert.equal(launchPlan.command, "codex");
  assert.deepEqual(launchPlan.args, ["app-server"]);
  assert.equal(launchPlan.options.cwd, "/tmp/workspace");
  assert.equal(launchPlan.options.env.PATH, "/usr/bin");
});

test("shutdownCodexProcess uses SIGTERM for the local codex child", () => {
  let signal = null;

  shutdownCodexProcess({
    killed: false,
    exitCode: null,
    kill(nextSignal) {
      signal = nextSignal;
    },
  });

  assert.equal(signal, "SIGTERM");
});
