// FILE: bridge.test.js
// Purpose: Verifies relay-bound thread-history payloads stay lightweight for mobile clients.
// Layer: Unit test
// Exports: node:test suite
// Depends on: node:test, node:assert/strict, ../src/bridge

const test = require("node:test");
const assert = require("node:assert/strict");
const WebSocket = require("ws");
const {
  getRelayWatchdogAction,
  hasRelayConnectionGoneStale,
  rememberForwardedRequestTiming,
  resolveForwardedRequestTiming,
  resolveForwardedInitializeResponse,
  runRelayWatchdogTick,
  sanitizeThreadHistoryImagesForRelay,
} = require("../src/bridge");

test("sanitizeThreadHistoryImagesForRelay replaces inline history images with lightweight references", () => {
  const rawMessage = JSON.stringify({
    id: "req-thread-read",
    result: {
      thread: {
        id: "thread-images",
        turns: [
          {
            id: "turn-1",
            items: [
              {
                id: "item-user",
                type: "user_message",
                content: [
                  {
                    type: "input_text",
                    text: "Look at this screenshot",
                  },
                  {
                    type: "image",
                    image_url: "data:image/png;base64,AAAA",
                  },
                ],
              },
            ],
          },
        ],
      },
    },
  });

  const sanitized = JSON.parse(
    sanitizeThreadHistoryImagesForRelay(rawMessage, "thread/read")
  );
  const content = sanitized.result.thread.turns[0].items[0].content;

  assert.deepEqual(content[0], {
    type: "input_text",
    text: "Look at this screenshot",
  });
  assert.deepEqual(content[1], {
    type: "image",
    url: "androdex://history-image-elided",
  });
});

test("sanitizeThreadHistoryImagesForRelay leaves unrelated RPC payloads unchanged", () => {
  const rawMessage = JSON.stringify({
    id: "req-other",
    result: {
      ok: true,
    },
  });

  assert.equal(
    sanitizeThreadHistoryImagesForRelay(rawMessage, "turn/start"),
    rawMessage
  );
});

test("getRelayWatchdogAction waits for inbound relay traffic before checking staleness", () => {
  const trackedSocket = {
    readyState: WebSocket.OPEN,
  };

  assert.equal(
    getRelayWatchdogAction({
      isShuttingDown: false,
      activeSocket: trackedSocket,
      trackedSocket,
      hasSeenInboundRelayTraffic: false,
      lastRelayActivityAt: 0,
    }),
    "wait"
  );
});

test("getRelayWatchdogAction keeps healthy quiet relay connections alive between heartbeat intervals", () => {
  const trackedSocket = {
    readyState: WebSocket.OPEN,
  };

  assert.equal(
    getRelayWatchdogAction({
      isShuttingDown: false,
      activeSocket: trackedSocket,
      trackedSocket,
      hasSeenInboundRelayTraffic: true,
      lastRelayActivityAt: 10_000,
      now: 39_000,
    }),
    "ping"
  );
});

test("getRelayWatchdogAction keeps quiet relay connections alive even after the stale window", () => {
  const trackedSocket = {
    readyState: WebSocket.OPEN,
  };

  assert.equal(
    getRelayWatchdogAction({
      isShuttingDown: false,
      activeSocket: trackedSocket,
      trackedSocket,
      hasSeenInboundRelayTraffic: true,
      lastRelayActivityAt: 10_000,
      now: 56_000,
    }),
    "ping"
  );
});

test("runRelayWatchdogTick logs unexpected watchdog failures instead of throwing", () => {
  const errors = [];
  const trackedSocket = {
    get readyState() {
      throw new Error("watchdog boom");
    },
    ping() {},
  };

  const result = runRelayWatchdogTick({
    isShuttingDown: false,
    activeSocket: trackedSocket,
    trackedSocket,
    hasSeenInboundRelayTraffic: true,
    lastRelayActivityAt: 10_000,
    logError(message) {
      errors.push(message);
    },
  });

  assert.equal(result, "error");
  assert.equal(errors.length, 1);
  assert.match(errors[0], /relay watchdog failed/i);
  assert.match(errors[0], /watchdog boom/i);
});

test("hasRelayConnectionGoneStale waits for the full stale timeout window", () => {
  assert.equal(hasRelayConnectionGoneStale(10_000, 54_999), false);
  assert.equal(hasRelayConnectionGoneStale(10_000, 55_000), true);
});

test("resolveForwardedInitializeResponse rewrites already-initialized errors into bridge-managed success", () => {
  const forwardedInitializeRequestIds = new Set(["req-init"]);

  const normalized = resolveForwardedInitializeResponse(
    JSON.stringify({
      id: "req-init",
      error: {
        message: "Already initialized",
      },
    }),
    forwardedInitializeRequestIds
  );

  assert.ok(normalized);
  assert.equal(normalized.handshakeWarm, true);
  assert.deepEqual(JSON.parse(normalized.message), {
    id: "req-init",
    result: {
      bridgeManaged: true,
      workspaceActive: true,
    },
  });
  assert.equal(forwardedInitializeRequestIds.has("req-init"), false);
});

test("resolveForwardedInitializeResponse passes through non-initialize responses unchanged", () => {
  const forwardedInitializeRequestIds = new Set(["req-init"]);

  assert.equal(
    resolveForwardedInitializeResponse(
      JSON.stringify({
        id: "req-other",
        error: {
          message: "Already initialized",
        },
      }),
      forwardedInitializeRequestIds
    ),
    null
  );
  assert.equal(forwardedInitializeRequestIds.has("req-init"), true);
});

test("rememberForwardedRequestTiming tracks thread RPCs and resolveForwardedRequestTiming reports their duration", () => {
  const forwardedRequestTimingsById = new Map();

  rememberForwardedRequestTiming(
    JSON.stringify({
      id: "req-thread-read",
      method: "thread/read",
      params: {
        threadId: "thread-123",
      },
    }),
    new Set(["thread/list", "thread/read", "thread/resume"]),
    forwardedRequestTimingsById,
    1_000
  );

  const resolved = resolveForwardedRequestTiming(
    JSON.stringify({
      id: "req-thread-read",
      result: {
        ok: true,
      },
    }),
    forwardedRequestTimingsById,
    4_210
  );

  assert.deepEqual(resolved, {
    method: "thread/read",
    threadId: "thread-123",
    durationMs: 3_210,
    errorMessage: "",
  });
  assert.equal(forwardedRequestTimingsById.size, 0);
});

test("rememberForwardedRequestTiming ignores unrelated methods", () => {
  const forwardedRequestTimingsById = new Map();

  rememberForwardedRequestTiming(
    JSON.stringify({
      id: "req-turn-start",
      method: "turn/start",
      params: {
        threadId: "thread-123",
      },
    }),
    new Set(["thread/list", "thread/read", "thread/resume"]),
    forwardedRequestTimingsById,
    1_000
  );

  assert.equal(
    resolveForwardedRequestTiming(
      JSON.stringify({
        id: "req-turn-start",
        result: {
          ok: true,
        },
      }),
      forwardedRequestTimingsById,
      2_000
    ),
    null
  );
});
