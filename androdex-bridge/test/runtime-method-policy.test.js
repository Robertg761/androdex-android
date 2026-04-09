const test = require("node:test");
const assert = require("node:assert/strict");

const {
  buildRuntimeTargetMethodRejectionMessage,
  isCodexNativeRuntimeTarget,
  isReadOnlyRuntimeTarget,
  isRuntimeTargetMethodAllowed,
} = require("../src/runtime/method-policy");
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

class FakeWebSocket {
  static OPEN = 1;
  static CONNECTING = 0;
  static instances = [];

  constructor(url) {
    this.url = url;
    this.readyState = FakeWebSocket.CONNECTING;
    this.handlers = new Map();
    this.sentMessages = [];
    FakeWebSocket.instances.push(this);
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
    if (parsed.tag === "orchestration.dispatchCommand") {
      queueMicrotask(() => {
        this.handlers.get("message")?.(Buffer.from(JSON.stringify({
          _tag: "Exit",
          requestId: parsed.id,
          exit: {
            _tag: "Success",
            value: {
              sequence: 8,
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

class UnsupportedThreadFakeWebSocket extends FakeWebSocket {
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
      return;
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
                  id: "thread-unsupported",
                  projectId: "project-1",
                  title: "Unsupported provider thread",
                  modelSelection: {
                    provider: "claudeAgent",
                    model: "claude-sonnet",
                  },
                  runtimeMode: "approval-required",
                  interactionMode: "plan",
                  branch: "main",
                  worktreePath: "/tmp",
                  latestTurn: null,
                  createdAt: "2026-04-07T11:00:00.000Z",
                  updatedAt: "2026-04-07T11:11:00.000Z",
                  archivedAt: null,
                  deletedAt: null,
                  messages: [],
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
}

class InterruptibleThreadFakeWebSocket extends FakeWebSocket {
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
      return;
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
                  title: "Interruptible thread",
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
                    state: "running",
                    requestedAt: "2026-04-07T11:10:00.000Z",
                    startedAt: "2026-04-07T11:10:05.000Z",
                    completedAt: null,
                    assistantMessageId: null,
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
                  ],
                  proposedPlans: [],
                  activities: [],
                  checkpoints: [],
                  session: {
                    status: "running",
                    activeTurnId: "turn-1",
                    updatedAt: "2026-04-07T11:10:10.000Z",
                  },
                },
              ],
            },
          },
        })));
      });
      return;
    }
    super.send(message);
  }
}

class ApprovalThreadFakeWebSocket extends FakeWebSocket {
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
      return;
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
                  title: "Approval thread",
                  modelSelection: {
                    provider: "codex",
                    model: "gpt-5.4",
                  },
                  runtimeMode: "approval-required",
                  interactionMode: "default",
                  branch: "main",
                  worktreePath: "/tmp",
                  latestTurn: {
                    turnId: "turn-1",
                    state: "running",
                    requestedAt: "2026-04-07T11:10:00.000Z",
                    startedAt: "2026-04-07T11:10:05.000Z",
                    completedAt: null,
                    assistantMessageId: null,
                  },
                  createdAt: "2026-04-07T11:00:00.000Z",
                  updatedAt: "2026-04-07T11:11:00.000Z",
                  archivedAt: null,
                  deletedAt: null,
                  messages: [],
                  proposedPlans: [],
                  activities: [
                    {
                      id: "activity-approval-1-open",
                      kind: "approval.requested",
                      summary: "Command approval requested",
                      payload: {
                        requestId: "approval-1",
                        requestKind: "command",
                        detail: "Run git diff --stat",
                      },
                      turnId: "turn-1",
                      createdAt: "2026-04-07T11:10:10.000Z",
                    },
                  ],
                  checkpoints: [],
                  session: {
                    status: "running",
                    activeTurnId: "turn-1",
                    updatedAt: "2026-04-07T11:10:10.000Z",
                  },
                },
              ],
            },
          },
        })));
      });
      return;
    }
    super.send(message);
  }
}

class RollbackThreadFakeWebSocket extends FakeWebSocket {
  constructor(url) {
    super(url);
    this.snapshotValue = {
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
          id: "thread-rollback",
          projectId: "project-1",
          title: "Rollback thread",
          modelSelection: {
            provider: "codex",
            model: "gpt-5.4",
          },
          runtimeMode: "approval-required",
          interactionMode: "default",
          branch: "main",
          worktreePath: "/tmp",
          latestTurn: {
            turnId: "turn-2",
            state: "completed",
            requestedAt: "2026-04-07T11:12:00.000Z",
            startedAt: "2026-04-07T11:12:05.000Z",
            completedAt: "2026-04-07T11:13:00.000Z",
            assistantMessageId: "msg-4",
          },
          createdAt: "2026-04-07T11:00:00.000Z",
          updatedAt: "2026-04-07T11:13:00.000Z",
          archivedAt: null,
          deletedAt: null,
          messages: [
            {
              id: "msg-1",
              role: "user",
              text: "Turn one",
              turnId: "turn-1",
              streaming: false,
              createdAt: "2026-04-07T11:10:00.000Z",
              updatedAt: "2026-04-07T11:10:00.000Z",
            },
            {
              id: "msg-2",
              role: "assistant",
              text: "Done one",
              turnId: "turn-1",
              streaming: false,
              createdAt: "2026-04-07T11:10:05.000Z",
              updatedAt: "2026-04-07T11:11:00.000Z",
            },
            {
              id: "msg-3",
              role: "user",
              text: "Turn two",
              turnId: "turn-2",
              streaming: false,
              createdAt: "2026-04-07T11:12:00.000Z",
              updatedAt: "2026-04-07T11:12:00.000Z",
            },
            {
              id: "msg-4",
              role: "assistant",
              text: "Done two",
              turnId: "turn-2",
              streaming: false,
              createdAt: "2026-04-07T11:12:05.000Z",
              updatedAt: "2026-04-07T11:13:00.000Z",
            },
          ],
          proposedPlans: [],
          activities: [],
          checkpoints: [
            {
              turnId: "turn-1",
              checkpointTurnCount: 1,
              checkpointRef: "refs/t3/checkpoints/thread-rollback/turn/1",
              status: "ready",
              files: [],
              assistantMessageId: "msg-2",
              completedAt: "2026-04-07T11:11:00.000Z",
            },
            {
              turnId: "turn-2",
              checkpointTurnCount: 2,
              checkpointRef: "refs/t3/checkpoints/thread-rollback/turn/2",
              status: "ready",
              files: [],
              assistantMessageId: "msg-4",
              completedAt: "2026-04-07T11:13:00.000Z",
            },
          ],
          session: null,
        },
      ],
    };
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
      return;
    }
    if (parsed.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        this.handlers.get("message")?.(Buffer.from(JSON.stringify({
          _tag: "Exit",
          requestId: parsed.id,
          exit: {
            _tag: "Success",
            value: this.snapshotValue,
          },
        })));
      });
      return;
    }
    if (
      parsed.tag === "orchestration.dispatchCommand"
      && parsed.payload?.type === "thread.checkpoint.revert"
    ) {
      const nextTurnCount = parsed.payload.turnCount;
      this.snapshotValue = {
        ...this.snapshotValue,
        snapshotSequence: 8,
        updatedAt: "2026-04-07T11:15:00.000Z",
        threads: [
          {
            ...this.snapshotValue.threads[0],
            updatedAt: "2026-04-07T11:15:00.000Z",
            latestTurn: {
              turnId: "turn-1",
              state: "completed",
              requestedAt: "2026-04-07T11:10:00.000Z",
              startedAt: "2026-04-07T11:10:05.000Z",
              completedAt: "2026-04-07T11:11:00.000Z",
              assistantMessageId: "msg-2",
            },
            messages: this.snapshotValue.threads[0].messages.filter((message) => message.turnId === "turn-1"),
            checkpoints: this.snapshotValue.threads[0].checkpoints.filter(
              (checkpoint) => checkpoint.checkpointTurnCount <= nextTurnCount
            ),
          },
        ],
      };
      queueMicrotask(() => {
        this.handlers.get("message")?.(Buffer.from(JSON.stringify({
          _tag: "Exit",
          requestId: parsed.id,
          exit: {
            _tag: "Success",
            value: {
              sequence: 8,
            },
          },
        })));
      });
      return;
    }
    super.send(message);
  }
}

class SessionStopThreadFakeWebSocket extends FakeWebSocket {
  constructor(url) {
    super(url);
    this.snapshotValue = {
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
          id: "thread-session-stop",
          projectId: "project-1",
          title: "Session stop thread",
          modelSelection: {
            provider: "codex",
            model: "gpt-5.4",
          },
          runtimeMode: "approval-required",
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
          updatedAt: "2026-04-07T11:13:00.000Z",
          archivedAt: null,
          deletedAt: null,
          messages: [],
          proposedPlans: [],
          activities: [],
          checkpoints: [],
          session: {
            threadId: "thread-session-stop",
            status: "ready",
            providerName: "codex",
            runtimeMode: "approval-required",
            activeTurnId: null,
            lastError: null,
            updatedAt: "2026-04-07T11:13:00.000Z",
          },
        },
      ],
    };
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
      return;
    }
    if (parsed.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        this.handlers.get("message")?.(Buffer.from(JSON.stringify({
          _tag: "Exit",
          requestId: parsed.id,
          exit: {
            _tag: "Success",
            value: this.snapshotValue,
          },
        })));
      });
      return;
    }
    if (
      parsed.tag === "orchestration.dispatchCommand"
      && parsed.payload?.type === "thread.session.stop"
    ) {
      this.snapshotValue = {
        ...this.snapshotValue,
        snapshotSequence: 8,
        updatedAt: "2026-04-07T11:15:00.000Z",
        threads: [
          {
            ...this.snapshotValue.threads[0],
            updatedAt: "2026-04-07T11:15:00.000Z",
            session: {
              ...this.snapshotValue.threads[0].session,
              status: "stopped",
              activeTurnId: null,
              updatedAt: "2026-04-07T11:15:00.000Z",
            },
          },
        ],
      };
      queueMicrotask(() => {
        this.handlers.get("message")?.(Buffer.from(JSON.stringify({
          _tag: "Exit",
          requestId: parsed.id,
          exit: {
            _tag: "Success",
            value: {
              sequence: 8,
            },
          },
        })));
      });
      return;
    }
    super.send(message);
  }
}

class ThreadCreateFakeWebSocket extends FakeWebSocket {
  constructor(url) {
    super(url);
    this.snapshotValue = {
      snapshotSequence: 7,
      updatedAt: "2026-04-07T12:00:00.000Z",
      projects: [
        {
          id: "project-create",
          title: "Project Create",
          workspaceRoot: "/tmp",
          createdAt: "2026-04-07T11:00:00.000Z",
          updatedAt: "2026-04-07T11:30:00.000Z",
          deletedAt: null,
        },
      ],
      threads: [],
    };
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
      return;
    }
    if (parsed.tag === "orchestration.getSnapshot") {
      queueMicrotask(() => {
        this.handlers.get("message")?.(Buffer.from(JSON.stringify({
          _tag: "Exit",
          requestId: parsed.id,
          exit: {
            _tag: "Success",
            value: this.snapshotValue,
          },
        })));
      });
      return;
    }
    if (
      parsed.tag === "orchestration.dispatchCommand"
      && parsed.payload?.type === "thread.create"
    ) {
      this.snapshotValue = {
        ...this.snapshotValue,
        snapshotSequence: 8,
        updatedAt: "2026-04-07T11:35:00.000Z",
        threads: [
          {
            id: parsed.payload.threadId,
            projectId: parsed.payload.projectId,
            title: parsed.payload.title,
            modelSelection: parsed.payload.modelSelection,
            runtimeMode: parsed.payload.runtimeMode,
            interactionMode: parsed.payload.interactionMode,
            branch: parsed.payload.branch,
            worktreePath: parsed.payload.worktreePath,
            latestTurn: null,
            createdAt: parsed.payload.createdAt,
            updatedAt: parsed.payload.createdAt,
            archivedAt: null,
            deletedAt: null,
            messages: [],
            proposedPlans: [],
            activities: [],
            checkpoints: [],
            session: {
              threadId: parsed.payload.threadId,
              status: "ready",
              providerName: "codex",
              runtimeMode: parsed.payload.runtimeMode,
              activeTurnId: null,
              lastError: null,
              updatedAt: parsed.payload.createdAt,
            },
          },
        ],
      };
      queueMicrotask(() => {
        this.handlers.get("message")?.(Buffer.from(JSON.stringify({
          _tag: "Exit",
          requestId: parsed.id,
          exit: {
            _tag: "Success",
            value: {
              sequence: 8,
            },
          },
        })));
      });
      return;
    }
    super.send(message);
  }
}

class UserInputThreadFakeWebSocket extends FakeWebSocket {
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
      return;
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
                  title: "User input thread",
                  modelSelection: {
                    provider: "codex",
                    model: "gpt-5.4",
                  },
                  runtimeMode: "approval-required",
                  interactionMode: "default",
                  branch: "main",
                  worktreePath: "/tmp",
                  latestTurn: {
                    turnId: "turn-1",
                    state: "running",
                    requestedAt: "2026-04-07T11:10:00.000Z",
                    startedAt: "2026-04-07T11:10:05.000Z",
                    completedAt: null,
                    assistantMessageId: null,
                  },
                  createdAt: "2026-04-07T11:00:00.000Z",
                  updatedAt: "2026-04-07T11:11:00.000Z",
                  archivedAt: null,
                  deletedAt: null,
                  messages: [],
                  proposedPlans: [],
                  activities: [
                    {
                      id: "activity-input-1-open",
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
                      turnId: "turn-1",
                      createdAt: "2026-04-07T11:10:10.000Z",
                    },
                  ],
                  checkpoints: [],
                  session: {
                    status: "running",
                    activeTurnId: "turn-1",
                    updatedAt: "2026-04-07T11:10:10.000Z",
                  },
                },
              ],
            },
          },
        })));
      });
      return;
    }
    super.send(message);
  }
}

class FallbackWorkspaceFakeWebSocket extends FakeWebSocket {
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
      return;
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
                  id: "thread-fallback",
                  projectId: "project-1",
                  title: "Fallback workspace thread",
                  modelSelection: {
                    provider: "codex",
                    model: "gpt-5.4",
                  },
                  runtimeMode: "full-access",
                  interactionMode: "default",
                  branch: "main",
                  worktreePath: "/tmp/androdex-missing-worktree",
                  latestTurn: null,
                  createdAt: "2026-04-07T11:00:00.000Z",
                  updatedAt: "2026-04-07T11:11:00.000Z",
                  archivedAt: null,
                  deletedAt: null,
                  messages: [],
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
}

test("runtime method policy recognizes codex-native and T3 read-only targets", () => {
  assert.equal(isCodexNativeRuntimeTarget("codex-native"), true);
  assert.equal(isCodexNativeRuntimeTarget("t3-server"), false);
  assert.equal(isReadOnlyRuntimeTarget("t3-server"), true);
  assert.equal(isReadOnlyRuntimeTarget("codex-native"), false);
});

test("T3 read-only policy allows snapshot/bootstrap-safe read methods", () => {
  assert.equal(
    isRuntimeTargetMethodAllowed({ targetKind: "t3-server", method: "bridge/approval/respond" }),
    true
  );
  assert.equal(
    isRuntimeTargetMethodAllowed({ targetKind: "t3-server", method: "bridge/user-input/respond" }),
    true
  );
  assert.equal(
    isRuntimeTargetMethodAllowed({ targetKind: "t3-server", method: "thread/start" }),
    true
  );
  assert.equal(
    isRuntimeTargetMethodAllowed({ targetKind: "t3-server", method: "thread/list" }),
    true
  );
  assert.equal(
    isRuntimeTargetMethodAllowed({ targetKind: "t3-server", method: "thread/read" }),
    true
  );
  assert.equal(
    isRuntimeTargetMethodAllowed({ targetKind: "t3-server", method: "thread/backgroundTerminals/clean" }),
    true
  );
  assert.equal(
    isRuntimeTargetMethodAllowed({ targetKind: "t3-server", method: "thread/rollback" }),
    true
  );
  assert.equal(
    isRuntimeTargetMethodAllowed({ targetKind: "t3-server", method: "turn/start" }),
    true
  );
  assert.equal(
    isRuntimeTargetMethodAllowed({ targetKind: "t3-server", method: "review/start" }),
    true
  );
  assert.equal(
    isRuntimeTargetMethodAllowed({ targetKind: "t3-server", method: "turn/interrupt" }),
    true
  );
  assert.equal(
    isRuntimeTargetMethodAllowed({ targetKind: "t3-server", method: "model/list" }),
    true
  );
});

test("T3 read-only policy rejects mutating methods with a clear message", () => {
  assert.equal(
    isRuntimeTargetMethodAllowed({ targetKind: "t3-server", method: "thread/fork" }),
    false
  );
  assert.match(
    buildRuntimeTargetMethodRejectionMessage({
      targetKind: "t3-server",
      method: "thread/fork",
    }),
    /does not support "thread\/fork" yet/i
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
      id: "req-thread-fork",
      method: "thread/fork",
      params: { threadId: "thread-123" },
    })),
    /does not support "thread\/fork" yet/i
  );
});

test("T3 runtime adapter dispatches review/start through thread.turn.start for companion-supported threads", async () => {
  FakeWebSocket.instances = [];
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
  assert.equal(
    adapter.send(JSON.stringify({
      id: "req-review-start",
      method: "review/start",
      params: {
        threadId: "thread-123",
        target: {
          type: "baseBranch",
          branch: "main",
        },
      },
    })),
    true
  );

  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));

  const socket = FakeWebSocket.instances.at(-1);
  const dispatchRequest = socket.sentMessages
    .map((message) => JSON.parse(message))
    .find((payload) => payload.tag === "orchestration.dispatchCommand" && payload.payload?.type === "thread.turn.start");
  assert.ok(dispatchRequest);
  assert.equal(dispatchRequest.payload.threadId, "thread-123");
  assert.match(dispatchRequest.payload.message.text, /Review the current branch against base branch main/i);
  assert.equal(dispatchRequest.payload.interactionMode, "default");
  assert.deepEqual(
    responses.find((response) => response.id === "req-review-start"),
    {
      id: "req-review-start",
      result: {
        ok: true,
        started: true,
        sequence: 8,
      },
    }
  );
});

test("T3 runtime adapter dispatches turn/interrupt for companion-supported threads", async () => {
  FakeWebSocket.instances = [];
  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: {
      ANDRODEX_T3_AUTH_MODE: "bootstrap-token",
      ANDRODEX_T3_BASE_DIR: "/tmp/t3-state",
      ANDRODEX_T3_PROTOCOL_VERSION: "2026-04-01",
    },
    WebSocketImpl: InterruptibleThreadFakeWebSocket,
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });
  await adapter.whenReady();
  assert.equal(adapter.send(JSON.stringify({
    id: "req-turn-interrupt",
    method: "turn/interrupt",
    params: {
      threadId: "thread-123",
      turnId: "turn-1",
    },
  })), true);

  await new Promise((resolve) => setTimeout(resolve, 0));

  assert.equal(responses.length, 1);
  assert.equal(responses[0].id, "req-turn-interrupt");
  assert.equal(responses[0].result.ok, true);
  assert.equal(responses[0].result.interrupted, true);
  assert.equal(responses[0].result.sequence, 8);

  const socket = FakeWebSocket.instances.at(-1);
  const dispatched = JSON.parse(socket.sentMessages.at(-1));
  assert.equal(dispatched.tag, "orchestration.dispatchCommand");
  assert.equal(dispatched.payload.type, "thread.turn.interrupt");
  assert.equal(dispatched.payload.threadId, "thread-123");
  assert.equal(dispatched.payload.turnId, "turn-1");
  assert.match(dispatched.payload.commandId, /^[0-9a-f-]{36}$/i);
});

test("T3 runtime adapter dispatches bridge/approval/respond for pending synthetic T3 approvals", async () => {
  FakeWebSocket.instances = [];
  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: {
      ANDRODEX_T3_AUTH_MODE: "bootstrap-token",
      ANDRODEX_T3_BASE_DIR: "/tmp/t3-state",
      ANDRODEX_T3_PROTOCOL_VERSION: "2026-04-01",
    },
    WebSocketImpl: ApprovalThreadFakeWebSocket,
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
  await new Promise((resolve) => setTimeout(resolve, 10));

  assert.equal(adapter.send(JSON.stringify({
    id: "req-approval-respond",
    method: "bridge/approval/respond",
    params: {
      bridgeRequestId: "t3-approval-request:thread-123:approval-1",
      decision: "accept",
    },
  })), true);
  await new Promise((resolve) => setTimeout(resolve, 0));

  const response = responses.find((entry) => entry.id === "req-approval-respond");
  assert.equal(response?.result?.ok, true);
  assert.equal(response?.result?.accepted, true);

  const socket = FakeWebSocket.instances.at(-1);
  const dispatched = JSON.parse(socket.sentMessages.at(-1));
  assert.equal(dispatched.tag, "orchestration.dispatchCommand");
  assert.equal(dispatched.payload.type, "thread.approval.respond");
  assert.equal(dispatched.payload.threadId, "thread-123");
  assert.equal(dispatched.payload.requestId, "approval-1");
  assert.equal(dispatched.payload.decision, "accept");
});

test("T3 runtime adapter dispatches bridge/user-input/respond for pending synthetic T3 user-input requests", async () => {
  FakeWebSocket.instances = [];
  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: {
      ANDRODEX_T3_AUTH_MODE: "bootstrap-token",
      ANDRODEX_T3_BASE_DIR: "/tmp/t3-state",
      ANDRODEX_T3_PROTOCOL_VERSION: "2026-04-01",
    },
    WebSocketImpl: UserInputThreadFakeWebSocket,
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

  assert.equal(adapter.send(JSON.stringify({
    id: "req-user-input-respond",
    method: "bridge/user-input/respond",
    params: {
      bridgeRequestId: "t3-user-input-request:thread-123:user-input-1",
      answers: {
        deploy_target: {
          answers: ["preview"],
        },
      },
    },
  })), true);
  await new Promise((resolve) => setTimeout(resolve, 0));

  const response = responses.find((entry) => entry.id === "req-user-input-respond");
  assert.equal(response?.result?.ok, true);
  assert.equal(response?.result?.answered, true);

  const socket = FakeWebSocket.instances.at(-1);
  const dispatched = JSON.parse(socket.sentMessages.at(-1));
  assert.equal(dispatched.tag, "orchestration.dispatchCommand");
  assert.equal(dispatched.payload.type, "thread.user-input.respond");
  assert.equal(dispatched.payload.threadId, "thread-123");
  assert.equal(dispatched.payload.requestId, "user-input-1");
  assert.deepEqual(dispatched.payload.answers, {
    deploy_target: {
      answers: ["preview"],
    },
  });
});

test("T3 runtime adapter dispatches turn/start for companion-supported threads", async () => {
  FakeWebSocket.instances = [];
  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: {
      ANDRODEX_T3_AUTH_MODE: "bootstrap-token",
      ANDRODEX_T3_BASE_DIR: "/tmp/t3-state",
      ANDRODEX_T3_PROTOCOL_VERSION: "2026-04-01",
    },
    WebSocketImpl: ApprovalThreadFakeWebSocket,
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });
  await adapter.whenReady();
  assert.equal(adapter.send(JSON.stringify({
    id: "req-turn-start",
    method: "turn/start",
    params: {
      threadId: "thread-123",
      input: [
        {
          type: "image",
          url: "data:image/png;base64,AAAA",
        },
        {
          type: "text",
          text: "Plan the rollout",
        },
      ],
      model: "gpt-5.4",
      effort: "high",
      serviceTier: "fast",
      collaborationMode: {
        mode: "plan",
        settings: {
          model: "gpt-5.4",
          reasoning_effort: "high",
          developer_instructions: null,
        },
      },
    },
  })), true);
  let response = null;
  for (let attempt = 0; attempt < 20; attempt += 1) {
    response = responses.find((entry) => entry.id === "req-turn-start") || null;
    if (response) {
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  assert.equal(response?.result?.ok, true);
  assert.equal(response?.result?.started, true);

  const socket = FakeWebSocket.instances.at(-1);
  const dispatches = Array.from(new Map(
    socket.sentMessages
      .map((entry) => JSON.parse(entry))
      .filter((entry) => entry.tag === "orchestration.dispatchCommand")
      .map((entry) => [entry.id, entry])
  ).values());

  assert.deepEqual(dispatches.map((entry) => entry.payload.type), [
    "thread.meta.update",
    "thread.interaction-mode.set",
    "thread.turn.start",
  ]);

  const turnStartDispatch = dispatches[2];
  assert.equal(turnStartDispatch.payload.threadId, "thread-123");
  assert.equal(turnStartDispatch.payload.message.role, "user");
  assert.equal(turnStartDispatch.payload.message.text, "Plan the rollout");
  assert.equal(turnStartDispatch.payload.message.attachments.length, 1);
  assert.equal(turnStartDispatch.payload.message.attachments[0].mimeType, "image/png");
  assert.equal(turnStartDispatch.payload.message.attachments[0].name, "image-1.png");
  assert.equal(turnStartDispatch.payload.modelSelection.provider, "codex");
  assert.equal(turnStartDispatch.payload.modelSelection.model, "gpt-5.4");
  assert.equal(turnStartDispatch.payload.modelSelection.options.reasoningEffort, "high");
  assert.equal(turnStartDispatch.payload.modelSelection.options.fastMode, true);
  assert.equal(turnStartDispatch.payload.interactionMode, "plan");
  assert.equal(turnStartDispatch.payload.titleSeed, "Plan the rollout");
});

test("T3 runtime adapter dispatches thread/rollback for companion-supported threads", async () => {
  FakeWebSocket.instances = [];
  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: {
      ANDRODEX_T3_AUTH_MODE: "bootstrap-token",
      ANDRODEX_T3_BASE_DIR: "/tmp/t3-state",
      ANDRODEX_T3_PROTOCOL_VERSION: "2026-04-01",
    },
    WebSocketImpl: RollbackThreadFakeWebSocket,
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });
  await adapter.whenReady();
  assert.equal(adapter.send(JSON.stringify({
    id: "req-thread-rollback",
    method: "thread/rollback",
    params: {
      threadId: "thread-rollback",
      numTurns: 1,
    },
  })), true);
  let response = null;
  for (let attempt = 0; attempt < 20; attempt += 1) {
    response = responses.find((entry) => entry.id === "req-thread-rollback") || null;
    if (response) {
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 0));
  }

  assert.equal(response?.result?.ok, true);
  assert.equal(response?.result?.thread?.id, "thread-rollback");
  assert.equal(response?.result?.thread?.turns?.length, 1);
  assert.deepEqual(
    response?.result?.thread?.turns?.[0]?.items?.map((item) => item.id),
    ["msg-1", "msg-2"]
  );

  const socket = FakeWebSocket.instances.at(-1);
  const dispatches = Array.from(new Map(
    socket.sentMessages
      .map((entry) => JSON.parse(entry))
      .filter((entry) => entry.tag === "orchestration.dispatchCommand")
      .map((entry) => [entry.id, entry])
  ).values());
  assert.equal(dispatches.length, 1);
  assert.equal(dispatches[0].payload.type, "thread.checkpoint.revert");
  assert.equal(dispatches[0].payload.turnCount, 1);
});

test("T3 runtime adapter dispatches thread/backgroundTerminals/clean for companion-supported threads", async () => {
  FakeWebSocket.instances = [];
  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: {
      ANDRODEX_T3_AUTH_MODE: "bootstrap-token",
      ANDRODEX_T3_BASE_DIR: "/tmp/t3-state",
      ANDRODEX_T3_PROTOCOL_VERSION: "2026-04-01",
    },
    WebSocketImpl: SessionStopThreadFakeWebSocket,
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });
  await adapter.whenReady();
  assert.equal(adapter.send(JSON.stringify({
    id: "req-thread-clean",
    method: "thread/backgroundTerminals/clean",
    params: {
      threadId: "thread-session-stop",
    },
  })), true);
  let response = null;
  for (let attempt = 0; attempt < 20; attempt += 1) {
    response = responses.find((entry) => entry.id === "req-thread-clean") || null;
    if (response) {
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 0));
  }

  assert.equal(response?.result?.ok, true);
  assert.equal(response?.result?.stopped, true);

  const socket = FakeWebSocket.instances.at(-1);
  const dispatches = Array.from(new Map(
    socket.sentMessages
      .map((entry) => JSON.parse(entry))
      .filter((entry) => entry.tag === "orchestration.dispatchCommand")
      .map((entry) => [entry.id, entry])
  ).values());
  assert.equal(dispatches.length, 1);
  assert.equal(dispatches[0].payload.type, "thread.session.stop");
  assert.equal(dispatches[0].payload.threadId, "thread-session-stop");
});

test("T3 runtime adapter dispatches thread/start through thread.create for known project workspaces", async () => {
  FakeWebSocket.instances = [];
  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: {
      ANDRODEX_T3_AUTH_MODE: "bootstrap-token",
      ANDRODEX_T3_BASE_DIR: "/tmp/t3-state",
      ANDRODEX_T3_PROTOCOL_VERSION: "2026-04-01",
    },
    WebSocketImpl: ThreadCreateFakeWebSocket,
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });
  await adapter.whenReady();
  assert.equal(adapter.send(JSON.stringify({
    id: "req-thread-start",
    method: "thread/start",
    params: {
      cwd: "/tmp",
      model: "gpt-5.4",
      sandboxPolicy: {
        type: "workspaceWrite",
      },
    },
  })), true);
 
  let response = null;
  for (let attempt = 0; attempt < 20; attempt += 1) {
    response = responses.find((entry) => entry.id === "req-thread-start") || null;
    if (response) {
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
 
  assert.equal(response?.result?.thread?.cwd, "/tmp");
  assert.equal(response?.result?.thread?.title, "tmp");
  assert.equal(response?.result?.thread?.threadCapabilities?.turnStart?.supported, true);
 
  const socket = FakeWebSocket.instances.at(-1);
  const dispatches = Array.from(new Map(
    socket.sentMessages
      .map((entry) => JSON.parse(entry))
      .filter((entry) => entry.tag === "orchestration.dispatchCommand")
      .map((entry) => [entry.id, entry])
  ).values());
  assert.equal(dispatches.length, 1);
  assert.equal(dispatches[0].payload.type, "thread.create");
  assert.equal(dispatches[0].payload.projectId, "project-create");
  assert.equal(dispatches[0].payload.runtimeMode, "approval-required");
});
test("T3 runtime adapter rejects turn/interrupt when the synchronized thread is no longer interruptible", async () => {
  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: {
      ANDRODEX_T3_AUTH_MODE: "bootstrap-token",
      ANDRODEX_T3_BASE_DIR: "/tmp/t3-state",
      ANDRODEX_T3_PROTOCOL_VERSION: "2026-04-01",
    },
    WebSocketImpl: UnsupportedThreadFakeWebSocket,
  });

  const responses = [];
  adapter.onMessage((message) => {
    responses.push(JSON.parse(message));
  });
  await adapter.whenReady();
  assert.equal(adapter.send(JSON.stringify({
    id: "req-turn-interrupt",
    method: "turn/interrupt",
    params: {
      threadId: "thread-unsupported",
      turnId: "turn-1",
    },
  })), true);

  await new Promise((resolve) => setTimeout(resolve, 0));

  assert.equal(responses.length, 1);
  assert.equal(responses[0].id, "req-turn-interrupt");
  assert.match(
    responses[0].error?.message || "",
    /codex-backed threads|does not support interrupt/i
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
  assert.equal(responses[0].result.thread.threadCapabilities.companionSupportState, "supported");
  assert.equal(responses[0].result.thread.threadCapabilities.liveUpdates.supported, true);
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
  assert.equal(responses[0].result.threadCapabilities.companionSupportState, "supported");
  assert.equal(responses[0].result.threadCapabilities.liveUpdates.supported, true);
});

test("T3 runtime adapter returns explicit capability metadata when thread/resume is gated", async () => {
  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: {
      ANDRODEX_T3_AUTH_MODE: "bootstrap-token",
      ANDRODEX_T3_BASE_DIR: "/tmp/t3-state",
      ANDRODEX_T3_PROTOCOL_VERSION: "2026-04-01",
    },
    WebSocketImpl: UnsupportedThreadFakeWebSocket,
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
      threadId: "thread-unsupported",
    },
  })), true);

  await new Promise((resolve) => setTimeout(resolve, 0));

  assert.equal(responses.length, 1);
  assert.equal(responses[0].id, "req-thread-resume");
  assert.equal(responses[0].result.ok, false);
  assert.equal(responses[0].result.liveUpdatesAttached, false);
  assert.equal(responses[0].result.threadCapabilities.companionSupportState, "unsupported_provider");
  assert.equal(responses[0].result.threadCapabilities.liveUpdates.supported, false);
  assert.match(responses[0].result.reason, /only attaches live updates for codex-backed threads/i);
});

test("T3 runtime adapter keeps supported thread/resume live when project workspace root is used as a fallback", async () => {
  const adapter = createRuntimeAdapter({
    targetKind: "t3-server",
    endpoint: "ws://127.0.0.1:7777",
    env: {
      ANDRODEX_T3_AUTH_MODE: "bootstrap-token",
      ANDRODEX_T3_BASE_DIR: "/tmp/t3-state",
      ANDRODEX_T3_PROTOCOL_VERSION: "2026-04-01",
    },
    WebSocketImpl: FallbackWorkspaceFakeWebSocket,
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
      threadId: "thread-fallback",
    },
  })), true);

  await new Promise((resolve) => setTimeout(resolve, 0));

  assert.equal(responses.length, 1);
  assert.equal(responses[0].id, "req-thread-resume");
  assert.equal(responses[0].result.ok, true);
  assert.equal(responses[0].result.resumed, true);
  assert.equal(responses[0].result.liveUpdatesAttached, true);
  assert.equal(responses[0].result.threadCapabilities.companionSupportState, "supported");
  assert.equal(responses[0].result.threadCapabilities.workspacePath, "/tmp");
  assert.equal(responses[0].result.threadCapabilities.workspacePathSource, "project_workspace_root");
  assert.equal(responses[0].result.threadCapabilities.workspaceFallbackUsed, true);
  assert.equal(responses[0].result.threadCapabilities.recordedWorktreePath, "/tmp/androdex-missing-worktree");
  assert.equal(responses[0].result.threadCapabilities.recordedWorktreeAvailable, false);
  assert.equal(responses[0].result.threadCapabilities.projectWorkspaceRoot, "/tmp");
  assert.equal(responses[0].result.threadCapabilities.projectWorkspaceRootAvailable, true);
});
