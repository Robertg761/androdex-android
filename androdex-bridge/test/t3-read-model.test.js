const test = require("node:test");
const assert = require("node:assert/strict");

const {
  applyT3EventsToSnapshot,
  buildT3ThreadListResult,
  buildT3ThreadReadResult,
} = require("../src/runtime/t3-read-model");

const snapshot = {
  snapshotSequence: 12,
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
      id: "thread-codex",
      projectId: "project-1",
      title: "Fix bug",
      modelSelection: {
        provider: "codex",
        model: "gpt-5.4",
      },
      runtimeMode: "full-access",
      interactionMode: "default",
      branch: "main",
      worktreePath: "/tmp",
      latestTurn: {
        turnId: "turn-2",
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
          text: "Please fix it",
          turnId: "turn-2",
          streaming: false,
          createdAt: "2026-04-07T11:10:00.000Z",
          updatedAt: "2026-04-07T11:10:00.000Z",
        },
        {
          id: "msg-2",
          role: "assistant",
          text: "Done",
          turnId: "turn-2",
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
    {
      id: "thread-unsupported",
      projectId: "project-1",
      title: "Claude thread",
      modelSelection: {
        provider: "claudeAgent",
        model: "claude-opus",
      },
      runtimeMode: "approval-required",
      interactionMode: "plan",
      branch: null,
      worktreePath: "/tmp",
      latestTurn: null,
      createdAt: "2026-04-07T10:00:00.000Z",
      updatedAt: "2026-04-07T10:30:00.000Z",
      archivedAt: null,
      deletedAt: null,
      messages: [],
      proposedPlans: [],
      activities: [],
      checkpoints: [],
      session: null,
    },
  ],
};

test("buildT3ThreadListResult maps snapshot threads into the Android summary contract", () => {
  const result = buildT3ThreadListResult({
    snapshot,
    limit: 10,
  });

  assert.equal(result.data.length, 2);
  assert.equal(result.data[0].id, "thread-codex");
  assert.equal(result.data[0].title, "Fix bug");
  assert.equal(result.data[0].cwd, "/tmp");
  assert.equal(result.data[0].model, "gpt-5.4");
  assert.equal(result.data[0].threadCapabilities.companionSupportState, "supported");
  assert.equal(result.data[0].threadCapabilities.liveUpdates.supported, true);
  assert.equal(result.data[0].threadCapabilities.turnStart.supported, false);
  assert.equal(result.data[0].threadCapabilities.toolInputResponses.supported, false);
  assert.equal(result.data[0].backendProvider, "codex");
  assert.match(result.data[1].preview, /unsupported t3 provider/i);
  assert.equal(result.data[1].threadCapabilities.companionSupportState, "unsupported_provider");
  assert.equal(result.data[1].threadCapabilities.liveUpdates.supported, false);
  assert.match(result.data[1].threadCapabilities.liveUpdates.reason, /only attaches live updates for codex-backed threads/i);
  assert.equal(result.nextCursor, null);
});

test("buildT3ThreadListResult applies cursor pagination", () => {
  const result = buildT3ThreadListResult({
    snapshot,
    limit: 1,
    cursor: "1",
  });

  assert.equal(result.data.length, 1);
  assert.equal(result.data[0].id, "thread-unsupported");
  assert.equal(result.nextCursor, null);
});

test("buildT3ThreadReadResult synthesizes turns/items that Android can decode", () => {
  const result = buildT3ThreadReadResult({
    snapshot,
    threadId: "thread-codex",
  });

  assert.equal(result.thread.id, "thread-codex");
  assert.equal(result.thread.threadCapabilities.companionSupportState, "supported");
  assert.equal(result.thread.turns.length, 1);
  assert.equal(result.thread.turns[0].id, "turn-2");
  assert.equal(result.thread.turns[0].status, "completed");
  assert.equal(result.thread.turns[0].items[0].type, "user_message");
  assert.equal(result.thread.turns[0].items[1].type, "assistant_message");
});

test("buildT3ThreadReadResult exposes explicit capability metadata for orphaned supported threads", () => {
  const orphanedSnapshot = {
    ...snapshot,
    threads: [
      {
        ...snapshot.threads[0],
        id: "thread-orphaned",
        worktreePath: "/tmp/androdex-missing-worktree",
      },
    ],
  };

  const result = buildT3ThreadReadResult({
    snapshot: orphanedSnapshot,
    threadId: "thread-orphaned",
  });

  assert.equal(result.thread.cwd, "/tmp");
  assert.match(result.thread.preview, /project workspace root fallback/i);
  assert.equal(result.thread.threadCapabilities.companionSupportState, "supported");
  assert.equal(result.thread.threadCapabilities.liveUpdates.supported, true);
  assert.equal(result.thread.threadCapabilities.workspacePath, "/tmp");
  assert.equal(result.thread.threadCapabilities.workspacePathSource, "project_workspace_root");
  assert.equal(result.thread.threadCapabilities.workspaceFallbackUsed, true);
  assert.equal(result.thread.threadCapabilities.recordedWorktreePath, "/tmp/androdex-missing-worktree");
  assert.equal(result.thread.threadCapabilities.recordedWorktreeAvailable, false);
  assert.equal(result.thread.threadCapabilities.projectWorkspaceRoot, "/tmp");
  assert.equal(result.thread.threadCapabilities.projectWorkspaceRootAvailable, true);
});

test("buildT3ThreadReadResult preserves image attachments as structured content", () => {
  const attachmentSnapshot = {
    ...snapshot,
    threads: [
      {
        ...snapshot.threads[0],
        messages: [
          {
            id: "msg-1",
            role: "user",
            text: "Please inspect this image",
            turnId: "turn-2",
            streaming: false,
            createdAt: "2026-04-07T11:10:00.000Z",
            updatedAt: "2026-04-07T11:10:00.000Z",
            attachments: [
              {
                name: "screenshot.png",
                url: "data:image/png;base64,AAAA",
              },
            ],
          },
        ],
      },
    ],
  };

  const result = buildT3ThreadReadResult({
    snapshot: attachmentSnapshot,
    threadId: "thread-codex",
  });

  assert.deepEqual(result.thread.turns[0].items[0].content, [
    {
      type: "input_text",
      text: "Please inspect this image",
    },
    {
      type: "image",
      url: "data:image/png;base64,AAAA",
    },
  ]);
});

test("buildT3ThreadReadResult throws when the requested thread is missing", () => {
  assert.throws(
    () => buildT3ThreadReadResult({
      snapshot,
      threadId: "missing-thread",
    }),
    /T3 thread not found/i
  );
});

test("applyT3EventsToSnapshot infers the latest turn from messages when session-set clears activeTurnId", () => {
  const snapshotWithoutLatestTurn = {
    ...snapshot,
    threads: [
      {
        ...snapshot.threads[0],
        latestTurn: null,
        session: null,
      },
    ],
  };

  const result = applyT3EventsToSnapshot({
    snapshot: snapshotWithoutLatestTurn,
    events: [
      {
        sequence: 13,
        eventId: "event-13",
        aggregateKind: "thread",
        aggregateId: "thread-codex",
        occurredAt: "2026-04-07T12:13:00.000Z",
        commandId: null,
        causationEventId: null,
        correlationId: null,
        metadata: {},
        type: "thread.session-set",
        payload: {
          threadId: "thread-codex",
          session: {
            threadId: "thread-codex",
            status: "ready",
            providerName: "codex",
            runtimeMode: "full-access",
            activeTurnId: null,
            lastError: null,
            updatedAt: "2026-04-07T12:13:00.000Z",
          },
        },
      },
    ],
  });

  assert.equal(result.snapshot.threads[0].latestTurn.turnId, "turn-2");
  assert.equal(result.snapshot.threads[0].latestTurn.state, "completed");
});
