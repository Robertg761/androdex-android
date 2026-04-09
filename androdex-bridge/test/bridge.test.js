// FILE: bridge.test.js
// Purpose: Verifies relay-bound thread-history payloads stay lightweight for mobile clients.
// Layer: Unit test
// Exports: node:test suite
// Depends on: node:test, node:assert/strict, ../src/bridge

const test = require("node:test");
const assert = require("node:assert/strict");
const WebSocket = require("ws");
const {
  buildBridgeManagedRuntimeTargetResult,
  buildUnavailableHostAccountStatus,
  createBridgeManagedInitializeSuccessResponse,
  getRelayWatchdogAction,
  hasRelayConnectionGoneStale,
  isBridgeManagedReadOnlyRuntimeConfigMethod,
  logForwardedRequestToCodex,
  rememberForwardedRequestTiming,
  resolveForwardedRequestReplay,
  resolveForwardedRequestTiming,
  resolveForwardedInitializeResponse,
  runRelayWatchdogTick,
  sanitizeThreadHistoryImagesForRelay,
  shouldAllowPreResumeRelayResponse,
  shouldServeBridgeManagedReadOnlySnapshot,
  shouldQueueMessageUntilCodexWarm,
} = require("../src/bridge");

test("buildBridgeManagedRuntimeTargetResult returns host runtime selection metadata", () => {
  const result = buildBridgeManagedRuntimeTargetResult({
    getStatus() {
      return {
        currentCwd: "/tmp/workspace",
        runtimeTarget: "t3-server",
        workspaceActive: true,
        runtimeMetadata: {
          runtimeAttachState: "ready",
          runtimeEndpointHost: "127.0.0.1",
        },
      };
    },
  });

  assert.deepEqual(result, {
    workspaceActive: true,
    currentCwd: "/tmp/workspace",
    runtimeTarget: "t3-server",
    runtimeTargetDisplayName: "T3 Server",
    backendProvider: null,
    backendProviderDisplayName: null,
    runtimeAttachState: "ready",
    runtimeEndpointHost: "127.0.0.1",
  });
});

test("createBridgeManagedInitializeSuccessResponse can include runtime-target metadata", () => {
  assert.deepEqual(
    JSON.parse(
      createBridgeManagedInitializeSuccessResponse("req-init", {
        runtimeTarget: "codex-native",
        runtimeTargetDisplayName: "Codex Native",
        backendProvider: "codex",
        backendProviderDisplayName: "Codex",
      })
    ),
    {
      id: "req-init",
      result: {
        bridgeManaged: true,
        workspaceActive: true,
        runtimeTarget: "codex-native",
        runtimeTargetDisplayName: "Codex Native",
        backendProvider: "codex",
        backendProviderDisplayName: "Codex",
      },
    }
  );
});

test("buildUnavailableHostAccountStatus returns a neutral bridge-managed snapshot", () => {
  assert.deepEqual(buildUnavailableHostAccountStatus(), {
    status: "unavailable",
    authMethod: null,
    email: null,
    planType: null,
    loginInFlight: false,
    needsReauth: false,
    tokenReady: false,
    expiresAt: null,
    bridgeVersion: null,
    bridgeLatestVersion: null,
  });
});

test("shouldServeBridgeManagedReadOnlySnapshot waits for a ready workspace", () => {
  assert.equal(
    shouldServeBridgeManagedReadOnlySnapshot({
      runtimeTarget: "t3-server",
      workspaceActive: false,
    }),
    false
  );
  assert.equal(
    shouldServeBridgeManagedReadOnlySnapshot({
      runtimeTarget: "t3-server",
      workspaceActive: true,
    }),
    true
  );
  assert.equal(
    shouldServeBridgeManagedReadOnlySnapshot({
      runtimeTarget: "codex-native",
      workspaceActive: true,
    }),
    false
  );
});

test("isBridgeManagedReadOnlyRuntimeConfigMethod accepts both collaboration mode spellings", () => {
  assert.equal(isBridgeManagedReadOnlyRuntimeConfigMethod("model/list"), true);
  assert.equal(isBridgeManagedReadOnlyRuntimeConfigMethod("collaborationMode/list"), true);
  assert.equal(isBridgeManagedReadOnlyRuntimeConfigMethod("collaborationmode/list"), true);
  assert.equal(isBridgeManagedReadOnlyRuntimeConfigMethod("turn/start"), false);
});

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

test("resolveForwardedInitializeResponse rewrites successful initialize results into bridge-managed success", () => {
  const forwardedInitializeRequestIds = new Set(["req-init"]);

  const normalized = resolveForwardedInitializeResponse(
    JSON.stringify({
      id: "req-init",
      result: {
        protocolVersion: "2026-03-26",
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

test("shouldAllowPreResumeRelayResponse allows plain RPC responses through before resume", () => {
  assert.equal(
    shouldAllowPreResumeRelayResponse(
      JSON.stringify({
        id: "req-collaboration-modes",
        result: {
          items: [],
        },
      })
    ),
    true
  );
});

test("shouldAllowPreResumeRelayResponse keeps notifications and server requests replay-safe", () => {
  assert.equal(
    shouldAllowPreResumeRelayResponse(
      JSON.stringify({
        method: "thread/updated",
        params: {
          threadId: "thread-123",
        },
      })
    ),
    false
  );
  assert.equal(
    shouldAllowPreResumeRelayResponse(
      JSON.stringify({
        id: "host-request",
        method: "pairing/recoveryProvisioned",
        params: {
          ok: true,
        },
      })
    ),
    false
  );
});

test("shouldQueueMessageUntilCodexWarm only queues Codex-native requests", () => {
  assert.equal(
    shouldQueueMessageUntilCodexWarm({
      rawMessage: JSON.stringify({
        id: "req-thread-list",
        method: "thread/list",
        params: {},
      }),
      codexHandshakeState: "cold",
      lastInitializeParams: {},
      runtimeTarget: "t3-server",
    }),
    false
  );
  assert.equal(
    shouldQueueMessageUntilCodexWarm({
      rawMessage: JSON.stringify({
        id: "req-thread-list",
        method: "thread/list",
        params: {},
      }),
      codexHandshakeState: "cold",
      lastInitializeParams: {},
      runtimeTarget: "codex-native",
    }),
    true
  );
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

test("rememberForwardedRequestTiming tracks turn-start latency when requested", () => {
  const forwardedRequestTimingsById = new Map();

  rememberForwardedRequestTiming(
    JSON.stringify({
      id: "req-turn-start",
      method: "turn/start",
      params: {
        threadId: "thread-123",
      },
    }),
    new Set(["thread/list", "thread/read", "thread/resume", "turn/start"]),
    forwardedRequestTimingsById,
    1_000
  );

  assert.deepEqual(
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
    {
      method: "turn/start",
      threadId: "thread-123",
      durationMs: 1_000,
      errorMessage: "",
    }
  );
});

test("logForwardedRequestToCodex records the turn-start context sent to the host runtime", () => {
  const lines = [];
  const originalLog = console.log;
  console.log = (message) => {
    lines.push(String(message));
  };

  try {
    logForwardedRequestToCodex(
      JSON.stringify({
        id: "req-turn-start",
        method: "turn/start",
        params: {
          threadId: "thread-123",
          input: [
            { type: "text", text: "hello" },
          ],
          model: "gpt-5.4",
          collaborationMode: {
            mode: "git",
          },
        },
      }),
      new Set(["turn/start"])
    );
  } finally {
    console.log = originalLog;
  }

  assert.equal(lines.length, 1);
  assert.match(lines[0], /rpc->codex turn\/start/);
  assert.match(lines[0], /thread=thread-123/);
  assert.match(lines[0], /inputItems=1/);
  assert.match(lines[0], /model=gpt-5\.4/);
  assert.match(lines[0], /collaborationMode=git/);
});

test("resolveForwardedRequestReplay returns the original request when Codex says it is not initialized", () => {
  const forwardedRequestTimingsById = new Map();
  const rawRequest = JSON.stringify({
    id: "req-thread-read",
    method: "thread/read",
    params: {
      threadId: "thread-123",
    },
  });

  rememberForwardedRequestTiming(
    rawRequest,
    new Set(["thread/list", "thread/read", "thread/resume"]),
    forwardedRequestTimingsById,
    1_000
  );

  const replay = resolveForwardedRequestReplay(
    JSON.stringify({
      id: "req-thread-read",
      error: {
        message: "Not initialized",
      },
    }),
    forwardedRequestTimingsById
  );

  assert.deepEqual(replay, {
    requestId: "req-thread-read",
    method: "thread/read",
    threadId: "thread-123",
    rawMessage: rawRequest,
  });
});

test("shouldQueueMessageUntilCodexWarm defers thread RPCs after a transport reset when initialize params are cached", () => {
  assert.equal(shouldQueueMessageUntilCodexWarm({
    rawMessage: JSON.stringify({
      id: "req-thread-read",
      method: "thread/read",
      params: {
        threadId: "thread-123",
      },
    }),
    codexHandshakeState: "cold",
    lastInitializeParams: {
      clientInfo: {
        name: "Androdex",
      },
    },
  }), true);
});

test("shouldQueueMessageUntilCodexWarm does not defer when the bridge has no cached initialize params", () => {
  assert.equal(shouldQueueMessageUntilCodexWarm({
    rawMessage: JSON.stringify({
      id: "req-thread-read",
      method: "thread/read",
      params: {
        threadId: "thread-123",
      },
    }),
    codexHandshakeState: "cold",
    lastInitializeParams: null,
  }), false);
});

test("shouldQueueMessageUntilCodexWarm leaves explicit initialize traffic alone", () => {
  assert.equal(shouldQueueMessageUntilCodexWarm({
    rawMessage: JSON.stringify({
      id: "req-init",
      method: "initialize",
      params: {
        clientInfo: {
          name: "Androdex",
        },
      },
    }),
    codexHandshakeState: "cold",
    lastInitializeParams: {
      clientInfo: {
        name: "Androdex",
      },
    },
  }), false);
});
