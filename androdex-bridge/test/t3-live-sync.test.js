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
        workspaceRoot: "/tmp/t3-state",
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
        worktreePath: "/tmp/t3-state",
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

    constructor(url) {
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
