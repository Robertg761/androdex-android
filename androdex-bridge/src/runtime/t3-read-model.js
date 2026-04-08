// FILE: runtime/t3-read-model.js
// Purpose: Translates the T3 orchestration read model into the existing Androdex thread/list and thread/read contract.
// Layer: CLI helper
// Exports: T3 snapshot translation helpers
// Depends on: fs, path

const fs = require("fs");
const path = require("path");

function buildT3ThreadListResult({
  snapshot = null,
  limit = 40,
  cursor = null,
} = {}) {
  const visibleThreads = listVisibleThreads(snapshot);
  const offset = Math.max(0, parseCursorOffset(cursor));
  const slicedThreads = visibleThreads.slice(offset, offset + normalizeLimit(limit));
  const nextOffset = offset + slicedThreads.length;
  return {
    data: slicedThreads.map((entry) => entry.summary),
    nextCursor: nextOffset < visibleThreads.length ? String(nextOffset) : null,
  };
}

function buildT3ThreadReadResult({
  snapshot = null,
  threadId = "",
} = {}) {
  const visibleThreads = listVisibleThreads(snapshot);
  const selectedThread = visibleThreads.find((entry) => entry.thread.id === normalizeNonEmptyString(threadId));
  if (!selectedThread) {
    throw new Error(`T3 thread not found: ${normalizeNonEmptyString(threadId) || "<unknown>"}`);
  }

  return {
    thread: {
      ...selectedThread.summary,
      turns: buildThreadTurns(selectedThread.thread),
    },
  };
}

function buildT3ThreadRollbackResult({
  snapshot = null,
  threadId = "",
  numTurns = 1,
  occurredAt = null,
} = {}) {
  const normalizedThreadId = normalizeNonEmptyString(threadId);
  const rollbackTurns = normalizeRollbackTurnCount(numTurns);
  const nextSnapshot = cloneT3Snapshot(snapshot);
  const thread = findRecord(nextSnapshot.threads, "id", normalizedThreadId);
  if (!thread) {
    throw new Error(`T3 thread not found: ${normalizedThreadId || "<unknown>"}`);
  }

  const currentTurnCount = getThreadCheckpointTurnCount(thread);
  if (currentTurnCount <= 0) {
    throw new Error("This T3 thread has no checkpoints available to roll back yet.");
  }

  const targetTurnCount = Math.max(0, currentTurnCount - rollbackTurns);
  applyThreadRevertedState(thread, {
    turnCount: targetTurnCount,
    updatedAt: normalizeNonEmptyString(occurredAt) || thread.updatedAt || null,
  });

  return {
    currentTurnCount,
    targetTurnCount,
    result: buildT3ThreadReadResult({
      snapshot: nextSnapshot,
      threadId: normalizedThreadId,
    }),
    snapshot: nextSnapshot,
  };
}

function createEmptyT3Snapshot() {
  return {
    snapshotSequence: 0,
    updatedAt: null,
    projects: [],
    threads: [],
  };
}

function applyT3EventsToSnapshot({
  snapshot = null,
  events = [],
} = {}) {
  const nextSnapshot = cloneT3Snapshot(snapshot);
  let duplicateCount = 0;
  let appliedCount = 0;
  let lastSequence = normalizeSequenceNumber(nextSnapshot.snapshotSequence);

  const orderedEvents = [...(Array.isArray(events) ? events : [])]
    .filter((event) => event && typeof event === "object")
    .sort((left, right) => normalizeSequenceNumber(left?.sequence) - normalizeSequenceNumber(right?.sequence));

  for (const event of orderedEvents) {
    const sequence = normalizeSequenceNumber(event?.sequence);
    if (!Number.isFinite(sequence) || sequence <= lastSequence) {
      duplicateCount += 1;
      continue;
    }

    applySingleT3Event(nextSnapshot, event);
    lastSequence = sequence;
    nextSnapshot.snapshotSequence = sequence;
    nextSnapshot.updatedAt = normalizeNonEmptyString(event?.occurredAt)
      || normalizeNonEmptyString(nextSnapshot.updatedAt)
      || null;
    appliedCount += 1;
  }

  return {
    appliedCount,
    duplicateCount,
    lastSequence,
    snapshot: nextSnapshot,
  };
}

function listVisibleThreads(snapshot) {
  const projectsById = buildProjectMap(snapshot?.projects);
  const threads = Array.isArray(snapshot?.threads) ? snapshot.threads : [];

  return threads
    .filter((thread) => thread && typeof thread === "object" && !thread.deletedAt)
    .map((thread) => {
      const project = projectsById.get(normalizeNonEmptyString(thread.projectId)) || null;
      return {
        project,
        summary: buildThreadSummary(thread, project),
        thread,
      };
    })
    .sort((left, right) => {
      const leftUpdatedAt = timestampOrZero(left.thread.updatedAt || left.thread.createdAt);
      const rightUpdatedAt = timestampOrZero(right.thread.updatedAt || right.thread.createdAt);
      return rightUpdatedAt - leftUpdatedAt;
    });
}

function cloneT3Snapshot(snapshot) {
  const normalized = snapshot && typeof snapshot === "object"
    ? snapshot
    : createEmptyT3Snapshot();
  return {
    snapshotSequence: normalizeSequenceNumber(normalized.snapshotSequence),
    updatedAt: normalizeNonEmptyString(normalized.updatedAt) || null,
    projects: (Array.isArray(normalized.projects) ? normalized.projects : []).map((project) => ({
      ...project,
    })),
    threads: (Array.isArray(normalized.threads) ? normalized.threads : []).map((thread) => cloneThread(thread)),
  };
}

function cloneThread(thread) {
  return {
    ...thread,
    latestTurn: thread?.latestTurn ? { ...thread.latestTurn } : null,
    messages: (Array.isArray(thread?.messages) ? thread.messages : []).map((message) => ({
      ...message,
      attachments: Array.isArray(message?.attachments)
        ? message.attachments.map((attachment) => ({ ...attachment }))
        : [],
    })),
    proposedPlans: (Array.isArray(thread?.proposedPlans) ? thread.proposedPlans : []).map((plan) => ({
      ...plan,
    })),
    activities: (Array.isArray(thread?.activities) ? thread.activities : []).map((activity) => ({
      ...activity,
    })),
    checkpoints: (Array.isArray(thread?.checkpoints) ? thread.checkpoints : []).map((checkpoint) => ({
      ...checkpoint,
      files: Array.isArray(checkpoint?.files)
        ? checkpoint.files.map((file) => ({ ...file }))
        : [],
    })),
    session: thread?.session ? { ...thread.session } : null,
  };
}

function applySingleT3Event(snapshot, event) {
  const eventType = normalizeNonEmptyString(event?.type);
  const payload = event?.payload && typeof event.payload === "object" ? event.payload : {};

  if (eventType.startsWith("project.")) {
    applyProjectEvent(snapshot, eventType, payload);
    return;
  }
  if (eventType.startsWith("thread.")) {
    applyThreadEvent(snapshot, eventType, payload, event);
  }
}

function applyProjectEvent(snapshot, eventType, payload) {
  if (eventType === "project.created") {
    upsertRecord(snapshot.projects, "id", {
      id: normalizeNonEmptyString(payload.projectId),
      title: normalizeNonEmptyString(payload.title) || "Project",
      workspaceRoot: normalizeNonEmptyString(payload.workspaceRoot) || null,
      defaultModelSelection: payload.defaultModelSelection ?? null,
      scripts: Array.isArray(payload.scripts) ? payload.scripts.map((script) => ({ ...script })) : [],
      createdAt: normalizeNonEmptyString(payload.createdAt) || null,
      updatedAt: normalizeNonEmptyString(payload.updatedAt) || normalizeNonEmptyString(payload.createdAt) || null,
      deletedAt: null,
    });
    return;
  }

  const project = findRecord(snapshot.projects, "id", payload.projectId);
  if (!project) {
    return;
  }

  if (eventType === "project.meta-updated") {
    if (Object.prototype.hasOwnProperty.call(payload, "title")) {
      project.title = normalizeNonEmptyString(payload.title) || project.title;
    }
    if (Object.prototype.hasOwnProperty.call(payload, "workspaceRoot")) {
      project.workspaceRoot = normalizeNonEmptyString(payload.workspaceRoot) || null;
    }
    if (Object.prototype.hasOwnProperty.call(payload, "defaultModelSelection")) {
      project.defaultModelSelection = payload.defaultModelSelection ?? null;
    }
    if (Object.prototype.hasOwnProperty.call(payload, "scripts")) {
      project.scripts = Array.isArray(payload.scripts) ? payload.scripts.map((script) => ({ ...script })) : [];
    }
    project.updatedAt = normalizeNonEmptyString(payload.updatedAt) || project.updatedAt || null;
    return;
  }

  if (eventType === "project.deleted") {
    project.deletedAt = normalizeNonEmptyString(payload.deletedAt) || project.deletedAt || null;
  }
}

function applyThreadEvent(snapshot, eventType, payload, event) {
  if (eventType === "thread.created") {
    upsertRecord(snapshot.threads, "id", createThreadFromPayload(payload));
    return;
  }

  const thread = findRecord(snapshot.threads, "id", payload.threadId);
  if (!thread) {
    return;
  }

  if (eventType === "thread.deleted") {
    thread.deletedAt = normalizeNonEmptyString(payload.deletedAt) || thread.deletedAt || null;
    return;
  }
  if (eventType === "thread.archived") {
    thread.archivedAt = normalizeNonEmptyString(payload.archivedAt) || thread.archivedAt || null;
    thread.updatedAt = normalizeNonEmptyString(payload.updatedAt) || thread.updatedAt || null;
    return;
  }
  if (eventType === "thread.unarchived") {
    thread.archivedAt = null;
    thread.updatedAt = normalizeNonEmptyString(payload.updatedAt) || thread.updatedAt || null;
    return;
  }
  if (eventType === "thread.meta-updated") {
    if (Object.prototype.hasOwnProperty.call(payload, "title")) {
      thread.title = normalizeNonEmptyString(payload.title) || thread.title;
    }
    if (Object.prototype.hasOwnProperty.call(payload, "modelSelection")) {
      thread.modelSelection = payload.modelSelection ?? thread.modelSelection;
    }
    if (Object.prototype.hasOwnProperty.call(payload, "branch")) {
      thread.branch = normalizeNonEmptyString(payload.branch) || null;
    }
    if (Object.prototype.hasOwnProperty.call(payload, "worktreePath")) {
      thread.worktreePath = normalizeNonEmptyString(payload.worktreePath) || null;
    }
    thread.updatedAt = normalizeNonEmptyString(payload.updatedAt) || thread.updatedAt || null;
    return;
  }
  if (eventType === "thread.runtime-mode-set") {
    thread.runtimeMode = normalizeNonEmptyString(payload.runtimeMode) || thread.runtimeMode;
    thread.updatedAt = normalizeNonEmptyString(payload.updatedAt) || thread.updatedAt || null;
    syncLatestTurnFromSession(thread, thread.session, payload.updatedAt || event?.occurredAt);
    return;
  }
  if (eventType === "thread.interaction-mode-set") {
    thread.interactionMode = normalizeNonEmptyString(payload.interactionMode) || thread.interactionMode;
    thread.updatedAt = normalizeNonEmptyString(payload.updatedAt) || thread.updatedAt || null;
    return;
  }
  if (eventType === "thread.message-sent") {
    upsertRecord(thread.messages, "id", {
      id: normalizeNonEmptyString(payload.messageId),
      role: normalizeNonEmptyString(payload.role) || "system",
      text: typeof payload.text === "string" ? payload.text : "",
      attachments: Array.isArray(payload.attachments)
        ? payload.attachments.map((attachment) => ({ ...attachment }))
        : [],
      turnId: normalizeNonEmptyString(payload.turnId) || null,
      streaming: Boolean(payload.streaming),
      createdAt: normalizeNonEmptyString(payload.createdAt) || null,
      updatedAt: normalizeNonEmptyString(payload.updatedAt) || normalizeNonEmptyString(payload.createdAt) || null,
    });
    thread.updatedAt = normalizeNonEmptyString(payload.updatedAt)
      || normalizeNonEmptyString(payload.createdAt)
      || thread.updatedAt
      || null;
    if (normalizeNonEmptyString(payload.role) === "assistant") {
      syncAssistantLatestTurnHint(thread, payload);
    }
    return;
  }
  if (eventType === "thread.turn-start-requested") {
    if (payload.modelSelection) {
      thread.modelSelection = payload.modelSelection;
    }
    thread.runtimeMode = normalizeNonEmptyString(payload.runtimeMode) || thread.runtimeMode;
    thread.interactionMode = normalizeNonEmptyString(payload.interactionMode) || thread.interactionMode;
    thread.updatedAt = normalizeNonEmptyString(payload.createdAt) || thread.updatedAt || null;
    return;
  }
  if (eventType === "thread.turn-interrupt-requested") {
    const turnId = normalizeNonEmptyString(payload.turnId);
    if (thread.latestTurn && (!turnId || normalizeNonEmptyString(thread.latestTurn.turnId) === turnId)) {
      thread.latestTurn.state = "interrupted";
      thread.latestTurn.completedAt = normalizeNonEmptyString(payload.createdAt) || thread.latestTurn.completedAt || null;
    }
    thread.updatedAt = normalizeNonEmptyString(payload.createdAt) || thread.updatedAt || null;
    return;
  }
  if (eventType === "thread.session-set") {
    thread.session = payload.session ? { ...payload.session } : null;
    thread.updatedAt = normalizeNonEmptyString(payload.session?.updatedAt) || thread.updatedAt || null;
    syncLatestTurnFromSession(thread, payload.session, event?.occurredAt);
    return;
  }
  if (eventType === "thread.proposed-plan-upserted") {
    upsertRecord(thread.proposedPlans, "id", {
      ...payload.proposedPlan,
    });
    return;
  }
  if (eventType === "thread.turn-diff-completed") {
    upsertRecord(thread.checkpoints, "turnId", {
      turnId: normalizeNonEmptyString(payload.turnId),
      checkpointTurnCount: normalizeSequenceNumber(payload.checkpointTurnCount),
      checkpointRef: normalizeNonEmptyString(payload.checkpointRef) || null,
      status: normalizeNonEmptyString(payload.status) || null,
      files: Array.isArray(payload.files) ? payload.files.map((file) => ({ ...file })) : [],
      assistantMessageId: normalizeNonEmptyString(payload.assistantMessageId) || null,
      completedAt: normalizeNonEmptyString(payload.completedAt) || null,
    });
    if (thread.latestTurn && normalizeNonEmptyString(thread.latestTurn.turnId) === normalizeNonEmptyString(payload.turnId)) {
      thread.latestTurn.state = "completed";
      thread.latestTurn.completedAt = normalizeNonEmptyString(payload.completedAt) || thread.latestTurn.completedAt || null;
      thread.latestTurn.assistantMessageId = normalizeNonEmptyString(payload.assistantMessageId) || thread.latestTurn.assistantMessageId || null;
    }
    thread.updatedAt = normalizeNonEmptyString(payload.completedAt) || thread.updatedAt || null;
    return;
  }
  if (eventType === "thread.activity-appended") {
    upsertRecord(thread.activities, "id", {
      ...payload.activity,
      sequence: Number.isFinite(payload.activity?.sequence)
        ? payload.activity.sequence
        : normalizeSequenceNumber(event?.sequence),
    });
    thread.updatedAt = normalizeNonEmptyString(payload.activity?.createdAt) || thread.updatedAt || null;
    return;
  }
  if (eventType === "thread.reverted") {
    applyThreadRevertedState(thread, {
      turnCount: normalizeSequenceNumber(payload.turnCount),
      updatedAt: normalizeNonEmptyString(event?.occurredAt) || thread.updatedAt || null,
    });
    return;
  }

  if (
    eventType === "thread.approval-response-requested"
    || eventType === "thread.user-input-response-requested"
    || eventType === "thread.checkpoint-revert-requested"
    || eventType === "thread.session-stop-requested"
  ) {
    thread.updatedAt = normalizeNonEmptyString(payload.createdAt) || normalizeNonEmptyString(event?.occurredAt) || thread.updatedAt || null;
  }
}

function createThreadFromPayload(payload) {
  return {
    id: normalizeNonEmptyString(payload.threadId),
    projectId: normalizeNonEmptyString(payload.projectId),
    title: normalizeNonEmptyString(payload.title) || "Conversation",
    modelSelection: payload.modelSelection ?? null,
    runtimeMode: normalizeNonEmptyString(payload.runtimeMode) || "full-access",
    interactionMode: normalizeNonEmptyString(payload.interactionMode) || "default",
    branch: normalizeNonEmptyString(payload.branch) || null,
    worktreePath: normalizeNonEmptyString(payload.worktreePath) || null,
    latestTurn: null,
    createdAt: normalizeNonEmptyString(payload.createdAt) || null,
    updatedAt: normalizeNonEmptyString(payload.updatedAt) || normalizeNonEmptyString(payload.createdAt) || null,
    archivedAt: null,
    deletedAt: null,
    messages: [],
    proposedPlans: [],
    activities: [],
    checkpoints: [],
    session: null,
  };
}

function syncAssistantLatestTurnHint(thread, payload) {
  const turnId = normalizeNonEmptyString(payload.turnId);
  if (!turnId) {
    return;
  }
  if (!thread.latestTurn || normalizeNonEmptyString(thread.latestTurn.turnId) !== turnId) {
    thread.latestTurn = {
      turnId,
      state: "completed",
      requestedAt: normalizeNonEmptyString(payload.createdAt) || thread.updatedAt || null,
      startedAt: normalizeNonEmptyString(payload.createdAt) || null,
      completedAt: normalizeNonEmptyString(payload.updatedAt) || normalizeNonEmptyString(payload.createdAt) || null,
      assistantMessageId: normalizeNonEmptyString(payload.messageId) || null,
    };
    return;
  }

  thread.latestTurn.assistantMessageId = normalizeNonEmptyString(payload.messageId) || thread.latestTurn.assistantMessageId || null;
}

function syncLatestTurnFromSession(thread, session, occurredAt) {
  if (!session || typeof session !== "object") {
    return;
  }

  const normalizedStatus = normalizeNonEmptyString(session.status).toLowerCase();
  const activeTurnId = normalizeNonEmptyString(session.activeTurnId);
  const existingLatestTurn = thread.latestTurn && typeof thread.latestTurn === "object"
    ? { ...thread.latestTurn }
    : null;
  const anchorTurnId = activeTurnId
    || normalizeNonEmptyString(existingLatestTurn?.turnId)
    || findMostRecentTurnId(thread.messages);
  if (!anchorTurnId) {
    if (normalizedStatus === "idle" || normalizedStatus === "ready" || normalizedStatus === "stopped") {
      thread.latestTurn = null;
      return;
    }
    thread.latestTurn = existingLatestTurn;
    return;
  }

  const requestedAt = normalizeNonEmptyString(existingLatestTurn?.requestedAt)
    || findTurnRequestedAt(thread.messages, anchorTurnId)
    || normalizeNonEmptyString(session.updatedAt)
    || normalizeNonEmptyString(occurredAt)
    || null;
  const nextState = mapSessionStatusToLatestTurnState(normalizedStatus, existingLatestTurn?.state);
  const startedAt = normalizeNonEmptyString(existingLatestTurn?.startedAt)
    || (nextState === "running" || nextState === "completed" || nextState === "interrupted" || nextState === "error"
      ? normalizeNonEmptyString(session.updatedAt) || normalizeNonEmptyString(occurredAt) || null
      : null);
  const completedAt = nextState === "running"
    ? null
    : normalizeNonEmptyString(session.updatedAt)
      || normalizeNonEmptyString(existingLatestTurn?.completedAt)
      || normalizeNonEmptyString(occurredAt)
      || null;

  thread.latestTurn = {
    turnId: anchorTurnId,
    state: nextState,
    requestedAt,
    startedAt,
    completedAt,
    assistantMessageId: findLatestAssistantMessageId(thread.messages, anchorTurnId)
      || normalizeNonEmptyString(existingLatestTurn?.assistantMessageId)
      || null,
    ...(existingLatestTurn?.sourceProposedPlan ? { sourceProposedPlan: existingLatestTurn.sourceProposedPlan } : {}),
  };
}

function mapSessionStatusToLatestTurnState(status, existingState) {
  if (status === "starting" || status === "running") {
    return "running";
  }
  if (status === "interrupted") {
    return "interrupted";
  }
  if (status === "error") {
    return "error";
  }
  if (existingState === "interrupted" || existingState === "error") {
    return existingState;
  }
  return "completed";
}

function findTurnRequestedAt(messages, turnId) {
  const normalizedTurnId = normalizeNonEmptyString(turnId);
  if (!normalizedTurnId) {
    return null;
  }
  const orderedMessages = [...(Array.isArray(messages) ? messages : [])]
    .filter((message) => normalizeNonEmptyString(message?.turnId) === normalizedTurnId)
    .sort((left, right) => timestampOrZero(left?.createdAt || left?.updatedAt) - timestampOrZero(right?.createdAt || right?.updatedAt));
  return normalizeNonEmptyString(orderedMessages[0]?.createdAt)
    || normalizeNonEmptyString(orderedMessages[0]?.updatedAt)
    || null;
}

function findLatestAssistantMessageId(messages, turnId) {
  const normalizedTurnId = normalizeNonEmptyString(turnId);
  if (!normalizedTurnId) {
    return null;
  }
  const orderedMessages = [...(Array.isArray(messages) ? messages : [])]
    .filter((message) =>
      normalizeNonEmptyString(message?.turnId) === normalizedTurnId
      && normalizeNonEmptyString(message?.role) === "assistant")
    .sort((left, right) => timestampOrZero(right?.updatedAt || right?.createdAt) - timestampOrZero(left?.updatedAt || left?.createdAt));
  return normalizeNonEmptyString(orderedMessages[0]?.id) || null;
}

function findMostRecentTurnId(messages) {
  const orderedMessages = [...(Array.isArray(messages) ? messages : [])]
    .sort((left, right) => timestampOrZero(right?.updatedAt || right?.createdAt) - timestampOrZero(left?.updatedAt || left?.createdAt));
  for (const message of orderedMessages) {
    const turnId = normalizeNonEmptyString(message?.turnId);
    if (turnId) {
      return turnId;
    }
  }
  return "";
}

function upsertRecord(records, key, nextRecord) {
  const normalizedKeyValue = normalizeNonEmptyString(nextRecord?.[key]);
  if (!normalizedKeyValue) {
    return;
  }
  const existingIndex = records.findIndex((record) => normalizeNonEmptyString(record?.[key]) === normalizedKeyValue);
  if (existingIndex >= 0) {
    records[existingIndex] = {
      ...records[existingIndex],
      ...nextRecord,
    };
    return;
  }
  records.push(nextRecord);
}

function findRecord(records, key, keyValue) {
  const normalizedKeyValue = normalizeNonEmptyString(keyValue);
  if (!normalizedKeyValue) {
    return null;
  }
  return records.find((record) => normalizeNonEmptyString(record?.[key]) === normalizedKeyValue) || null;
}

function buildProjectMap(projects) {
  const projectsById = new Map();
  for (const project of Array.isArray(projects) ? projects : []) {
    const projectId = normalizeNonEmptyString(project?.id);
    if (!projectId) {
      continue;
    }
    projectsById.set(projectId, project);
  }
  return projectsById;
}

function buildThreadSummary(thread, project) {
  const workspace = resolveThreadWorkspace(thread, project);
  const provider = normalizeNonEmptyString(thread?.modelSelection?.provider);
  const threadCapabilities = describeT3ThreadCapabilities({
    thread,
    project,
  });
  const preview = buildThreadPreview(thread, provider, workspace);
  return {
    id: normalizeNonEmptyString(thread?.id),
    title: normalizeNonEmptyString(thread?.title) || "Conversation",
    preview,
    cwd: workspace.path,
    createdAt: normalizeNonEmptyString(thread?.createdAt) || null,
    updatedAt: normalizeNonEmptyString(thread?.updatedAt) || normalizeNonEmptyString(thread?.createdAt) || null,
    model: normalizeNonEmptyString(thread?.modelSelection?.model) || null,
    backendProvider: threadCapabilities.backendProvider,
    threadCapabilities,
  };
}

function buildThreadPreview(thread, provider, workspace) {
  const notices = [];
  if (provider && provider !== "codex") {
    notices.push(`Unsupported T3 provider: ${provider}`);
  }
  if (workspace.fallbackToProjectRoot) {
    notices.push("Using project workspace root fallback");
  } else if (workspace.path && !workspace.pathAvailable) {
    notices.push("Workspace unavailable locally");
  }
  if (thread?.archivedAt) {
    notices.push("Archived");
  }

  const basePreview = latestMessagePreview(thread);
  return [...notices, basePreview].filter(Boolean).join(" | ") || null;
}

function latestMessagePreview(thread) {
  const messages = Array.isArray(thread?.messages) ? thread.messages : [];
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const preview = composeMessageText(messages[index]);
    if (preview) {
      return preview;
    }
  }
  return "";
}

function resolveThreadWorkspace(thread, project) {
  const worktreePath = normalizeNonEmptyString(thread?.worktreePath) || null;
  const projectWorkspaceRoot = normalizeNonEmptyString(project?.workspaceRoot) || null;
  const worktreePathAvailable = worktreePath ? pathExists(worktreePath) : false;
  const projectWorkspaceRootAvailable = projectWorkspaceRoot ? pathExists(projectWorkspaceRoot) : false;

  if (worktreePath && worktreePathAvailable) {
    return {
      path: worktreePath,
      pathAvailable: true,
      pathSource: "worktree_path",
      worktreePath,
      worktreePathAvailable,
      projectWorkspaceRoot,
      projectWorkspaceRootAvailable,
      fallbackToProjectRoot: false,
    };
  }

  if (projectWorkspaceRoot && projectWorkspaceRootAvailable) {
    return {
      path: projectWorkspaceRoot,
      pathAvailable: true,
      pathSource: "project_workspace_root",
      worktreePath,
      worktreePathAvailable,
      projectWorkspaceRoot,
      projectWorkspaceRootAvailable,
      fallbackToProjectRoot: Boolean(worktreePath && !worktreePathAvailable),
    };
  }

  if (worktreePath) {
    return {
      path: worktreePath,
      pathAvailable: false,
      pathSource: "worktree_path",
      worktreePath,
      worktreePathAvailable,
      projectWorkspaceRoot,
      projectWorkspaceRootAvailable,
      fallbackToProjectRoot: false,
    };
  }

  if (projectWorkspaceRoot) {
    return {
      path: projectWorkspaceRoot,
      pathAvailable: false,
      pathSource: "project_workspace_root",
      worktreePath,
      worktreePathAvailable,
      projectWorkspaceRoot,
      projectWorkspaceRootAvailable,
      fallbackToProjectRoot: false,
    };
  }

  return {
    path: null,
    pathAvailable: false,
    pathSource: null,
    worktreePath,
    worktreePathAvailable,
    projectWorkspaceRoot,
    projectWorkspaceRootAvailable,
    fallbackToProjectRoot: false,
  };
}

function checkpointStatusToLatestTurnState(status) {
  if (status === "error") {
    return "error";
  }
  if (status === "missing") {
    return "interrupted";
  }
  return "completed";
}

function compareT3MessagesByCreatedAt(left, right) {
  return timestampOrZero(left?.createdAt) - timestampOrZero(right?.createdAt)
    || normalizeNonEmptyString(left?.id).localeCompare(normalizeNonEmptyString(right?.id));
}

function retainThreadMessagesAfterRevert(messages, retainedTurnIds, turnCount) {
  const normalizedMessages = Array.isArray(messages) ? messages : [];
  const retainedMessageIds = new Set();
  for (const message of normalizedMessages) {
    const messageId = normalizeNonEmptyString(message?.id);
    const role = normalizeNonEmptyString(message?.role).toLowerCase();
    const turnId = normalizeNonEmptyString(message?.turnId);
    if (role === "system") {
      retainedMessageIds.add(messageId);
      continue;
    }
    if (turnId && retainedTurnIds.has(turnId)) {
      retainedMessageIds.add(messageId);
    }
  }

  const retainedUserCount = normalizedMessages.filter(
    (message) =>
      normalizeNonEmptyString(message?.role).toLowerCase() === "user"
      && retainedMessageIds.has(normalizeNonEmptyString(message?.id))
  ).length;
  const missingUserCount = Math.max(0, normalizeSequenceNumber(turnCount) - retainedUserCount);
  if (missingUserCount > 0) {
    const fallbackUserMessages = normalizedMessages
      .filter((message) => {
        const messageId = normalizeNonEmptyString(message?.id);
        const turnId = normalizeNonEmptyString(message?.turnId);
        return normalizeNonEmptyString(message?.role).toLowerCase() === "user"
          && !retainedMessageIds.has(messageId)
          && (!turnId || retainedTurnIds.has(turnId));
      })
      .sort(compareT3MessagesByCreatedAt)
      .slice(0, missingUserCount);
    for (const message of fallbackUserMessages) {
      retainedMessageIds.add(normalizeNonEmptyString(message?.id));
    }
  }

  const retainedAssistantCount = normalizedMessages.filter(
    (message) =>
      normalizeNonEmptyString(message?.role).toLowerCase() === "assistant"
      && retainedMessageIds.has(normalizeNonEmptyString(message?.id))
  ).length;
  const missingAssistantCount = Math.max(0, normalizeSequenceNumber(turnCount) - retainedAssistantCount);
  if (missingAssistantCount > 0) {
    const fallbackAssistantMessages = normalizedMessages
      .filter((message) => {
        const messageId = normalizeNonEmptyString(message?.id);
        const turnId = normalizeNonEmptyString(message?.turnId);
        return normalizeNonEmptyString(message?.role).toLowerCase() === "assistant"
          && !retainedMessageIds.has(messageId)
          && (!turnId || retainedTurnIds.has(turnId));
      })
      .sort(compareT3MessagesByCreatedAt)
      .slice(0, missingAssistantCount);
    for (const message of fallbackAssistantMessages) {
      retainedMessageIds.add(normalizeNonEmptyString(message?.id));
    }
  }

  return normalizedMessages.filter(
    (message) => retainedMessageIds.has(normalizeNonEmptyString(message?.id))
  );
}

function retainThreadActivitiesAfterRevert(activities, retainedTurnIds) {
  return (Array.isArray(activities) ? activities : []).filter((activity) => {
    const turnId = normalizeNonEmptyString(activity?.turnId);
    return !turnId || retainedTurnIds.has(turnId);
  });
}

function retainThreadProposedPlansAfterRevert(proposedPlans, retainedTurnIds) {
  return (Array.isArray(proposedPlans) ? proposedPlans : []).filter((proposedPlan) => {
    const turnId = normalizeNonEmptyString(proposedPlan?.turnId);
    return !turnId || retainedTurnIds.has(turnId);
  });
}

function getThreadCheckpointTurnCount(thread) {
  return (Array.isArray(thread?.checkpoints) ? thread.checkpoints : []).reduce((maxTurnCount, checkpoint) => {
    return Math.max(maxTurnCount, normalizeSequenceNumber(checkpoint?.checkpointTurnCount));
  }, 0);
}

function normalizeRollbackTurnCount(value) {
  const parsed = Number.parseInt(String(value), 10);
  if (!Number.isFinite(parsed) || parsed < 1) {
    throw new Error("T3 rollback requires dropping at least one turn.");
  }
  return parsed;
}

function applyThreadRevertedState(thread, {
  turnCount = 0,
  updatedAt = null,
} = {}) {
  const normalizedTurnCount = Math.max(0, normalizeSequenceNumber(turnCount));
  const checkpoints = (Array.isArray(thread?.checkpoints) ? thread.checkpoints : [])
    .filter((entry) => normalizeSequenceNumber(entry?.checkpointTurnCount) <= normalizedTurnCount)
    .sort(
      (left, right) =>
        normalizeSequenceNumber(left?.checkpointTurnCount) - normalizeSequenceNumber(right?.checkpointTurnCount)
    );
  const retainedTurnIds = new Set(
    checkpoints.map((entry) => normalizeNonEmptyString(entry?.turnId)).filter(Boolean)
  );
  const latestCheckpoint = checkpoints.at(-1) || null;

  thread.checkpoints = checkpoints;
  thread.messages = retainThreadMessagesAfterRevert(
    Array.isArray(thread?.messages) ? thread.messages : [],
    retainedTurnIds,
    normalizedTurnCount
  );
  thread.proposedPlans = retainThreadProposedPlansAfterRevert(
    Array.isArray(thread?.proposedPlans) ? thread.proposedPlans : [],
    retainedTurnIds
  );
  thread.activities = retainThreadActivitiesAfterRevert(
    Array.isArray(thread?.activities) ? thread.activities : [],
    retainedTurnIds
  );
  thread.latestTurn = latestCheckpoint
    ? {
      turnId: normalizeNonEmptyString(latestCheckpoint.turnId),
      state: checkpointStatusToLatestTurnState(
        normalizeNonEmptyString(latestCheckpoint.status) || "ready"
      ),
      requestedAt: normalizeNonEmptyString(latestCheckpoint.completedAt) || null,
      startedAt: normalizeNonEmptyString(latestCheckpoint.completedAt) || null,
      completedAt: normalizeNonEmptyString(latestCheckpoint.completedAt) || null,
      assistantMessageId: normalizeNonEmptyString(latestCheckpoint.assistantMessageId) || null,
    }
    : null;
  thread.session = null;
  thread.updatedAt = normalizeNonEmptyString(updatedAt) || thread.updatedAt || null;
}

function describeT3ThreadCapabilities({
  snapshot = null,
  thread,
  project = null,
}) {
  const resolvedProject = project || findRecord(
    Array.isArray(snapshot?.projects) ? snapshot.projects : [],
    "id",
    thread?.projectId,
  );
  const backendProvider = normalizeNonEmptyString(thread?.modelSelection?.provider) || null;
  const normalizedProvider = normalizeNonEmptyString(backendProvider).toLowerCase();
  const workspace = resolveThreadWorkspace(thread, resolvedProject);
  const workspacePath = workspace.path;
  const workspaceAvailable = workspace.pathAvailable;
  const providerSupported = !normalizedProvider || normalizedProvider === "codex";
  const workspaceResolved = Boolean(workspacePath);
  const companionSupportState = resolveCompanionSupportState({
    providerSupported,
    workspaceResolved,
    workspaceAvailable,
  });
  const companionSupportReason = buildCompanionSupportReason({
    backendProvider,
    companionSupportState,
  });
  const liveUpdatesReason = companionSupportReason
    || "The read-only T3 adapter only attaches live updates for supported Codex-backed threads whose local workspace mapping still resolves.";
  const readOnlyMutationReason = "The read-only T3 adapter milestone has not enabled mutating thread actions yet.";
  const checkpointTurnCount = getThreadCheckpointTurnCount(thread);
  const checkpointRollbackReason = companionSupportState === "supported"
    ? (
      checkpointTurnCount > 0
        ? null
        : "This T3 thread has no checkpoints available to roll back yet."
    )
    : companionSupportReason || readOnlyMutationReason;
  const interruptReason = companionSupportState === "supported"
    ? null
    : companionSupportReason || readOnlyMutationReason;

  return {
    readOnly: true,
    backendProvider,
    companionSupported: companionSupportState === "supported",
    companionSupportState,
    companionSupportReason,
    workspacePath: workspacePath || null,
    workspacePathSource: workspace.pathSource,
    workspaceResolved,
    workspaceAvailable,
    workspaceFallbackUsed: workspace.fallbackToProjectRoot,
    recordedWorktreePath: workspace.worktreePath,
    recordedWorktreeAvailable: workspace.worktreePathAvailable,
    projectWorkspaceRoot: workspace.projectWorkspaceRoot,
    projectWorkspaceRootAvailable: workspace.projectWorkspaceRootAvailable,
    read: {
      supported: true,
      reason: null,
    },
    liveUpdates: {
      supported: companionSupportState === "supported",
      reason: companionSupportState === "supported" ? null : liveUpdatesReason,
    },
    turnStart: {
      supported: companionSupportState === "supported",
      reason: companionSupportState === "supported"
        ? null
        : interruptReason,
    },
    turnInterrupt: {
      supported: companionSupportState === "supported",
      reason: interruptReason,
    },
    approvalResponses: {
      supported: companionSupportState === "supported",
      reason: companionSupportState === "supported"
        ? null
        : interruptReason,
    },
    userInputResponses: {
      supported: companionSupportState === "supported",
      reason: companionSupportState === "supported"
        ? null
        : interruptReason,
    },
    toolInputResponses: {
      supported: companionSupportState === "supported",
      reason: companionSupportState === "supported"
        ? null
        : interruptReason,
    },
    checkpointRollback: {
      supported: companionSupportState === "supported" && checkpointTurnCount > 0,
      reason: checkpointRollbackReason,
    },
  };
}

function resolveCompanionSupportState({
  providerSupported,
  workspaceResolved,
  workspaceAvailable,
}) {
  const workspaceReady = workspaceResolved && workspaceAvailable;
  if (providerSupported && workspaceReady) {
    return "supported";
  }
  if (!providerSupported && workspaceReady) {
    return "unsupported_provider";
  }
  if (!providerSupported && workspaceResolved) {
    return "unsupported_provider_and_workspace_unavailable";
  }
  if (!providerSupported) {
    return "unsupported_provider_and_workspace_unmapped";
  }
  if (workspaceResolved) {
    return "workspace_unavailable";
  }
  return "workspace_unmapped";
}

function buildCompanionSupportReason({
  backendProvider,
  companionSupportState,
}) {
  if (companionSupportState === "supported") {
    return null;
  }
  if (companionSupportState === "unsupported_provider") {
    return `The read-only T3 adapter only attaches live updates for Codex-backed threads; this thread uses ${backendProvider || "an unsupported provider"}.`;
  }
  if (companionSupportState === "unsupported_provider_and_workspace_unavailable") {
    return `This T3 thread uses ${backendProvider || "an unsupported provider"} and its recorded local workspace path no longer resolves on this host.`;
  }
  if (companionSupportState === "unsupported_provider_and_workspace_unmapped") {
    return `This T3 thread uses ${backendProvider || "an unsupported provider"} and does not expose a usable local workspace mapping on this host.`;
  }
  if (companionSupportState === "workspace_unavailable") {
    return "This supported T3 thread remains discoverable, but its recorded local workspace path no longer resolves on this host.";
  }
  if (companionSupportState === "workspace_unmapped") {
    return "This supported T3 thread remains discoverable, but the bridge could not resolve a usable local workspace mapping for it on this host.";
  }
  return "This T3 thread is not currently eligible for Androdex companion support.";
}

function buildThreadTurns(thread) {
  const messages = [...(Array.isArray(thread?.messages) ? thread.messages : [])]
    .sort((left, right) => {
      const leftCreated = timestampOrZero(left?.createdAt || left?.updatedAt);
      const rightCreated = timestampOrZero(right?.createdAt || right?.updatedAt);
      if (leftCreated !== rightCreated) {
        return leftCreated - rightCreated;
      }
      return normalizeNonEmptyString(left?.id).localeCompare(normalizeNonEmptyString(right?.id));
    });

  const turns = [];
  const turnsByKey = new Map();

  for (const message of messages) {
    const turnId = normalizeNonEmptyString(message?.turnId);
    const turnKey = turnId || `message:${normalizeNonEmptyString(message?.id) || turns.length + 1}`;
    let turn = turnsByKey.get(turnKey);
    if (!turn) {
      turn = {
        ...(turnId ? { id: turnId } : {}),
        createdAt: normalizeNonEmptyString(message?.createdAt) || normalizeNonEmptyString(message?.updatedAt) || null,
        updatedAt: normalizeNonEmptyString(message?.updatedAt) || normalizeNonEmptyString(message?.createdAt) || null,
        items: [],
      };
      turnsByKey.set(turnKey, turn);
      turns.push(turn);
    }

    turn.items.push(buildTurnItem(message));
  }

  const latestTurnId = normalizeNonEmptyString(thread?.latestTurn?.turnId);
  if (latestTurnId && !turnsByKey.has(latestTurnId)) {
    const latestTurn = {
      id: latestTurnId,
      createdAt: normalizeNonEmptyString(thread?.latestTurn?.requestedAt)
        || normalizeNonEmptyString(thread?.latestTurn?.startedAt)
        || normalizeNonEmptyString(thread?.latestTurn?.completedAt)
        || normalizeNonEmptyString(thread?.updatedAt)
        || null,
      updatedAt: normalizeNonEmptyString(thread?.latestTurn?.completedAt)
        || normalizeNonEmptyString(thread?.latestTurn?.startedAt)
        || normalizeNonEmptyString(thread?.latestTurn?.requestedAt)
        || normalizeNonEmptyString(thread?.updatedAt)
        || null,
      items: [],
    };
    turnsByKey.set(latestTurnId, latestTurn);
    turns.push(latestTurn);
  }

  for (const turn of turns) {
    const normalizedTurnId = normalizeNonEmptyString(turn.id);
    if (normalizedTurnId && normalizedTurnId === latestTurnId) {
      turn.status = mapLatestTurnStateToThreadStatus(thread?.latestTurn?.state);
    } else {
      turn.status = "completed";
    }
  }

  return turns;
}

function buildTurnItem(message) {
  const role = normalizeNonEmptyString(message?.role);
  const baseItem = {
    id: normalizeNonEmptyString(message?.id) || null,
    createdAt: normalizeNonEmptyString(message?.createdAt) || normalizeNonEmptyString(message?.updatedAt) || null,
    updatedAt: normalizeNonEmptyString(message?.updatedAt) || normalizeNonEmptyString(message?.createdAt) || null,
  };
  const content = buildMessageContent(message, role);

  if (role === "user") {
    return {
      ...baseItem,
      type: "user_message",
      content,
    };
  }

  if (role === "assistant") {
    return {
      ...baseItem,
      type: "assistant_message",
      content,
    };
  }

  return {
    ...baseItem,
    type: "message",
    role: role || "system",
    text: composeMessageText(message),
  };
}

function buildMessageContent(message, role) {
  const text = composeMessageText(message);
  const textType = role === "user" ? "input_text" : "output_text";
  const content = [];
  if (text) {
    content.push({
      type: textType,
      text,
    });
  }
  content.push(...buildAttachmentContentItems(message?.attachments));
  if (content.length > 0) {
    return content;
  }
  return [
    {
      type: textType,
      text: "",
    },
  ];
}

function composeMessageText(message) {
  const text = normalizeNonEmptyString(message?.text);
  const attachmentLabels = listUnsupportedAttachmentLabels(message?.attachments);
  return [text, ...attachmentLabels].filter(Boolean).join("\n").trim();
}

function listUnsupportedAttachmentLabels(attachments) {
  return (Array.isArray(attachments) ? attachments : [])
    .map((attachment) => {
      if (isRenderableImageAttachment(attachment)) {
        return "";
      }
      const attachmentName = normalizeNonEmptyString(attachment?.name);
      if (!attachmentName) {
        return "";
      }
      return `[Attachment: ${attachmentName}]`;
    })
    .filter(Boolean);
}

function buildAttachmentContentItems(attachments) {
  return (Array.isArray(attachments) ? attachments : [])
    .map((attachment) => buildAttachmentContentItem(attachment))
    .filter(Boolean);
}

function buildAttachmentContentItem(attachment) {
  if (!isRenderableImageAttachment(attachment)) {
    return null;
  }

  const source = firstNonEmptyString([
    attachment?.url,
    attachment?.image_url,
    attachment?.imageUrl,
    attachment?.path,
    attachment?.sourceUrl,
    attachment?.source_url,
  ]);
  const thumbnailBase64JPEG = firstNonEmptyString([
    attachment?.thumbnailBase64JPEG,
    attachment?.thumbnail_base64_jpeg,
  ]);
  const contentItem = {
    type: looksLikeLocalPath(source) ? "localimage" : "image",
  };

  if (source) {
    if (looksLikeLocalPath(source)) {
      contentItem.path = source;
    } else {
      contentItem.url = source;
    }
  }
  if (thumbnailBase64JPEG) {
    contentItem.thumbnailBase64JPEG = thumbnailBase64JPEG;
  }

  return Object.keys(contentItem).length > 1 ? contentItem : null;
}

function isRenderableImageAttachment(attachment) {
  if (!attachment || typeof attachment !== "object") {
    return false;
  }

  const attachmentType = firstNonEmptyString([
    attachment.type,
    attachment.kind,
  ]).toLowerCase();
  if (attachmentType === "image" || attachmentType === "localimage") {
    return true;
  }

  const mimeType = firstNonEmptyString([
    attachment.mimeType,
    attachment.mime_type,
    attachment.mediaType,
    attachment.media_type,
    attachment.contentType,
    attachment.content_type,
  ]).toLowerCase();
  if (mimeType.startsWith("image/")) {
    return true;
  }

  const source = firstNonEmptyString([
    attachment.url,
    attachment.image_url,
    attachment.imageUrl,
    attachment.path,
    attachment.sourceUrl,
    attachment.source_url,
  ]);
  if (source && looksLikeImageSource(source)) {
    return true;
  }

  const name = normalizeNonEmptyString(attachment.name);
  if (name && looksLikeImageSource(name)) {
    return true;
  }

  return !!firstNonEmptyString([
    attachment.thumbnailBase64JPEG,
    attachment.thumbnail_base64_jpeg,
  ]);
}

function looksLikeImageSource(source) {
  if (!source) {
    return false;
  }
  if (/^data:image\//i.test(source)) {
    return true;
  }
  return /\.(avif|bmp|gif|heic|heif|jpe?g|png|svg|webp)(?:[?#].*)?$/i.test(source);
}

function looksLikeLocalPath(source) {
  if (!source || /^data:/i.test(source) || /^[a-z]+:\/\//i.test(source)) {
    return false;
  }
  return source.startsWith("/")
    || source.startsWith("./")
    || source.startsWith("../")
    || /^[A-Za-z]:[\\/]/.test(source);
}

function firstNonEmptyString(values) {
  for (const value of Array.isArray(values) ? values : []) {
    const normalized = normalizeNonEmptyString(value);
    if (normalized) {
      return normalized;
    }
  }
  return "";
}

function mapLatestTurnStateToThreadStatus(state) {
  const normalizedState = normalizeNonEmptyString(state).toLowerCase();
  if (normalizedState === "running") {
    return "running";
  }
  if (normalizedState === "interrupted") {
    return "interrupted";
  }
  if (normalizedState === "error") {
    return "error";
  }
  return "completed";
}

function normalizeLimit(limit) {
  const numericLimit = Number(limit);
  if (!Number.isFinite(numericLimit) || numericLimit <= 0) {
    return 40;
  }
  return Math.max(1, Math.min(200, Math.trunc(numericLimit)));
}

function parseCursorOffset(cursor) {
  if (cursor == null) {
    return 0;
  }
  const parsed = Number(cursor);
  return Number.isFinite(parsed) && parsed >= 0 ? Math.trunc(parsed) : 0;
}

function timestampOrZero(value) {
  const parsed = Date.parse(normalizeNonEmptyString(value) || "");
  return Number.isFinite(parsed) ? parsed : 0;
}

function normalizeNonEmptyString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

function normalizeSequenceNumber(value) {
  const numericValue = Number(value);
  if (!Number.isFinite(numericValue) || numericValue < 0) {
    return 0;
  }
  return Math.trunc(numericValue);
}

function pathExists(candidatePath) {
  try {
    return fs.existsSync(path.normalize(candidatePath));
  } catch {
    return false;
  }
}

module.exports = {
  applyT3EventsToSnapshot,
  buildT3ThreadListResult,
  buildT3ThreadRollbackResult,
  buildT3ThreadReadResult,
  createEmptyT3Snapshot,
  describeT3ThreadCapabilities,
};
