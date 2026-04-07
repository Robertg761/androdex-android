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
    if (parsed.tag === "server.getConfig") {
      queueMicrotask(() => {
        this.handlers.get("message")?.(Buffer.from(JSON.stringify({
          _tag: "Exit",
          requestId: parsed.id,
          exit: {
            _tag: "Success",
            value: {
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
          },
        })));
      });
    }
    if (parsed.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        this.handlers.get("message")?.(Buffer.from(JSON.stringify({
          _tag: "Exit",
          requestId: parsed.id,
          exit: {
            _tag: "Success",
            value: {
              snapshotSequence: 7,
              updatedAt: "2026-04-07T12:00:00.000Z",
              projects: [
                {
                  id: "project-1",
                  title: "Project One",
                  workspaceRoot: "/tmp",
                  createdAt: "2026-04-07T11:00:00.000Z",
                  updatedAt: "2026-04-07T11:30:00.000Z",
                  deletedAt: null,
                },
              ],
              threads: [
                {
                  id: "thread-123",
                  projectId: "project-1",
                  title: "Snapshot thread",
                  modelSelection: {
                    provider: "codex",
                    model: "gpt-5.4",
                  },
                  runtimeMode: "full-access",
                  interactionMode: "default",
                  branch: "main",
                  worktreePath: "/tmp",
                  latestTurn: {
                    turnId: "turn-1",
                    state: "completed",
                    requestedAt: "2026-04-07T11:10:00.000Z",
                    startedAt: "2026-04-07T11:10:05.000Z",
                    completedAt: "2026-04-07T11:11:00.000Z",
                    assistantMessageId: "msg-2",
                  },
                  createdAt: "2026-04-07T11:00:00.000Z",
                  updatedAt: "2026-04-07T11:11:00.000Z",
                  archivedAt: null,
                  deletedAt: null,
                  messages: [
                    {
                      id: "msg-1",
                      role: "user",
                      text: "Hello",
                      turnId: "turn-1",
                      streaming: false,
                      createdAt: "2026-04-07T11:10:00.000Z",
                      updatedAt: "2026-04-07T11:10:00.000Z",
                    },
                    {
                      id: "msg-2",
                      role: "assistant",
                      text: "World",
                      turnId: "turn-1",
                      streaming: false,
                      createdAt: "2026-04-07T11:10:05.000Z",
                      updatedAt: "2026-04-07T11:11:00.000Z",
                    },
                  ],
                  proposedPlans: [],
                  activities: [],
                  checkpoints: [],
                  session: null,
                },
              ],
            },
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

test("T3 runtime adapter synthesizes thread/list responses from the snapshot read model", async () => {
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

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });
  await adapter.whenReady();
  assert.equal(adapter.send(JSON.stringify({
    id: "req-thread-list",
    method: "thread/list",
    params: {
      limit: 10,
      cursor: null,
    },
  })), true);

  await new Promise((resolve) => setTimeout(resolve, 0));

  assert.equal(responses.length, 1);
  assert.equal(responses[0].id, "req-thread-list");
  assert.equal(responses[0].result.data[0].id, "thread-123");
});

test("T3 runtime adapter synthesizes thread/read responses from the snapshot read model", async () => {
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

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });
  await adapter.whenReady();
  assert.equal(adapter.send(JSON.stringify({
    id: "req-thread-read",
    method: "thread/read",
    params: {
      threadId: "thread-123",
      includeTurns: true,
    },
  })), true);

  await new Promise((resolve) => setTimeout(resolve, 0));

  assert.equal(responses.length, 1);
  assert.equal(responses[0].id, "req-thread-read");
  assert.equal(responses[0].result.thread.id, "thread-123");
  assert.equal(responses[0].result.thread.turns[0].items[1].type, "assistant_message");
});

test("T3 runtime adapter attaches bridge-managed live updates for supported thread/resume requests", async () => {
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

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });
  await adapter.whenReady();
  assert.equal(adapter.send(JSON.stringify({
    id: "req-thread-resume",
    method: "thread/resume",
    params: {
      threadId: "thread-123",
    },
  })), true);

  await new Promise((resolve) => setTimeout(resolve, 0));

  assert.equal(responses.length, 1);
  assert.equal(responses[0].id, "req-thread-resume");
  assert.equal(responses[0].result.ok, true);
  assert.equal(responses[0].result.resumed, true);
  assert.equal(responses[0].result.liveUpdatesAttached, true);
  assert.match(responses[0].result.reason, /attached bridge-managed live updates/i);
});
