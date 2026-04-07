const test = require("node:test");
const assert = require("node:assert/strict");

const {
  buildRuntimeTargetMethodRejectionMessage,
  isCodexNativeRuntimeTarget,
  isReadOnlyRuntimeTarget,
  isRuntimeTargetMethodAllowed,
} = require("../src/runtime/method-policy");
const { createRuntimeAdapter } = require("../src/runtime/adapter");

class FakeWebSocket {
  static OPEN = 1;
  static CONNECTING = 0;

  constructor(url) {
    this.url = url;
    this.readyState = FakeWebSocket.CONNECTING;
    this.handlers = new Map();
    this.sentMessages = [];
    queueMicrotask(() => {
      this.readyState = FakeWebSocket.OPEN;
      this.handlers.get("open")?.();
    });
  }

  on(event, handler) {
    this.handlers.set(event, handler);
  }

  send(message) {
    this.sentMessages.push(message);
    const parsed = JSON.parse(message);
    if (parsed.method === "server.getConfig") {
      queueMicrotask(() => {
        this.handlers.get("message")?.(Buffer.from(JSON.stringify({
          id: parsed.id,
          result: {
            protocolVersion: "2026-04-01",
            authMode: "bootstrap-token",
            baseDir: "/tmp/t3-state",
            rpcMethods: [
              "server.getConfig",
              "orchestration.getSnapshot",
              "orchestration.replayEvents",
            ],
            subscriptions: [
              "subscribeOrchestrationDomainEvents",
            ],
          },
        })));
      });
    }
  }

  close() {
    this.readyState = 3;
  }
}

test("runtime method policy recognizes codex-native and T3 read-only targets", () => {
  assert.equal(isCodexNativeRuntimeTarget("codex-native"), true);
  assert.equal(isCodexNativeRuntimeTarget("t3-server"), false);
  assert.equal(isReadOnlyRuntimeTarget("t3-server"), true);
  assert.equal(isReadOnlyRuntimeTarget("codex-native"), false);
});

test("T3 read-only policy allows snapshot/bootstrap-safe read methods", () => {
  assert.equal(
    isRuntimeTargetMethodAllowed({ targetKind: "t3-server", method: "thread/list" }),
    true
  );
  assert.equal(
    isRuntimeTargetMethodAllowed({ targetKind: "t3-server", method: "thread/read" }),
    true
  );
  assert.equal(
    isRuntimeTargetMethodAllowed({ targetKind: "t3-server", method: "model/list" }),
    true
  );
});

test("T3 read-only policy rejects mutating methods with a clear message", () => {
  assert.equal(
    isRuntimeTargetMethodAllowed({ targetKind: "t3-server", method: "turn/start" }),
    false
  );
  assert.match(
    buildRuntimeTargetMethodRejectionMessage({
      targetKind: "t3-server",
      method: "turn/start",
    }),
    /currently read-only/i
  );
});

test("T3 runtime adapter forwards allowed read-only methods to the configured endpoint", async () => {
  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: {
      ANDRODEX_T3_AUTH_MODE: "bootstrap-token",
      ANDRODEX_T3_BASE_DIR: "/tmp/t3-state",
      ANDRODEX_T3_PROTOCOL_VERSION: "2026-04-01",
    },
    WebSocketImpl: FakeWebSocket,
  });

  await adapter.whenReady();
  assert.equal(adapter.kind, "t3-server");
  assert.equal(adapter.backendProviderKind, null);
  assert.equal(adapter.readOnly, true);
  assert.equal(
    adapter.send(JSON.stringify({
      id: "req-thread-read",
      method: "thread/read",
      params: { threadId: "thread-123" },
    })),
    true
  );
});

test("T3 runtime adapter blocks mutating methods before they cross the transport", async () => {
  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: {
      ANDRODEX_T3_AUTH_MODE: "bootstrap-token",
      ANDRODEX_T3_BASE_DIR: "/tmp/t3-state",
      ANDRODEX_T3_PROTOCOL_VERSION: "2026-04-01",
    },
    WebSocketImpl: FakeWebSocket,
  });

  await adapter.whenReady();
  assert.throws(
    () => adapter.send(JSON.stringify({
      id: "req-turn-start",
      method: "turn/start",
      params: { threadId: "thread-123" },
    })),
    /currently read-only/i
  );
});
