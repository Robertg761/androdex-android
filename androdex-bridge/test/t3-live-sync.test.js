const test = require("node:test");
const assert = require("node:assert/strict");

const runtimeAdapterModule = require("../src/runtime/adapter");

const trackedAdapters = new Set();

test.after(() => {
  for (const adapter of trackedAdapters) {
    adapter.shutdown();
  }
  trackedAdapters.clear();
});

const createRuntimeAdapter = (...args) => {
  const adapter = runtimeAdapterModule.createRuntimeAdapter(...args);
  trackedAdapters.add(adapter);
  return adapter;
};

function createTestEnv() {
  return {
    ANDRODEX_T3_AUTH_MODE: "bootstrap-token",
    ANDRODEX_T3_BASE_DIR: "/tmp/t3-state",
    ANDRODEX_T3_PROTOCOL_VERSION: "2026-04-01",
  };
}

function createBaseSnapshot() {
  return {
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
        title: "Original title",
        modelSelection: {
          provider: "codex",
          model: "gpt-5.4",
        },
        runtimeMode: "full-access",
        interactionMode: "default",
        branch: "main",
        worktreePath: "/tmp",
        latestTurn: null,
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
        ],
        proposedPlans: [],
        activities: [],
        checkpoints: [],
        session: null,
      },
    ],
  };
}

function createScriptedWebSocket(resolver) {
  return class ScriptedWebSocket {
    static OPEN = 1;
    static CONNECTING = 0;
    static CLOSED = 3;
    static latestInstance = null;
    static instances = [];

    constructor(url) {
      this.connectionIndex = ScriptedWebSocket.instances.length;
      ScriptedWebSocket.instances.push(this);
      ScriptedWebSocket.latestInstance = this;
      this.url = url;
      this.readyState = ScriptedWebSocket.CONNECTING;
      this.handlers = new Map();
      this.sentMessages = [];
      queueMicrotask(() => {
        this.readyState = ScriptedWebSocket.OPEN;
        this.handlers.get("open")?.();
      });
    }

    on(event, handler) {
      this.handlers.set(event, handler);
    }

    send(message) {
      this.sentMessages.push(message);
      resolver({
        message: JSON.parse(message),
        socket: this,
      });
    }

    close(code = 1000, reason = "") {
      this.readyState = ScriptedWebSocket.CLOSED;
      this.handlers.get("close")?.(code, Buffer.from(reason));
    }

    serverMessage(frame) {
      this.handlers.get("message")?.(Buffer.from(JSON.stringify(frame)));
    }
  };
}

function succeedRpc(socket, requestId, value) {
  socket.serverMessage({
    _tag: "Exit",
    requestId,
    exit: {
      _tag: "Success",
      value,
    },
  });
}

function failRpc(socket, requestId, message) {
  socket.serverMessage({
    _tag: "Exit",
    requestId,
    exit: {
      _tag: "Failure",
      cause: {
        message,
      },
    },
  });
}

async function nextTick() {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

async function waitFor(predicate, {
  attempts = 100,
  delayMs = 5,
} = {}) {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    if (predicate()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, delayMs));
  }
  assert.fail("Timed out waiting for the expected condition.");
}

test("T3 adapter replays buffered snapshot gaps before it exposes the ready read model", async () => {
  const replayRequests = [];
  const persistedReplayWrites = [];
  const metadataUpdates = [];

  const liveGapEvent = {
    sequence: 9,
    eventId: "event-9",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:09.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.meta-updated",
    payload: {
      threadId: "thread-123",
      title: "Recovered title",
      updatedAt: "2026-04-07T12:00:09.000Z",
    },
  };
  const replayedEvents = [
    {
      sequence: 8,
      eventId: "event-8",
      aggregateKind: "thread",
      aggregateId: "thread-123",
      occurredAt: "2026-04-07T12:00:08.000Z",
      commandId: null,
      causationEventId: null,
      correlationId: null,
      metadata: {},
      type: "thread.message-sent",
      payload: {
        threadId: "thread-123",
        messageId: "msg-2",
        role: "assistant",
        text: "Recovered answer",
        attachments: [],
        turnId: "turn-1",
        streaming: false,
        createdAt: "2026-04-07T12:00:08.000Z",
        updatedAt: "2026-04-07T12:00:08.000Z",
      },
    },
    liveGapEvent,
  ];

  const WebSocketImpl = createScriptedWebSocket(({ message, socket }) => {
    if (message.tag === "server.getConfig") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, {
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
        });
      });
      return;
    }

    if (message.tag === "subscribeOrchestrationDomainEvents") {
      queueMicrotask(() => {
        socket.serverMessage({
          _tag: "Chunk",
          requestId: message.id,
          values: [liveGapEvent],
        });
      });
      return;
    }

    if (message.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, createBaseSnapshot());
      });
      return;
    }

    if (message.tag === "orchestration.replayEvents") {
      replayRequests.push(message.payload.fromSequenceExclusive);
      queueMicrotask(() => {
        succeedRpc(socket, message.id, replayedEvents);
      });
    }
  });

  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: createTestEnv(),
    WebSocketImpl,
    loadReplayCursor() {
      return 5;
    },
    persistReplayCursor(scope) {
      persistedReplayWrites.push(scope);
    },
  });

  adapter.onMetadata((metadata) => {
    metadataUpdates.push(metadata);
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });

  await adapter.whenReady();
  assert.deepEqual(replayRequests, [7]);

  adapter.send(JSON.stringify({
    id: "req-thread-read",
    method: "thread/read",
    params: {
      threadId: "thread-123",
    },
  }));
  await nextTick();

  assert.equal(responses.length, 1);
  assert.equal(responses[0].result.thread.title, "Recovered title");
  assert.equal(responses[0].result.thread.turns[0].items[1].content[0].text, "Recovered answer");
  assert.ok(persistedReplayWrites.some((entry) => entry.sequence === 9));
  assert.ok(metadataUpdates.some((metadata) => metadata.runtimeSubscriptionState === "replaying"));
  assert.equal(metadataUpdates.at(-1)?.runtimeReplaySequence, 9);
});

test("T3 adapter emits structured replay logs with safe state-root identity", async () => {
  const logEntries = [];
  const replayedEvents = [
    {
      sequence: 8,
      eventId: "event-8",
      aggregateKind: "thread",
      aggregateId: "thread-123",
      occurredAt: "2026-04-07T12:00:08.000Z",
      type: "thread.meta-updated",
      payload: {
        threadId: "thread-123",
        title: "Recovered title",
        updatedAt: "2026-04-07T12:00:08.000Z",
      },
    },
  ];

  const WebSocketImpl = createScriptedWebSocket(({ message, socket }) => {
    if (message.tag === "server.getConfig") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, {
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
        });
      });
      return;
    }

    if (message.tag === "subscribeOrchestrationDomainEvents") {
      return;
    }

    if (message.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, createBaseSnapshot());
      });
      return;
    }

    if (message.tag === "orchestration.replayEvents") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, replayedEvents);
      });
    }
  });

  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: createTestEnv(),
    WebSocketImpl,
    loadReplayCursor() {
      return 9;
    },
    logEvent(entry) {
      logEntries.push(entry);
    },
  });

  await adapter.whenReady();

  const replayRequestLog = logEntries.find((entry) => entry.event === "replay_requested");
  assert.ok(replayRequestLog);
  assert.equal(replayRequestLog.reasonCode, "bootstrap_resume");
  assert.equal(replayRequestLog.fromSequenceExclusive, 7);
  assert.match(replayRequestLog.stateRootId, /^[0-9a-f]{12}$/);
  assert.equal(replayRequestLog.stateRootId, "22fb27b82037");
  assert.equal(replayRequestLog.protocolVersion, "2026-04-01");
  assert.equal(replayRequestLog.authMode, "bootstrap-token");

  const replayAppliedLog = logEntries.find((entry) => entry.event === "replay_applied");
  assert.ok(replayAppliedLog);
  assert.equal(replayAppliedLog.toSequenceInclusive, 8);
  assert.equal(replayAppliedLog.appliedCount, 1);
  assert.equal(replayAppliedLog.duplicateCount, 0);

  assert.ok(logEntries.every((entry) => !JSON.stringify(entry).includes("/tmp/t3-state")));
});

test("T3 adapter emits structured gating logs for rejected read-only actions", async () => {
  const logEntries = [];

  const WebSocketImpl = createScriptedWebSocket(({ message, socket }) => {
    if (message.tag === "server.getConfig") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, {
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
        });
      });
      return;
    }

    if (message.tag === "subscribeOrchestrationDomainEvents") {
      return;
    }

    if (message.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, createBaseSnapshot());
      });
    }
  });

  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: createTestEnv(),
    WebSocketImpl,
    logEvent(entry) {
      logEntries.push(entry);
    },
  });

  await adapter.whenReady();

  assert.throws(() => {
    adapter.send(JSON.stringify({
      id: "req-thread-fork",
      method: "thread/fork",
      params: {
        threadId: "thread-123",
      },
    }));
  }, /does not support "thread\/fork" yet/i);

  assert.deepEqual(
    logEntries.find((entry) =>
      entry.event === "action_gated" && entry.reasonCode === "runtime_method_rejected"
    ),
    {
      component: "t3-runtime-adapter",
      event: "action_gated",
      runtimeTarget: "t3-server",
      attachState: "ready",
      subscriptionState: "live",
      protocolVersion: "2026-04-01",
      authMode: "bootstrap-token",
      endpointHost: "127.0.0.1",
      stateRootId: "22fb27b82037",
      snapshotSequence: 7,
      replaySequence: 7,
      duplicateSuppressionCount: 0,
      reasonCode: "runtime_method_rejected",
      method: "thread/fork",
      threadId: "thread-123",
    },
  );
});

test("T3 adapter keeps a live cache and suppresses duplicate events without re-fetching the snapshot", async () => {
  let snapshotRequestCount = 0;
  let subscriptionRequestId = null;
  const metadataUpdates = [];

  const liveAssistantEvent = {
    sequence: 8,
    eventId: "event-8",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:08.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.message-sent",
    payload: {
      threadId: "thread-123",
      messageId: "msg-2",
      role: "assistant",
      text: "Live answer",
      attachments: [],
      turnId: "turn-1",
      streaming: false,
      createdAt: "2026-04-07T12:00:08.000Z",
      updatedAt: "2026-04-07T12:00:08.000Z",
    },
  };

  const WebSocketImpl = createScriptedWebSocket(({ message, socket }) => {
    if (message.tag === "server.getConfig") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, {
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
        });
      });
      return;
    }

    if (message.tag === "subscribeOrchestrationDomainEvents") {
      subscriptionRequestId = message.id;
      return;
    }

    if (message.tag === "orchestration.getSnapshot") {
      snapshotRequestCount += 1;
      queueMicrotask(() => {
        succeedRpc(socket, message.id, createBaseSnapshot());
      });
    }
  });

  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: createTestEnv(),
    WebSocketImpl,
  });

  adapter.onMetadata((metadata) => {
    metadataUpdates.push(metadata);
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });

  await adapter.whenReady();
  assert.equal(snapshotRequestCount, 1);
  assert.ok(subscriptionRequestId);

  const liveSocket = WebSocketImpl.latestInstance;
  assert.ok(liveSocket);
  liveSocket.serverMessage({
    _tag: "Chunk",
    requestId: subscriptionRequestId,
    values: [liveAssistantEvent],
  });
  liveSocket.serverMessage({
    _tag: "Chunk",
    requestId: subscriptionRequestId,
    values: [liveAssistantEvent],
  });
  await nextTick();

  adapter.send(JSON.stringify({
    id: "req-thread-read",
    method: "thread/read",
    params: {
      threadId: "thread-123",
    },
  }));
  await nextTick();

  assert.equal(snapshotRequestCount, 1);
  assert.equal(responses.length, 1);
  assert.equal(responses[0].result.thread.turns[0].items[1].content[0].text, "Live answer");
  assert.ok((metadataUpdates.at(-1)?.runtimeDuplicateSuppressionCount || 0) >= 1);
});

test("T3 adapter only emits bridge-managed live notifications after a supported thread is resumed", async () => {
  let subscriptionRequestId = null;
  const runningSessionEvent = {
    sequence: 8,
    eventId: "event-8",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:08.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.session-set",
    payload: {
      threadId: "thread-123",
      session: {
        status: "running",
        activeTurnId: "turn-2",
        updatedAt: "2026-04-07T12:00:08.000Z",
      },
    },
  };
  const assistantMessageEvent = {
    sequence: 9,
    eventId: "event-9",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:09.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.message-sent",
    payload: {
      threadId: "thread-123",
      messageId: "msg-2",
      role: "assistant",
      text: "Live answer after resume",
      attachments: [],
      turnId: "turn-2",
      streaming: false,
      createdAt: "2026-04-07T12:00:09.000Z",
      updatedAt: "2026-04-07T12:00:09.000Z",
    },
  };
  const completedSessionEvent = {
    sequence: 10,
    eventId: "event-10",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:10.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.session-set",
    payload: {
      threadId: "thread-123",
      session: {
        status: "ready",
        activeTurnId: null,
        updatedAt: "2026-04-07T12:00:10.000Z",
      },
    },
  };

  const WebSocketImpl = createScriptedWebSocket(({ message, socket }) => {
    if (message.tag === "server.getConfig") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, {
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
        });
      });
      return;
    }

    if (message.tag === "subscribeOrchestrationDomainEvents") {
      subscriptionRequestId = message.id;
      return;
    }

    if (message.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, createBaseSnapshot());
      });
    }
  });

  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: createTestEnv(),
    WebSocketImpl,
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });

  await adapter.whenReady();
  const liveSocket = WebSocketImpl.latestInstance;
  assert.ok(liveSocket);
  assert.ok(subscriptionRequestId);

  liveSocket.serverMessage({
    _tag: "Chunk",
    requestId: subscriptionRequestId,
    values: [runningSessionEvent],
  });
  await nextTick();

  assert.deepEqual(responses, []);

  adapter.send(JSON.stringify({
    id: "req-thread-resume",
    method: "thread/resume",
    params: {
      threadId: "thread-123",
    },
  }));
  await nextTick();

  liveSocket.serverMessage({
    _tag: "Chunk",
    requestId: subscriptionRequestId,
    values: [assistantMessageEvent, completedSessionEvent],
  });
  await nextTick();

  assert.equal(responses.length, 3);
  assert.equal(responses[0].id, "req-thread-resume");
  assert.equal(responses[0].result.liveUpdatesAttached, true);
  assert.equal(responses[1].method, "codex/event/agent_message");
  assert.equal(responses[1].params.threadId, "thread-123");
  assert.equal(responses[1].params.turnId, "turn-2");
  assert.equal(responses[1].params.messageId, "msg-2");
  assert.equal(responses[1].params.message, "Live answer after resume");
  assert.equal(responses[2].method, "turn/completed");
  assert.equal(responses[2].params.threadId, "thread-123");
  assert.equal(responses[2].params.turnId, "turn-2");
  assert.equal(responses[2].params.status, "completed");
});

test("T3 adapter suppresses duplicate resumed-thread assistant and title notifications for repeated live delivery", async () => {
  let subscriptionRequestId = null;
  const titleEvent = {
    sequence: 8,
    eventId: "event-8",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:08.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.meta-updated",
    payload: {
      threadId: "thread-123",
      title: "Recovered live title",
      updatedAt: "2026-04-07T12:00:08.000Z",
    },
  };
  const assistantEvent = {
    sequence: 9,
    eventId: "event-9",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:09.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.message-sent",
    payload: {
      threadId: "thread-123",
      messageId: "msg-2",
      role: "assistant",
      text: "Recovered live answer",
      attachments: [],
      turnId: "turn-1",
      streaming: false,
      createdAt: "2026-04-07T12:00:09.000Z",
      updatedAt: "2026-04-07T12:00:09.000Z",
    },
  };

  const WebSocketImpl = createScriptedWebSocket(({ message, socket }) => {
    if (message.tag === "server.getConfig") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, {
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
        });
      });
      return;
    }

    if (message.tag === "subscribeOrchestrationDomainEvents") {
      subscriptionRequestId = message.id;
      return;
    }

    if (message.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, createBaseSnapshot());
      });
    }
  });

  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: createTestEnv(),
    WebSocketImpl,
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });

  await adapter.whenReady();
  adapter.send(JSON.stringify({
    id: "req-thread-resume",
    method: "thread/resume",
    params: {
      threadId: "thread-123",
    },
  }));
  await nextTick();

  const liveSocket = WebSocketImpl.latestInstance;
  assert.ok(liveSocket);
  assert.ok(subscriptionRequestId);

  liveSocket.serverMessage({
    _tag: "Chunk",
    requestId: subscriptionRequestId,
    values: [titleEvent, assistantEvent],
  });
  liveSocket.serverMessage({
    _tag: "Chunk",
    requestId: subscriptionRequestId,
    values: [titleEvent, assistantEvent],
  });
  await nextTick();

  assert.equal(
    responses.filter((entry) =>
      entry.method === "thread/name/updated" && entry.params?.title === "Recovered live title"
    ).length,
    1,
  );
  assert.equal(
    responses.filter((entry) =>
      entry.method === "codex/event/agent_message" && entry.params?.messageId === "msg-2"
    ).length,
    1,
  );
});

test("T3 adapter emits a fresh turn/started when T3 swaps the active running turn id", async () => {
  let subscriptionRequestId = null;
  const firstRunningSessionEvent = {
    sequence: 8,
    eventId: "event-8",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:08.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.session-set",
    payload: {
      threadId: "thread-123",
      session: {
        status: "running",
        activeTurnId: "turn-1",
        updatedAt: "2026-04-07T12:00:08.000Z",
      },
    },
  };
  const secondRunningSessionEvent = {
    sequence: 9,
    eventId: "event-9",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:09.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.session-set",
    payload: {
      threadId: "thread-123",
      session: {
        status: "running",
        activeTurnId: "turn-2",
        updatedAt: "2026-04-07T12:00:09.000Z",
      },
    },
  };

  const WebSocketImpl = createScriptedWebSocket(({ message, socket }) => {
    if (message.tag === "server.getConfig") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, {
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
        });
      });
      return;
    }

    if (message.tag === "subscribeOrchestrationDomainEvents") {
      subscriptionRequestId = message.id;
      return;
    }

    if (message.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, createBaseSnapshot());
      });
    }
  });

  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: createTestEnv(),
    WebSocketImpl,
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });

  await adapter.whenReady();
  adapter.send(JSON.stringify({
    id: "req-thread-resume",
    method: "thread/resume",
    params: {
      threadId: "thread-123",
    },
  }));
  await nextTick();

  const liveSocket = WebSocketImpl.latestInstance;
  assert.ok(liveSocket);
  assert.ok(subscriptionRequestId);
  liveSocket.serverMessage({
    _tag: "Chunk",
    requestId: subscriptionRequestId,
    values: [firstRunningSessionEvent, secondRunningSessionEvent],
  });
  await nextTick();

  assert.equal(responses.length, 3);
  assert.equal(responses[1].method, "turn/started");
  assert.equal(responses[1].params.turnId, "turn-1");
  assert.equal(responses[2].method, "turn/started");
  assert.equal(responses[2].params.turnId, "turn-2");
});

test("T3 adapter suppresses duplicate resumed-thread turn lifecycle notifications for the same turn state", async () => {
  let subscriptionRequestId = null;
  const runningSessionEvent = {
    sequence: 8,
    eventId: "event-8",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:08.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.session-set",
    payload: {
      threadId: "thread-123",
      session: {
        status: "running",
        activeTurnId: "turn-2",
        updatedAt: "2026-04-07T12:00:08.000Z",
      },
    },
  };
  const duplicateRunningSessionEvent = {
    ...runningSessionEvent,
    sequence: 9,
    eventId: "event-9",
    occurredAt: "2026-04-07T12:00:09.000Z",
    payload: {
      ...runningSessionEvent.payload,
      session: {
        ...runningSessionEvent.payload.session,
        updatedAt: "2026-04-07T12:00:09.000Z",
      },
    },
  };
  const completedSessionEvent = {
    sequence: 10,
    eventId: "event-10",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:10.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.session-set",
    payload: {
      threadId: "thread-123",
      session: {
        status: "ready",
        activeTurnId: null,
        updatedAt: "2026-04-07T12:00:10.000Z",
      },
    },
  };
  const duplicateCompletedSessionEvent = {
    ...completedSessionEvent,
    sequence: 11,
    eventId: "event-11",
    occurredAt: "2026-04-07T12:00:11.000Z",
    payload: {
      ...completedSessionEvent.payload,
      session: {
        ...completedSessionEvent.payload.session,
        updatedAt: "2026-04-07T12:00:11.000Z",
      },
    },
  };

  const WebSocketImpl = createScriptedWebSocket(({ message, socket }) => {
    if (message.tag === "server.getConfig") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, {
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
        });
      });
      return;
    }

    if (message.tag === "subscribeOrchestrationDomainEvents") {
      subscriptionRequestId = message.id;
      return;
    }

    if (message.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, createBaseSnapshot());
      });
    }
  });

  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: createTestEnv(),
    WebSocketImpl,
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });

  await adapter.whenReady();
  adapter.send(JSON.stringify({
    id: "req-thread-resume",
    method: "thread/resume",
    params: {
      threadId: "thread-123",
    },
  }));
  await nextTick();

  const liveSocket = WebSocketImpl.latestInstance;
  assert.ok(liveSocket);
  assert.ok(subscriptionRequestId);
  liveSocket.serverMessage({
    _tag: "Chunk",
    requestId: subscriptionRequestId,
    values: [
      runningSessionEvent,
      duplicateRunningSessionEvent,
      completedSessionEvent,
      duplicateCompletedSessionEvent,
    ],
  });
  await nextTick();

  const turnStartedNotifications = responses.filter((entry) => entry.method === "turn/started");
  const turnCompletedNotifications = responses.filter((entry) => entry.method === "turn/completed");

  assert.equal(turnStartedNotifications.length, 1);
  assert.equal(turnStartedNotifications[0].params.turnId, "turn-2");
  assert.equal(turnCompletedNotifications.length, 1);
  assert.equal(turnCompletedNotifications[0].params.turnId, "turn-2");
  assert.equal(turnCompletedNotifications[0].params.status, "completed");
});

test("T3 adapter stops emitting live notifications after a resumed thread becomes unsupported", async () => {
  let subscriptionRequestId = null;
  const providerSwitchEvent = {
    sequence: 8,
    eventId: "event-8",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:08.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.meta-updated",
    payload: {
      threadId: "thread-123",
      modelSelection: {
        provider: "claudeAgent",
        model: "claude-sonnet",
      },
      updatedAt: "2026-04-07T12:00:08.000Z",
    },
  };
  const assistantMessageEvent = {
    sequence: 9,
    eventId: "event-9",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:09.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.message-sent",
    payload: {
      threadId: "thread-123",
      messageId: "msg-unsupported",
      role: "assistant",
      text: "Should stay hidden",
      attachments: [],
      turnId: "turn-1",
      streaming: false,
      createdAt: "2026-04-07T12:00:09.000Z",
      updatedAt: "2026-04-07T12:00:09.000Z",
    },
  };

  const WebSocketImpl = createScriptedWebSocket(({ message, socket }) => {
    if (message.tag === "server.getConfig") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, {
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
        });
      });
      return;
    }

    if (message.tag === "subscribeOrchestrationDomainEvents") {
      subscriptionRequestId = message.id;
      return;
    }

    if (message.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, createBaseSnapshot());
      });
    }
  });

  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: createTestEnv(),
    WebSocketImpl,
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });

  await adapter.whenReady();
  adapter.send(JSON.stringify({
    id: "req-thread-resume",
    method: "thread/resume",
    params: {
      threadId: "thread-123",
    },
  }));
  await nextTick();

  const liveSocket = WebSocketImpl.latestInstance;
  assert.ok(liveSocket);
  assert.ok(subscriptionRequestId);
  liveSocket.serverMessage({
    _tag: "Chunk",
    requestId: subscriptionRequestId,
    values: [providerSwitchEvent, assistantMessageEvent],
  });
  await nextTick();

  assert.equal(responses.length, 1);
  assert.equal(responses[0].id, "req-thread-resume");
});

test("T3 adapter projects plan and task activity updates into Android-safe live notifications after resume", async () => {
  let subscriptionRequestId = null;
  const planActivityEvent = {
    sequence: 8,
    eventId: "event-8",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:08.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-plan-1",
        tone: "info",
        kind: "turn.plan.updated",
        summary: "Plan updated",
        payload: {
          explanation: "Working through the change",
          plan: [
            { step: "Inspect files", status: "completed" },
            { step: "Update bridge", status: "in_progress" },
          ],
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:08.000Z",
      },
    },
  };
  const taskProgressEvent = {
    sequence: 9,
    eventId: "event-9",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:09.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-task-1-progress",
        tone: "info",
        kind: "task.progress",
        summary: "Reasoning update",
        payload: {
          taskId: "task-1",
          summary: "Inspecting reconnect state",
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:09.000Z",
      },
    },
  };
  const taskCompletedEvent = {
    sequence: 10,
    eventId: "event-10",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:10.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-task-1-complete",
        tone: "info",
        kind: "task.completed",
        summary: "Task completed",
        payload: {
          taskId: "task-1",
          status: "completed",
          summary: "Reconnect analysis finished",
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:10.000Z",
      },
    },
  };

  const WebSocketImpl = createScriptedWebSocket(({ message, socket }) => {
    if (message.tag === "server.getConfig") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, {
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
        });
      });
      return;
    }

    if (message.tag === "subscribeOrchestrationDomainEvents") {
      subscriptionRequestId = message.id;
      return;
    }

    if (message.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, createBaseSnapshot());
      });
    }
  });

  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: createTestEnv(),
    WebSocketImpl,
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });

  await adapter.whenReady();
  adapter.send(JSON.stringify({
    id: "req-thread-resume",
    method: "thread/resume",
    params: {
      threadId: "thread-123",
    },
  }));
  await nextTick();

  const liveSocket = WebSocketImpl.latestInstance;
  assert.ok(liveSocket);
  assert.ok(subscriptionRequestId);
  liveSocket.serverMessage({
    _tag: "Chunk",
    requestId: subscriptionRequestId,
    values: [planActivityEvent, taskProgressEvent, taskCompletedEvent],
  });
  await nextTick();

  assert.equal(responses.length, 4);
  assert.equal(responses[1].method, "turn/plan/updated");
  assert.equal(responses[1].params.threadId, "thread-123");
  assert.equal(responses[1].params.turnId, "turn-2");
  assert.equal(responses[1].params.explanation, "Working through the change");
  assert.equal(responses[1].params.steps.length, 2);

  assert.equal(responses[2].method, "item/updated");
  assert.equal(responses[2].params.threadId, "thread-123");
  assert.equal(responses[2].params.turnId, "turn-2");
  assert.equal(responses[2].params.item.type, "activity");
  assert.equal(responses[2].params.item.summary, "Inspecting reconnect state");

  assert.equal(responses[3].method, "item/completed");
  assert.equal(responses[3].params.threadId, "thread-123");
  assert.equal(responses[3].params.turnId, "turn-2");
  assert.equal(responses[3].params.item.type, "activity");
  assert.equal(responses[3].params.item.status, "completed");
  assert.equal(responses[3].params.item.summary, "Reconnect analysis finished");
  assert.equal(responses[3].params.itemId, responses[2].params.itemId);
});

test("T3 adapter projects tool activity lifecycle into stable execution item notifications after resume", async () => {
  let subscriptionRequestId = null;
  const toolStartedEvent = {
    sequence: 8,
    eventId: "event-8",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:08.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-tool-start",
        tone: "tool",
        kind: "tool.started",
        summary: "Run tests started",
        payload: {
          itemType: "command_execution",
          detail: "npm test",
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:08.000Z",
      },
    },
  };
  const toolUpdatedEvent = {
    sequence: 9,
    eventId: "event-9",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:09.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-tool-update",
        tone: "tool",
        kind: "tool.updated",
        summary: "Run tests",
        payload: {
          itemType: "command_execution",
          status: "in_progress",
          detail: "npm test",
          data: {
            pid: 123,
          },
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:09.000Z",
      },
    },
  };
  const toolCompletedEvent = {
    sequence: 10,
    eventId: "event-10",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:10.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-tool-complete",
        tone: "tool",
        kind: "tool.completed",
        summary: "Run tests",
        payload: {
          itemType: "command_execution",
          detail: "npm test",
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:10.000Z",
      },
    },
  };

  const WebSocketImpl = createScriptedWebSocket(({ message, socket }) => {
    if (message.tag === "server.getConfig") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, {
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
        });
      });
      return;
    }

    if (message.tag === "subscribeOrchestrationDomainEvents") {
      subscriptionRequestId = message.id;
      return;
    }

    if (message.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, createBaseSnapshot());
      });
    }
  });

  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: createTestEnv(),
    WebSocketImpl,
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });

  await adapter.whenReady();
  adapter.send(JSON.stringify({
    id: "req-thread-resume",
    method: "thread/resume",
    params: {
      threadId: "thread-123",
    },
  }));
  await nextTick();

  const liveSocket = WebSocketImpl.latestInstance;
  assert.ok(liveSocket);
  assert.ok(subscriptionRequestId);
  liveSocket.serverMessage({
    _tag: "Chunk",
    requestId: subscriptionRequestId,
    values: [toolStartedEvent, toolUpdatedEvent, toolCompletedEvent],
  });
  await nextTick();

  assert.equal(responses.length, 4);
  assert.equal(responses[1].method, "item/updated");
  assert.equal(responses[1].params.item.type, "command_execution");
  assert.equal(responses[1].params.item.command, "npm test");

  assert.equal(responses[2].method, "item/updated");
  assert.equal(responses[2].params.item.type, "command_execution");
  assert.equal(responses[2].params.item.status, "in_progress");
  assert.equal(responses[2].params.item.command, "npm test");
  assert.equal(responses[2].params.item.data.pid, 123);

  assert.equal(responses[3].method, "item/completed");
  assert.equal(responses[3].params.item.type, "command_execution");
  assert.equal(responses[3].params.item.status, "completed");
  assert.equal(responses[3].params.item.command, "npm test");
  assert.equal(responses[1].params.itemId, responses[2].params.itemId);
  assert.equal(responses[2].params.itemId, responses[3].params.itemId);
});

test("T3 adapter projects approval and user-input activity lifecycles into stable execution item notifications after resume", async () => {
  let subscriptionRequestId = null;
  const approvalRequestedEvent = {
    sequence: 8,
    eventId: "event-8",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:08.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-approval-1-open",
        tone: "approval",
        kind: "approval.requested",
        summary: "Command approval requested",
        payload: {
          requestId: "approval-1",
          requestKind: "command",
          requestType: "command_approval",
          detail: "Run git diff --stat",
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:08.000Z",
      },
    },
  };
  const approvalResolvedEvent = {
    sequence: 9,
    eventId: "event-9",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:09.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-approval-1-resolved",
        tone: "approval",
        kind: "approval.resolved",
        summary: "Approval resolved",
        payload: {
          requestId: "approval-1",
          requestKind: "command",
          requestType: "command_approval",
          decision: "accept",
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:09.000Z",
      },
    },
  };
  const userInputRequestedEvent = {
    sequence: 10,
    eventId: "event-10",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:10.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-input-1-open",
        tone: "info",
        kind: "user-input.requested",
        summary: "User input requested",
        payload: {
          requestId: "user-input-1",
          questions: [
            {
              id: "deploy_target",
              question: "Where should we deploy?",
            },
          ],
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:10.000Z",
      },
    },
  };
  const userInputResolvedEvent = {
    sequence: 11,
    eventId: "event-11",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:11.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-input-1-resolved",
        tone: "info",
        kind: "user-input.resolved",
        summary: "User input submitted",
        payload: {
          requestId: "user-input-1",
          answers: {
            deploy_target: ["preview"],
          },
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:11.000Z",
      },
    },
  };

  const WebSocketImpl = createScriptedWebSocket(({ message, socket }) => {
    if (message.tag === "server.getConfig") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, {
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
        });
      });
      return;
    }

    if (message.tag === "subscribeOrchestrationDomainEvents") {
      subscriptionRequestId = message.id;
      return;
    }

    if (message.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, createBaseSnapshot());
      });
    }
  });

  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: createTestEnv(),
    WebSocketImpl,
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });

  await adapter.whenReady();
  adapter.send(JSON.stringify({
    id: "req-thread-resume",
    method: "thread/resume",
    params: {
      threadId: "thread-123",
    },
  }));
  await nextTick();

  const liveSocket = WebSocketImpl.latestInstance;
  assert.ok(liveSocket);
  assert.ok(subscriptionRequestId);
  liveSocket.serverMessage({
    _tag: "Chunk",
    requestId: subscriptionRequestId,
    values: [
      approvalRequestedEvent,
      approvalResolvedEvent,
      userInputRequestedEvent,
      userInputResolvedEvent,
    ],
  });
  await nextTick();

  assert.equal(responses.length, 9);
  const approvalRequest = responses.find((entry) =>
    entry.id === "t3-approval-request:thread-123:approval-1"
  );
  const approvalOpened = responses.find((entry) =>
    entry.method === "item/updated" && entry.params?.itemId === "t3-approval:thread-123:approval-1"
  );
  const approvalCleared = responses.find((entry) =>
    entry.method === "approval/cleared" && entry.params?.requestId === "approval-1"
  );
  const approvalCompleted = responses.find((entry) =>
    entry.method === "item/completed" && entry.params?.itemId === "t3-approval:thread-123:approval-1"
  );
  const userInputOpened = responses.find((entry) =>
    entry.method === "item/updated" && entry.params?.itemId === "t3-user-input:thread-123:user-input-1"
  );
  const userInputRequest = responses.find((entry) =>
    entry.id === "t3-user-input-request:thread-123:user-input-1"
  );
  const userInputCleared = responses.find((entry) =>
    entry.method === "user-input/cleared" && entry.params?.requestId === "user-input-1"
  );
  const userInputCompleted = responses.find((entry) =>
    entry.method === "item/completed" && entry.params?.itemId === "t3-user-input:thread-123:user-input-1"
  );

  assert.equal(approvalRequest?.method, "item/commandExecution/requestApproval");
  assert.equal(approvalRequest?.params?.command, "Run git diff --stat");
  assert.equal(approvalRequest?.params?.requestId, "approval-1");
  assert.equal(approvalOpened?.params?.item.status, "in_progress");
  assert.equal(approvalOpened?.params?.item.title, "Command approval requested");
  assert.equal(approvalOpened?.params?.item.summary, "Run git diff --stat");
  assert.equal(approvalCleared?.params?.threadId, "thread-123");
  assert.equal(approvalCompleted?.params?.item.status, "completed");
  assert.equal(approvalCompleted?.params?.item.title, "Approval resolved");
  assert.equal(userInputRequest?.method, "item/tool/requestUserInput");
  assert.equal(userInputRequest?.params?.requestId, "user-input-1");
  assert.equal(userInputRequest?.params?.threadId, "thread-123");
  assert.equal(userInputRequest?.params?.questions?.[0]?.id, "deploy_target");
  assert.equal(userInputOpened?.params?.item.status, "in_progress");
  assert.equal(userInputOpened?.params?.item.title, "User input requested");
  assert.equal(userInputCleared?.params?.threadId, "thread-123");
  assert.equal(userInputCompleted?.params?.item.status, "completed");
  assert.equal(userInputCompleted?.params?.item.title, "User input submitted");
});

test("T3 adapter suppresses duplicate approval and user-input cleared notifications for repeated resolved activities", async () => {
  let subscriptionRequestId = null;
  const approvalRequestedEvent = {
    sequence: 8,
    eventId: "event-8",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:08.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-approval-1-open",
        tone: "approval",
        kind: "approval.requested",
        summary: "Command approval requested",
        payload: {
          requestId: "approval-1",
          requestKind: "command",
          requestType: "command_approval",
          detail: "Run git diff --stat",
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:08.000Z",
      },
    },
  };
  const approvalResolvedEvent = {
    sequence: 9,
    eventId: "event-9",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:09.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-approval-1-resolved",
        tone: "approval",
        kind: "approval.resolved",
        summary: "Approval resolved",
        payload: {
          requestId: "approval-1",
          requestKind: "command",
          requestType: "command_approval",
          decision: "accept",
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:09.000Z",
      },
    },
  };
  const duplicateApprovalResolvedEvent = {
    ...approvalResolvedEvent,
    sequence: 10,
    eventId: "event-10",
  };
  const userInputRequestedEvent = {
    sequence: 11,
    eventId: "event-11",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:11.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-input-1-open",
        tone: "info",
        kind: "user-input.requested",
        summary: "User input requested",
        payload: {
          requestId: "user-input-1",
          questions: [
            {
              id: "deploy_target",
              question: "Where should we deploy?",
            },
          ],
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:11.000Z",
      },
    },
  };
  const userInputResolvedEvent = {
    sequence: 12,
    eventId: "event-12",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:12.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-input-1-resolved",
        tone: "info",
        kind: "user-input.resolved",
        summary: "User input submitted",
        payload: {
          requestId: "user-input-1",
          answers: {
            deploy_target: ["preview"],
          },
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:12.000Z",
      },
    },
  };
  const duplicateUserInputResolvedEvent = {
    ...userInputResolvedEvent,
    sequence: 13,
    eventId: "event-13",
  };

  const WebSocketImpl = createScriptedWebSocket(({ message, socket }) => {
    if (message.tag === "server.getConfig") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, {
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
        });
      });
      return;
    }

    if (message.tag === "subscribeOrchestrationDomainEvents") {
      subscriptionRequestId = message.id;
      return;
    }

    if (message.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, createBaseSnapshot());
      });
    }
  });

  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: createTestEnv(),
    WebSocketImpl,
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });

  await adapter.whenReady();
  adapter.send(JSON.stringify({
    id: "req-thread-resume",
    method: "thread/resume",
    params: {
      threadId: "thread-123",
    },
  }));
  await nextTick();

  const liveSocket = WebSocketImpl.latestInstance;
  assert.ok(liveSocket);
  assert.ok(subscriptionRequestId);
  liveSocket.serverMessage({
    _tag: "Chunk",
    requestId: subscriptionRequestId,
    values: [
      approvalRequestedEvent,
      approvalResolvedEvent,
      duplicateApprovalResolvedEvent,
      userInputRequestedEvent,
      userInputResolvedEvent,
      duplicateUserInputResolvedEvent,
    ],
  });
  await nextTick();

  const approvalClearedNotifications = responses.filter((entry) =>
    entry.method === "approval/cleared" && entry.params?.requestId === "approval-1"
  );
  const userInputClearedNotifications = responses.filter((entry) =>
    entry.method === "user-input/cleared" && entry.params?.requestId === "user-input-1"
  );

  assert.equal(approvalClearedNotifications.length, 1);
  assert.equal(userInputClearedNotifications.length, 1);
});

test("T3 adapter keeps repeated same-command tool activities distinct within one resumed turn", async () => {
  let subscriptionRequestId = null;
  const toolStartedFirstEvent = {
    sequence: 8,
    eventId: "event-8",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:08.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-tool-start-1",
        tone: "tool",
        kind: "tool.started",
        summary: "Run tests started",
        payload: {
          itemType: "command_execution",
          detail: "npm test",
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:08.000Z",
      },
    },
  };
  const toolStartedSecondEvent = {
    ...toolStartedFirstEvent,
    sequence: 9,
    eventId: "event-9",
    occurredAt: "2026-04-07T12:00:09.000Z",
    payload: {
      ...toolStartedFirstEvent.payload,
      activity: {
        ...toolStartedFirstEvent.payload.activity,
        id: "activity-tool-start-2",
        createdAt: "2026-04-07T12:00:09.000Z",
      },
    },
  };
  const toolCompletedFirstEvent = {
    sequence: 10,
    eventId: "event-10",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:10.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-tool-complete-1",
        tone: "tool",
        kind: "tool.completed",
        summary: "Run tests",
        payload: {
          itemType: "command_execution",
          detail: "npm test",
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:10.000Z",
      },
    },
  };
  const toolCompletedSecondEvent = {
    ...toolCompletedFirstEvent,
    sequence: 11,
    eventId: "event-11",
    occurredAt: "2026-04-07T12:00:11.000Z",
    payload: {
      ...toolCompletedFirstEvent.payload,
      activity: {
        ...toolCompletedFirstEvent.payload.activity,
        id: "activity-tool-complete-2",
        createdAt: "2026-04-07T12:00:11.000Z",
      },
    },
  };

  const WebSocketImpl = createScriptedWebSocket(({ message, socket }) => {
    if (message.tag === "server.getConfig") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, {
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
        });
      });
      return;
    }

    if (message.tag === "subscribeOrchestrationDomainEvents") {
      subscriptionRequestId = message.id;
      return;
    }

    if (message.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, createBaseSnapshot());
      });
    }
  });

  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: createTestEnv(),
    WebSocketImpl,
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });

  await adapter.whenReady();
  adapter.send(JSON.stringify({
    id: "req-thread-resume",
    method: "thread/resume",
    params: {
      threadId: "thread-123",
    },
  }));
  await nextTick();

  const liveSocket = WebSocketImpl.latestInstance;
  assert.ok(liveSocket);
  assert.ok(subscriptionRequestId);
  liveSocket.serverMessage({
    _tag: "Chunk",
    requestId: subscriptionRequestId,
    values: [
      toolStartedFirstEvent,
      toolStartedSecondEvent,
      toolCompletedFirstEvent,
      toolCompletedSecondEvent,
    ],
  });
  await nextTick();

  assert.equal(responses.length, 5);
  assert.equal(responses[1].method, "item/updated");
  assert.equal(responses[2].method, "item/updated");
  assert.equal(responses[3].method, "item/completed");
  assert.equal(responses[4].method, "item/completed");
  assert.notEqual(responses[1].params.itemId, responses[2].params.itemId);
  assert.equal(responses[3].params.itemId, responses[1].params.itemId);
  assert.equal(responses[4].params.itemId, responses[2].params.itemId);
});

test("T3 adapter suppresses duplicate live activity notifications when the same activity id is replayed with a new sequence", async () => {
  let subscriptionRequestId = null;
  const firstPlanEvent = {
    sequence: 8,
    eventId: "event-8",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:08.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-plan-shared",
        tone: "info",
        kind: "turn.plan.updated",
        summary: "Plan updated",
        payload: {
          explanation: "Same plan",
          plan: [
            { step: "Inspect files", status: "completed" },
            { step: "Update bridge", status: "in_progress" },
          ],
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:08.000Z",
      },
    },
  };
  const duplicatedPlanEvent = {
    ...firstPlanEvent,
    sequence: 9,
    eventId: "event-9",
    occurredAt: "2026-04-07T12:00:09.000Z",
    payload: {
      ...firstPlanEvent.payload,
      activity: {
        ...firstPlanEvent.payload.activity,
        createdAt: "2026-04-07T12:00:09.000Z",
      },
    },
  };

  const WebSocketImpl = createScriptedWebSocket(({ message, socket }) => {
    if (message.tag === "server.getConfig") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, {
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
        });
      });
      return;
    }

    if (message.tag === "subscribeOrchestrationDomainEvents") {
      subscriptionRequestId = message.id;
      return;
    }

    if (message.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, createBaseSnapshot());
      });
    }
  });

  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: createTestEnv(),
    WebSocketImpl,
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });

  await adapter.whenReady();
  adapter.send(JSON.stringify({
    id: "req-thread-resume",
    method: "thread/resume",
    params: {
      threadId: "thread-123",
    },
  }));
  await nextTick();

  const liveSocket = WebSocketImpl.latestInstance;
  assert.ok(liveSocket);
  assert.ok(subscriptionRequestId);
  liveSocket.serverMessage({
    _tag: "Chunk",
    requestId: subscriptionRequestId,
    values: [firstPlanEvent, duplicatedPlanEvent],
  });
  await nextTick();

  assert.equal(responses.length, 2);
  assert.equal(responses[1].method, "turn/plan/updated");
  assert.equal(responses[1].params.explanation, "Same plan");
});

test("T3 adapter suppresses duplicate approval activity notifications when the same activity id is replayed with a new sequence", async () => {
  let subscriptionRequestId = null;
  const firstApprovalEvent = {
    sequence: 8,
    eventId: "event-8",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:08.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-approval-shared",
        tone: "approval",
        kind: "approval.requested",
        summary: "Command approval requested",
        payload: {
          requestId: "approval-1",
          requestKind: "command",
          requestType: "command_approval",
          detail: "Run npm test",
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:08.000Z",
      },
    },
  };
  const duplicatedApprovalEvent = {
    ...firstApprovalEvent,
    sequence: 9,
    eventId: "event-9",
    occurredAt: "2026-04-07T12:00:09.000Z",
    payload: {
      ...firstApprovalEvent.payload,
      activity: {
        ...firstApprovalEvent.payload.activity,
        createdAt: "2026-04-07T12:00:09.000Z",
      },
    },
  };

  const WebSocketImpl = createScriptedWebSocket(({ message, socket }) => {
    if (message.tag === "server.getConfig") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, {
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
        });
      });
      return;
    }

    if (message.tag === "subscribeOrchestrationDomainEvents") {
      subscriptionRequestId = message.id;
      return;
    }

    if (message.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, createBaseSnapshot());
      });
    }
  });

  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: createTestEnv(),
    WebSocketImpl,
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });

  await adapter.whenReady();
  adapter.send(JSON.stringify({
    id: "req-thread-resume",
    method: "thread/resume",
    params: {
      threadId: "thread-123",
    },
  }));
  await nextTick();

  const liveSocket = WebSocketImpl.latestInstance;
  assert.ok(liveSocket);
  assert.ok(subscriptionRequestId);
  liveSocket.serverMessage({
    _tag: "Chunk",
    requestId: subscriptionRequestId,
    values: [firstApprovalEvent, duplicatedApprovalEvent],
  });
  await nextTick();

  assert.equal(responses.length, 3);
  const approvalRequests = responses.filter((entry) =>
    entry.method === "item/commandExecution/requestApproval"
  );
  const approvalUpdates = responses.filter((entry) =>
    entry.method === "item/updated" && entry.params?.itemId === "t3-approval:thread-123:approval-1"
  );
  assert.equal(approvalRequests.length, 1);
  assert.equal(approvalUpdates.length, 1);
  assert.equal(approvalUpdates[0].params.item.summary, "Run npm test");
});

test("T3 adapter clears transient replay failures and does not leak unhandled rejections during live recovery", async () => {
  let replayAttemptCount = 0;
  let subscriptionRequestId = null;
  const metadataUpdates = [];
  const unhandledRejections = [];
  const processHandler = (reason) => {
    unhandledRejections.push(reason);
  };
  process.on("unhandledRejection", processHandler);

  const firstGapEvent = {
    sequence: 10,
    eventId: "event-10",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:10.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.meta-updated",
    payload: {
      threadId: "thread-123",
      title: "Recovered after retry",
      updatedAt: "2026-04-07T12:00:10.000Z",
    },
  };
  const secondGapEvent = {
    ...firstGapEvent,
    sequence: 11,
    eventId: "event-11",
    occurredAt: "2026-04-07T12:00:11.000Z",
    payload: {
      ...firstGapEvent.payload,
      updatedAt: "2026-04-07T12:00:11.000Z",
    },
  };

  const WebSocketImpl = createScriptedWebSocket(({ message, socket }) => {
    if (message.tag === "server.getConfig") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, {
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
        });
      });
      return;
    }

    if (message.tag === "subscribeOrchestrationDomainEvents") {
      subscriptionRequestId = message.id;
      return;
    }

    if (message.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, createBaseSnapshot());
      });
      return;
    }

    if (message.tag === "orchestration.replayEvents") {
      replayAttemptCount += 1;
      queueMicrotask(() => {
        if (replayAttemptCount === 1) {
          failRpc(socket, message.id, "temporary replay outage");
          return;
        }
        succeedRpc(socket, message.id, [
          {
            sequence: 8,
            eventId: "event-8",
            aggregateKind: "thread",
            aggregateId: "thread-123",
            occurredAt: "2026-04-07T12:00:08.000Z",
            commandId: null,
            causationEventId: null,
            correlationId: null,
            metadata: {},
            type: "thread.message-sent",
            payload: {
              threadId: "thread-123",
              messageId: "msg-2",
              role: "assistant",
              text: "Recovered after retry",
              attachments: [],
              turnId: "turn-1",
              streaming: false,
              createdAt: "2026-04-07T12:00:08.000Z",
              updatedAt: "2026-04-07T12:00:08.000Z",
            },
          },
          firstGapEvent,
          secondGapEvent,
        ]);
      });
    }
  });

  try {
    const adapter = createRuntimeAdapter({
      targetKind: "t3-server",
      endpoint: "ws://127.0.0.1:7777",
      env: createTestEnv(),
      WebSocketImpl,
    });

    adapter.onMetadata((metadata) => {
      metadataUpdates.push(metadata);
    });

    await adapter.whenReady();
    const liveSocket = WebSocketImpl.latestInstance;
    assert.ok(liveSocket);
    assert.ok(subscriptionRequestId);

    liveSocket.serverMessage({
      _tag: "Chunk",
      requestId: subscriptionRequestId,
      values: [firstGapEvent],
    });
    await nextTick();
    await nextTick();

    assert.ok(metadataUpdates.some((metadata) => metadata.runtimeAttachFailure === "temporary replay outage"));

    liveSocket.serverMessage({
      _tag: "Chunk",
      requestId: subscriptionRequestId,
      values: [secondGapEvent],
    });
    await nextTick();
    await nextTick();

    assert.equal(unhandledRejections.length, 0);
    assert.equal(metadataUpdates.at(-1)?.runtimeSubscriptionState, "live");
    assert.equal(metadataUpdates.at(-1)?.runtimeAttachFailure, null);
  } finally {
    process.off("unhandledRejection", processHandler);
  }
});

test("T3 adapter reconnects through snapshot plus replay after a transport restart without duplicating resumed-thread notifications", async () => {
  let firstSubscriptionRequestId = null;
  let secondSubscriptionRequestId = null;
  let persistedSequence = 0;
  const metadataUpdates = [];
  const replayRequests = [];

  const firstAssistantEvent = {
    sequence: 8,
    eventId: "event-8",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:08.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.message-sent",
    payload: {
      threadId: "thread-123",
      messageId: "msg-2",
      role: "assistant",
      text: "Seen before restart",
      attachments: [],
      turnId: "turn-1",
      streaming: false,
      createdAt: "2026-04-07T12:00:08.000Z",
      updatedAt: "2026-04-07T12:00:08.000Z",
    },
  };
  const reconnectTitleEvent = {
    sequence: 9,
    eventId: "event-9",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:09.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.meta-updated",
    payload: {
      threadId: "thread-123",
      title: "Recovered after restart",
      updatedAt: "2026-04-07T12:00:09.000Z",
    },
  };

  const WebSocketImpl = createScriptedWebSocket(({ message, socket }) => {
    if (message.tag === "server.getConfig") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, {
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
        });
      });
      return;
    }

    if (message.tag === "subscribeOrchestrationDomainEvents") {
      if (socket.connectionIndex === 0) {
        firstSubscriptionRequestId = message.id;
      } else {
        secondSubscriptionRequestId = message.id;
      }
      return;
    }

    if (message.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, createBaseSnapshot());
      });
      return;
    }

    if (message.tag === "orchestration.replayEvents") {
      replayRequests.push({
        connectionIndex: socket.connectionIndex,
        fromSequenceExclusive: message.payload.fromSequenceExclusive,
      });
      queueMicrotask(() => {
        succeedRpc(socket, message.id, [
          firstAssistantEvent,
          reconnectTitleEvent,
        ]);
      });
    }
  });

  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: createTestEnv(),
    WebSocketImpl,
    loadReplayCursor() {
      return persistedSequence;
    },
    persistReplayCursor(scope) {
      persistedSequence = scope.sequence;
    },
  });

  adapter.onMetadata((metadata) => {
    metadataUpdates.push(metadata);
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });

  await adapter.whenReady();
  adapter.send(JSON.stringify({
    id: "req-thread-resume",
    method: "thread/resume",
    params: {
      threadId: "thread-123",
    },
  }));
  await nextTick();

  const firstSocket = WebSocketImpl.instances[0];
  assert.ok(firstSocket);
  assert.ok(firstSubscriptionRequestId);
  firstSocket.serverMessage({
    _tag: "Chunk",
    requestId: firstSubscriptionRequestId,
    values: [firstAssistantEvent],
  });
  await nextTick();

  assert.equal(persistedSequence, 8);
  assert.equal(
    responses.filter((entry) => entry.method === "codex/event/agent_message" && entry.params?.messageId === "msg-2").length,
    1,
  );

  firstSocket.close(1012, "service restart");
  await waitFor(() => WebSocketImpl.instances.length >= 2);
  await waitFor(() => secondSubscriptionRequestId !== null);
  await waitFor(() => metadataUpdates.at(-1)?.runtimeSubscriptionState === "live" && persistedSequence === 9);

  adapter.send(JSON.stringify({
    id: "req-thread-read",
    method: "thread/read",
    params: {
      threadId: "thread-123",
    },
  }));
  await nextTick();

  assert.ok(metadataUpdates.some((metadata) => metadata.runtimeAttachState === "reconnecting"));
  assert.deepEqual(replayRequests, [
    {
      connectionIndex: 1,
      fromSequenceExclusive: 7,
    },
  ]);
  assert.equal(
    responses.filter((entry) => entry.method === "codex/event/agent_message" && entry.params?.messageId === "msg-2").length,
    1,
  );
  assert.ok(responses.some((entry) =>
    entry.method === "thread/name/updated" && entry.params?.title === "Recovered after restart"));
  assert.equal(
    responses.filter((entry) =>
      entry.method === "thread/name/updated" && entry.params?.title === "Recovered after restart"
    ).length,
    1,
  );

  const threadReadResponse = responses.find((entry) => entry.id === "req-thread-read");
  assert.ok(threadReadResponse);
  assert.equal(threadReadResponse.result.thread.title, "Recovered after restart");
  assert.equal(threadReadResponse.result.thread.turns[0].items[1].content[0].text, "Seen before restart");
});

test("T3 adapter reconnect replay does not duplicate prior plan/task/tool notifications and still emits the new activity completion", async () => {
  let firstSubscriptionRequestId = null;
  let secondSubscriptionRequestId = null;
  let persistedSequence = 0;
  const replayRequests = [];

  const planUpdatedEvent = {
    sequence: 8,
    eventId: "event-8",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:08.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-plan-1",
        tone: "info",
        kind: "turn.plan.updated",
        summary: "Plan updated",
        payload: {
          explanation: "Reconnect safely",
          plan: [
            { step: "Resume thread", status: "completed" },
            { step: "Watch replay", status: "in_progress" },
          ],
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:08.000Z",
      },
    },
  };
  const toolStartedEvent = {
    sequence: 9,
    eventId: "event-9",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:09.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-tool-start",
        tone: "tool",
        kind: "tool.started",
        summary: "Run tests started",
        payload: {
          itemType: "command_execution",
          detail: "npm test",
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:09.000Z",
      },
    },
  };
  const toolCompletedEvent = {
    sequence: 10,
    eventId: "event-10",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:10.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-tool-complete",
        tone: "tool",
        kind: "tool.completed",
        summary: "Run tests",
        payload: {
          itemType: "command_execution",
          detail: "npm test",
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:10.000Z",
      },
    },
  };

  const WebSocketImpl = createScriptedWebSocket(({ message, socket }) => {
    if (message.tag === "server.getConfig") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, {
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
        });
      });
      return;
    }

    if (message.tag === "subscribeOrchestrationDomainEvents") {
      if (socket.connectionIndex === 0) {
        firstSubscriptionRequestId = message.id;
      } else {
        secondSubscriptionRequestId = message.id;
      }
      return;
    }

    if (message.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, createBaseSnapshot());
      });
      return;
    }

    if (message.tag === "orchestration.replayEvents") {
      replayRequests.push({
        connectionIndex: socket.connectionIndex,
        fromSequenceExclusive: message.payload.fromSequenceExclusive,
      });
      queueMicrotask(() => {
        succeedRpc(socket, message.id, [
          planUpdatedEvent,
          toolStartedEvent,
          toolCompletedEvent,
        ]);
      });
    }
  });

  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: createTestEnv(),
    WebSocketImpl,
    loadReplayCursor() {
      return persistedSequence;
    },
    persistReplayCursor(scope) {
      persistedSequence = scope.sequence;
    },
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });

  await adapter.whenReady();
  adapter.send(JSON.stringify({
    id: "req-thread-resume",
    method: "thread/resume",
    params: {
      threadId: "thread-123",
    },
  }));
  await nextTick();

  const firstSocket = WebSocketImpl.instances[0];
  assert.ok(firstSocket);
  assert.ok(firstSubscriptionRequestId);
  firstSocket.serverMessage({
    _tag: "Chunk",
    requestId: firstSubscriptionRequestId,
    values: [planUpdatedEvent, toolStartedEvent],
  });
  await nextTick();

  const firstPlanNotification = responses.find((entry) => entry.method === "turn/plan/updated");
  const firstToolStartNotification = responses.find((entry) => entry.method === "item/updated");
  assert.ok(firstPlanNotification);
  assert.ok(firstToolStartNotification);
  assert.equal(firstToolStartNotification.params.item.command, "npm test");
  assert.equal(persistedSequence, 9);

  firstSocket.close(1012, "service restart");
  await waitFor(() => WebSocketImpl.instances.length >= 2);
  await waitFor(() => secondSubscriptionRequestId !== null);
  await waitFor(() => persistedSequence === 10);

  const planNotifications = responses.filter((entry) => entry.method === "turn/plan/updated");
  const toolUpdatedNotifications = responses.filter((entry) => entry.method === "item/updated");
  const toolCompletedNotifications = responses.filter((entry) => entry.method === "item/completed");

  assert.deepEqual(replayRequests, [
    {
      connectionIndex: 1,
      fromSequenceExclusive: 7,
    },
  ]);
  assert.equal(planNotifications.length, 1);
  assert.equal(toolUpdatedNotifications.length, 1);
  assert.equal(toolCompletedNotifications.length, 1);
  assert.equal(toolCompletedNotifications[0].params.item.command, "npm test");
  assert.equal(toolCompletedNotifications[0].params.itemId, firstToolStartNotification.params.itemId);
});

test("T3 adapter reconnect replay does not duplicate prior approval and user-input notifications and still emits the new resolutions", async () => {
  let firstSubscriptionRequestId = null;
  let secondSubscriptionRequestId = null;
  let persistedSequence = 0;
  const replayRequests = [];

  const approvalRequestedEvent = {
    sequence: 8,
    eventId: "event-8",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:08.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-approval-1-open",
        tone: "approval",
        kind: "approval.requested",
        summary: "Command approval requested",
        payload: {
          requestId: "approval-1",
          requestKind: "command",
          requestType: "command_approval",
          detail: "Run npm test",
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:08.000Z",
      },
    },
  };
  const userInputRequestedEvent = {
    sequence: 9,
    eventId: "event-9",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:09.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-input-1-open",
        tone: "info",
        kind: "user-input.requested",
        summary: "User input requested",
        payload: {
          requestId: "user-input-1",
          questions: [
            {
              id: "deploy_target",
              question: "Where should we deploy?",
            },
          ],
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:09.000Z",
      },
    },
  };
  const approvalResolvedEvent = {
    sequence: 10,
    eventId: "event-10",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:10.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-approval-1-resolved",
        tone: "approval",
        kind: "approval.resolved",
        summary: "Approval resolved",
        payload: {
          requestId: "approval-1",
          requestKind: "command",
          requestType: "command_approval",
          decision: "accept",
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:10.000Z",
      },
    },
  };
  const userInputResolvedEvent = {
    sequence: 11,
    eventId: "event-11",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:11.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.activity-appended",
    payload: {
      threadId: "thread-123",
      activity: {
        id: "activity-input-1-resolved",
        tone: "info",
        kind: "user-input.resolved",
        summary: "User input submitted",
        payload: {
          requestId: "user-input-1",
          answers: {
            deploy_target: ["preview"],
          },
        },
        turnId: "turn-2",
        createdAt: "2026-04-07T12:00:11.000Z",
      },
    },
  };

  const WebSocketImpl = createScriptedWebSocket(({ message, socket }) => {
    if (message.tag === "server.getConfig") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, {
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
        });
      });
      return;
    }

    if (message.tag === "subscribeOrchestrationDomainEvents") {
      if (socket.connectionIndex === 0) {
        firstSubscriptionRequestId = message.id;
      } else {
        secondSubscriptionRequestId = message.id;
      }
      return;
    }

    if (message.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, createBaseSnapshot());
      });
      return;
    }

    if (message.tag === "orchestration.replayEvents") {
      replayRequests.push({
        connectionIndex: socket.connectionIndex,
        fromSequenceExclusive: message.payload.fromSequenceExclusive,
      });
      queueMicrotask(() => {
        succeedRpc(socket, message.id, [
          approvalRequestedEvent,
          userInputRequestedEvent,
          approvalResolvedEvent,
          userInputResolvedEvent,
        ]);
      });
    }
  });

  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: createTestEnv(),
    WebSocketImpl,
    loadReplayCursor() {
      return persistedSequence;
    },
    persistReplayCursor(scope) {
      persistedSequence = scope.sequence;
    },
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });

  await adapter.whenReady();
  adapter.send(JSON.stringify({
    id: "req-thread-resume",
    method: "thread/resume",
    params: {
      threadId: "thread-123",
    },
  }));
  await nextTick();

  const firstSocket = WebSocketImpl.instances[0];
  assert.ok(firstSocket);
  assert.ok(firstSubscriptionRequestId);
  firstSocket.serverMessage({
    _tag: "Chunk",
    requestId: firstSubscriptionRequestId,
    values: [approvalRequestedEvent, userInputRequestedEvent],
  });
  await nextTick();

  const approvalOpened = responses.find((entry) =>
    entry.method === "item/updated" && entry.params?.itemId === "t3-approval:thread-123:approval-1"
  );
  const userInputOpened = responses.find((entry) =>
    entry.method === "item/updated" && entry.params?.itemId === "t3-user-input:thread-123:user-input-1"
  );
  assert.ok(approvalOpened);
  assert.ok(userInputOpened);
  assert.equal(persistedSequence, 9);

  firstSocket.close(1012, "service restart");
  await waitFor(() => WebSocketImpl.instances.length >= 2);
  await waitFor(() => secondSubscriptionRequestId !== null);
  await waitFor(() => persistedSequence === 11);

  const approvalUpdatedNotifications = responses.filter((entry) =>
    entry.method === "item/updated" && entry.params?.itemId === "t3-approval:thread-123:approval-1"
  );
  const userInputUpdatedNotifications = responses.filter((entry) =>
    entry.method === "item/updated" && entry.params?.itemId === "t3-user-input:thread-123:user-input-1"
  );
  const userInputRequestNotifications = responses.filter((entry) =>
    entry.id === "t3-user-input-request:thread-123:user-input-1"
  );
  const userInputClearedNotifications = responses.filter((entry) =>
    entry.method === "user-input/cleared" && entry.params?.requestId === "user-input-1"
  );
  const approvalCompletedNotifications = responses.filter((entry) =>
    entry.method === "item/completed" && entry.params?.itemId === "t3-approval:thread-123:approval-1"
  );
  const userInputCompletedNotifications = responses.filter((entry) =>
    entry.method === "item/completed" && entry.params?.itemId === "t3-user-input:thread-123:user-input-1"
  );

  assert.deepEqual(replayRequests, [
    {
      connectionIndex: 1,
      fromSequenceExclusive: 7,
    },
  ]);
  assert.equal(approvalUpdatedNotifications.length, 1);
  assert.equal(userInputUpdatedNotifications.length, 1);
  assert.equal(userInputRequestNotifications.length, 1);
  assert.equal(userInputClearedNotifications.length, 1);
  assert.equal(approvalCompletedNotifications.length, 1);
  assert.equal(userInputCompletedNotifications.length, 1);
  assert.equal(approvalCompletedNotifications[0].params.item.title, "Approval resolved");
  assert.equal(userInputCompletedNotifications[0].params.item.title, "User input submitted");
});

test("T3 adapter reconnect replay does not duplicate prior turn lifecycle notifications and still emits the new completion", async () => {
  let firstSubscriptionRequestId = null;
  let secondSubscriptionRequestId = null;
  let persistedSequence = 0;
  const replayRequests = [];

  const runningSessionEvent = {
    sequence: 8,
    eventId: "event-8",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:08.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.session-set",
    payload: {
      threadId: "thread-123",
      session: {
        status: "running",
        activeTurnId: "turn-2",
        updatedAt: "2026-04-07T12:00:08.000Z",
      },
    },
  };
  const completedSessionEvent = {
    sequence: 9,
    eventId: "event-9",
    aggregateKind: "thread",
    aggregateId: "thread-123",
    occurredAt: "2026-04-07T12:00:09.000Z",
    commandId: null,
    causationEventId: null,
    correlationId: null,
    metadata: {},
    type: "thread.session-set",
    payload: {
      threadId: "thread-123",
      session: {
        status: "ready",
        activeTurnId: null,
        updatedAt: "2026-04-07T12:00:09.000Z",
      },
    },
  };

  const WebSocketImpl = createScriptedWebSocket(({ message, socket }) => {
    if (message.tag === "server.getConfig") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, {
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
        });
      });
      return;
    }

    if (message.tag === "subscribeOrchestrationDomainEvents") {
      if (socket.connectionIndex === 0) {
        firstSubscriptionRequestId = message.id;
      } else {
        secondSubscriptionRequestId = message.id;
      }
      return;
    }

    if (message.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        succeedRpc(socket, message.id, createBaseSnapshot());
      });
      return;
    }

    if (message.tag === "orchestration.replayEvents") {
      replayRequests.push({
        connectionIndex: socket.connectionIndex,
        fromSequenceExclusive: message.payload.fromSequenceExclusive,
      });
      queueMicrotask(() => {
        succeedRpc(socket, message.id, [
          runningSessionEvent,
          completedSessionEvent,
        ]);
      });
    }
  });

  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: createTestEnv(),
    WebSocketImpl,
    loadReplayCursor() {
      return persistedSequence;
    },
    persistReplayCursor(scope) {
      persistedSequence = scope.sequence;
    },
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });

  await adapter.whenReady();
  adapter.send(JSON.stringify({
    id: "req-thread-resume",
    method: "thread/resume",
    params: {
      threadId: "thread-123",
    },
  }));
  await nextTick();

  const firstSocket = WebSocketImpl.instances[0];
  assert.ok(firstSocket);
  assert.ok(firstSubscriptionRequestId);
  firstSocket.serverMessage({
    _tag: "Chunk",
    requestId: firstSubscriptionRequestId,
    values: [runningSessionEvent],
  });
  await nextTick();

  assert.equal(
    responses.filter((entry) => entry.method === "turn/started").length,
    1,
  );
  assert.equal(persistedSequence, 8);

  firstSocket.close(1012, "service restart");
  await waitFor(() => WebSocketImpl.instances.length >= 2);
  await waitFor(() => secondSubscriptionRequestId !== null);
  await waitFor(() => persistedSequence === 9);

  const turnStartedNotifications = responses.filter((entry) => entry.method === "turn/started");
  const turnCompletedNotifications = responses.filter((entry) => entry.method === "turn/completed");

  assert.deepEqual(replayRequests, [
    {
      connectionIndex: 1,
      fromSequenceExclusive: 7,
    },
  ]);
  assert.equal(turnStartedNotifications.length, 1);
  assert.equal(turnStartedNotifications[0].params.turnId, "turn-2");
  assert.equal(turnCompletedNotifications.length, 1);
  assert.equal(turnCompletedNotifications[0].params.turnId, "turn-2");
  assert.equal(turnCompletedNotifications[0].params.status, "completed");
});
