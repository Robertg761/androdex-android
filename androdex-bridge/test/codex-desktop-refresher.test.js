const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const {
  CodexDesktopRefresher,
  readBridgeConfig,
} = require("../src/codex-desktop-refresher");

const MAC_REFRESH_SCRIPT_PATH = path.join(__dirname, "..", "src", "scripts", "codex-refresh.applescript");

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

test("readBridgeConfig keeps the public relay default and disables refresh unless explicitly enabled", () => {
  const defaultConfig = readBridgeConfig({ env: {} });
  const enabledConfig = readBridgeConfig({
    env: {
      ANDRODEX_REFRESH_ENABLED: "true",
      ANDRODEX_RELAY: "ws://127.0.0.1:8787/relay",
      ANDRODEX_CODEX_ENDPOINT: "ws://127.0.0.1:8080",
    },
  });

  assert.equal(defaultConfig.relayUrl, "wss://relay.androdex.xyz/relay");
  assert.equal(defaultConfig.refreshEnabled, false);
  assert.equal(enabledConfig.refreshEnabled, true);
  assert.equal(enabledConfig.relayUrl, "ws://127.0.0.1:8787/relay");
  assert.equal(enabledConfig.codexEndpoint, "ws://127.0.0.1:8080");
});

test("macOS refresh AppleScript opens thread deep links directly", () => {
  const script = fs.readFileSync(MAC_REFRESH_SCRIPT_PATH, "utf8");
  assert.match(script, /open location targetUrl/);
  assert.doesNotMatch(script, /codex:\/\/settings/);
});

test("thread/start falls back once to the new-thread route when a concrete thread id is still unknown", async () => {
  const refreshCalls = [];
  const refresher = new CodexDesktopRefresher({
    enabled: true,
    debounceMs: 0,
    fallbackNewThreadMs: 15,
    refreshExecutor: async ({ targetUrl }) => {
      refreshCalls.push(targetUrl);
    },
  });

  refresher.handleInbound(JSON.stringify({
    method: "thread/start",
    params: {},
  }));

  await wait(40);

  assert.deepEqual(refreshCalls, ["codex://threads/new"]);
  refresher.handleTransportReset();
});

test("turn/completed stops the active watcher after the completion refresh", async () => {
  const refreshCalls = [];
  let stopCount = 0;
  const refresher = new CodexDesktopRefresher({
    enabled: true,
    debounceMs: 0,
    refreshExecutor: async ({ targetUrl }) => {
      refreshCalls.push(targetUrl);
    },
    watchThreadRolloutFactory: () => ({
      stop() {
        stopCount += 1;
      },
    }),
  });

  refresher.handleInbound(JSON.stringify({
    method: "turn/start",
    params: {
      threadId: "thread-complete",
    },
  }));
  await wait(10);
  refreshCalls.length = 0;

  refresher.handleOutbound(JSON.stringify({
    method: "turn/completed",
    params: {
      threadId: "thread-complete",
      turnId: "turn-complete",
    },
  }));
  await wait(10);

  assert.deepEqual(refreshCalls, ["codex://threads/thread-complete"]);
  assert.equal(stopCount, 1);
});

test("completion refresh is retried after a slow in-flight refresh finishes", async () => {
  const refreshCalls = [];
  let releaseSlowRefresh = null;

  const refresher = new CodexDesktopRefresher({
    enabled: true,
    debounceMs: 0,
    refreshExecutor: async ({ targetUrl }) => {
      refreshCalls.push(targetUrl);
      if (refreshCalls.length === 1) {
        await new Promise((resolve) => {
          releaseSlowRefresh = resolve;
        });
      }
    },
    watchThreadRolloutFactory: () => ({ stop() {} }),
  });

  refresher.handleInbound(JSON.stringify({
    method: "turn/start",
    params: {
      threadId: "thread-slow",
    },
  }));
  await wait(10);

  refresher.handleOutbound(JSON.stringify({
    method: "turn/completed",
    params: {
      threadId: "thread-slow",
      turnId: "turn-slow",
    },
  }));
  await wait(10);

  assert.equal(refreshCalls.length, 1);

  releaseSlowRefresh?.();
  await wait(25);

  assert.deepEqual(refreshCalls, [
    "codex://threads/thread-slow",
    "codex://threads/thread-slow",
  ]);
});

test("desktop refresh disables itself after a desktop-unavailable AppleScript failure", async () => {
  let attempts = 0;

  const refresher = new CodexDesktopRefresher({
    enabled: true,
    debounceMs: 0,
    refreshBackend: "applescript",
    refreshExecutor: async () => {
      attempts += 1;
      throw new Error("Unable to find application named Codex");
    },
  });

  refresher.handleInbound(JSON.stringify({
    method: "turn/start",
    params: {
      threadId: "thread-disable-1",
    },
  }));
  await wait(10);

  refresher.handleInbound(JSON.stringify({
    method: "turn/start",
    params: {
      threadId: "thread-disable-2",
    },
  }));
  await wait(10);

  assert.equal(attempts, 1);
  assert.equal(refresher.runtimeRefreshAvailable, false);
});
