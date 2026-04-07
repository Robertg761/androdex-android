const test = require("node:test");
const assert = require("node:assert/strict");

const { createRuntimeAdapter } = require("../src/runtime/adapter");

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

  const threadReadResponse = responses.find((entry) => entry.id === "req-thread-read");
  assert.ok(threadReadResponse);
  assert.equal(threadReadResponse.result.thread.title, "Recovered after restart");
  assert.equal(threadReadResponse.result.thread.turns[0].items[1].content[0].text, "Seen before restart");
});
