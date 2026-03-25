const test = require("node:test");
const assert = require("node:assert/strict");

const {
  createCodexLaunchPlan,
  shutdownCodexProcess,
} = require("../src/codex-transport");
const { createHostPlatform } = require("../src/platform");

test("createCodexLaunchPlan uses cmd.exe on Windows", () => {
  const platformAdapter = createHostPlatform({
    processPlatform: "win32",
    env: { ComSpec: "C:\\Windows\\System32\\cmd.exe" },
  });

  const launchPlan = createCodexLaunchPlan({
    env: { ComSpec: "C:\\Windows\\System32\\cmd.exe" },
    cwd: "/tmp/workspace",
    platformAdapter,
  });

  assert.equal(launchPlan.command, "C:\\Windows\\System32\\cmd.exe");
  assert.deepEqual(launchPlan.args, ["/d", "/c", "codex app-server"]);
  assert.equal(launchPlan.options.windowsHide, true);
  assert.equal(launchPlan.options.cwd, "/tmp/workspace");
});

test("createCodexLaunchPlan uses direct codex launch on macOS", () => {
  const platformAdapter = createHostPlatform({ processPlatform: "darwin" });

  const launchPlan = createCodexLaunchPlan({
    env: {},
    cwd: "/tmp/workspace",
    platformAdapter,
  });

  assert.equal(launchPlan.command, "codex");
  assert.deepEqual(launchPlan.args, ["app-server"]);
  assert.equal(launchPlan.options.cwd, "/tmp/workspace");
});

test("shutdownCodexProcess uses taskkill on Windows", async () => {
  const spawnCalls = [];
  const platformAdapter = createHostPlatform({
    processPlatform: "win32",
    spawnFn(command, args, options) {
      spawnCalls.push({ command, args, options });
      return {
        on(eventName, handler) {
          if (eventName === "error") {
            this.onError = handler;
          }
        },
      };
    },
  });

  shutdownCodexProcess({
    pid: 4321,
    killed: false,
    exitCode: null,
    kill() {
      throw new Error("kill fallback should not run");
    },
  }, platformAdapter);

  assert.deepEqual(spawnCalls, [{
    command: "taskkill",
    args: ["/pid", "4321", "/t", "/f"],
    options: { stdio: "ignore", windowsHide: true },
  }]);
});

test("shutdownCodexProcess uses SIGTERM on macOS", () => {
  let signal = null;
  const platformAdapter = createHostPlatform({ processPlatform: "darwin" });

  shutdownCodexProcess({
    killed: false,
    exitCode: null,
    kill(nextSignal) {
      signal = nextSignal;
    },
  }, platformAdapter);

  assert.equal(signal, "SIGTERM");
});
