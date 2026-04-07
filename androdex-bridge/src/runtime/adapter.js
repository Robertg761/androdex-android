// FILE: runtime/adapter.js
// Purpose: Provides the first runtime-target adapter seam so the bridge can switch host runtimes without pushing target-specific lifecycle into workspace orchestration.
// Layer: CLI helper
// Exports: createRuntimeAdapter
// Depends on: ../codex/transport, ./method-policy, ./t3-protocol, ./t3-suitability, ./target-config

const fs = require("fs");
const path = require("path");
const { createCodexTransport } = require("../codex/transport");
const {
  applyT3EventsToSnapshot,
  buildT3ThreadListResult,
  buildT3ThreadReadResult,
  createEmptyT3Snapshot,
} = require("./t3-read-model");
const {
  buildRuntimeTargetMethodRejectionMessage,
  isRuntimeTargetMethodAllowed,
} = require("./method-policy");
const {
  createT3AttachRequirements,
  ensureLoopbackEndpoint,
  validateT3AttachConfig,
} = require("./t3-suitability");
const { createT3EndpointTransport } = require("./t3-protocol");
const {
  DEFAULT_RUNTIME_TARGET,
  resolveRuntimeTargetConfig,
} = require("./target-config");

const T3_ATTACH_TIMEOUT_MS = 5_000;
const T3_LIVE_TURN_EVENT_TYPES = new Set([
  "thread.runtime-mode-set",
  "thread.session-set",
  "thread.turn-diff-completed",
  "thread.turn-interrupt-requested",
]);

function createRuntimeAdapter({
  targetKind = DEFAULT_RUNTIME_TARGET,
  endpoint = "",
  env = process.env,
  cwd = "",
  loadReplayCursor = null,
  persistReplayCursor = null,
  WebSocketImpl,
} = {}) {
  const runtimeTarget = resolveRuntimeTargetConfig({ kind: targetKind });
  if (runtimeTarget.kind === "codex-native") {
    return createCodexNativeRuntimeAdapter({
      runtimeTarget,
      endpoint,
      env,
      cwd,
      WebSocketImpl,
    });
  }

  if (runtimeTarget.kind === "t3-server") {
    return createT3ServerRuntimeAdapter({
      runtimeTarget,
      endpoint,
      env,
      loadReplayCursor,
      persistReplayCursor,
      WebSocketImpl,
    });
  }

  throw new Error(`Unsupported runtime target adapter: ${runtimeTarget.kind}`);
}

function createCodexNativeRuntimeAdapter({
  runtimeTarget,
  endpoint = "",
  env = process.env,
  cwd = "",
  WebSocketImpl,
} = {}) {
  const transport = createCodexTransport({
    endpoint,
    env,
    cwd,
    runtimeTarget: runtimeTarget.kind,
    WebSocketImpl,
  });

  return {
    backendProviderKind: runtimeTarget.backendProviderKind,
    describe: transport.describe,
    getRuntimeMetadata() {
      return null;
    },
    kind: runtimeTarget.kind,
    mode: transport.mode,
    onClose: transport.onClose,
    onError: transport.onError,
    onMessage: transport.onMessage,
    onMetadata() {},
    send: transport.send,
    shutdown: transport.shutdown,
    whenReady() {
      return Promise.resolve();
    },
  };
}

function createT3ServerRuntimeAdapter({
  runtimeTarget,
  endpoint = "",
  env = process.env,
  loadReplayCursor = null,
  persistReplayCursor = null,
  WebSocketImpl,
} = {}) {
  const normalizedEndpoint = normalizeNonEmptyString(endpoint);
  if (!normalizedEndpoint) {
    const endpointHint = Array.isArray(runtimeTarget?.endpointEnvVars)
      && runtimeTarget.endpointEnvVars.length > 0
      ? runtimeTarget.endpointEnvVars.join(" or ")
      : "an explicit endpoint";
    throw new Error(
      `${runtimeTarget.displayName} read-only attach currently requires ${endpointHint}.`
    );
  }

  const parsedEndpoint = ensureLoopbackEndpoint(normalizedEndpoint);
  const metadataListeners = createHandlerBag();
  const messageListeners = createHandlerBag();
  let snapshotCache = createEmptyT3Snapshot();
  let runtimeMetadata = {
    runtimeAttachState: "probing",
    runtimeAttachFailure: null,
    runtimeProtocolVersion: null,
    runtimeAuthMode: null,
    runtimeStateRoot: null,
    runtimeEndpointHost: normalizeNonEmptyString(parsedEndpoint.hostname),
    runtimeSnapshotSequence: null,
    runtimeReplaySequence: 0,
    runtimeSubscriptionState: "bootstrapping",
    runtimeDuplicateSuppressionCount: 0,
  };
  let unsubscribeDomainEvents = null;
  let pendingDomainEvents = [];
  let pendingEventFlush = Promise.resolve();
  let appliedSequence = 0;
  let duplicateSuppressionCount = 0;
  let liveDomainEventsReady = false;
  let replayRecoveryPromise = null;
  let resubscribePromise = null;
  let replayScope = null;
  let persistedReplayCursor = 0;
  let shuttingDown = false;
  const resumedThreadIds = new Set();
  const transport = createT3EndpointTransport({
    endpoint: normalizedEndpoint,
    WebSocketImpl,
    onBeforeReadyRequest: performSuitabilityProbe,
  });
  transport.onMessage((message) => {
    messageListeners.emit(message);
  });

  function updateRuntimeMetadata(nextValues) {
    runtimeMetadata = {
      ...runtimeMetadata,
      ...(nextValues || {}),
    };
    metadataListeners.emit(runtimeMetadata);
  }

  async function performSuitabilityProbe({ request }) {
    updateRuntimeMetadata({
      runtimeAttachState: "probing",
      runtimeAttachFailure: null,
      runtimeSubscriptionState: "bootstrapping",
    });
    const configResult = await request("server.getConfig", {}, {
      timeoutMs: T3_ATTACH_TIMEOUT_MS,
    });
    const validatedMetadata = validateT3AttachConfig({
      endpoint: normalizedEndpoint,
      config: configResult,
      requirements: createT3AttachRequirements({
        endpoint: normalizedEndpoint,
        env,
      }),
    });
    updateRuntimeMetadata({
      runtimeAttachState: "bootstrapping",
      runtimeAttachFailure: null,
      ...validatedMetadata,
    });
    replayScope = {
      runtimeStateRoot: normalizeNonEmptyString(validatedMetadata.runtimeStateRoot),
      runtimeTarget: runtimeTarget.kind,
    };
    persistedReplayCursor = normalizeSequenceNumber(loadReplayCursor?.(replayScope));
    updateRuntimeMetadata({
      runtimeReplaySequence: persistedReplayCursor,
    });
    startDomainEventSubscription();
    await refreshSnapshotCache();
    appliedSequence = normalizeSequenceNumber(snapshotCache?.snapshotSequence);
    persistAppliedReplaySequence(appliedSequence);
    liveDomainEventsReady = true;
    await flushPendingDomainEvents();
    updateRuntimeMetadata({
      runtimeAttachState: "ready",
      runtimeAttachFailure: null,
      runtimeSubscriptionState: "live",
    });
  }

  async function refreshSnapshotCache() {
    const snapshot = await transport.request("orchestration.getSnapshot", {}, {
      timeoutMs: T3_ATTACH_TIMEOUT_MS,
    });
    snapshotCache = snapshot && typeof snapshot === "object" ? snapshot : createEmptyT3Snapshot();
    updateRuntimeMetadata({
      runtimeSnapshotSequence: normalizeSequenceNumber(snapshotCache?.snapshotSequence),
    });
    return snapshotCache;
  }

  async function ensureSnapshotCache() {
    const visibleSequence = normalizeSequenceNumber(snapshotCache?.snapshotSequence);
    if (visibleSequence > 0 || Array.isArray(snapshotCache?.threads) || Array.isArray(snapshotCache?.projects)) {
      return snapshotCache;
    }
    return refreshSnapshotCache();
  }

  function startDomainEventSubscription() {
    unsubscribeDomainEvents?.();
    updateRuntimeMetadata({
      runtimeSubscriptionState: liveDomainEventsReady ? "resubscribing" : "subscribing",
    });
    unsubscribeDomainEvents = transport.subscribe("subscribeOrchestrationDomainEvents", {}, {
      onValue(event) {
        queueDomainEvent(event);
      },
      onEnd() {
        scheduleDomainEventResubscribe();
      },
      onError() {
        scheduleDomainEventResubscribe();
      },
    });
  }

  function scheduleDomainEventResubscribe() {
    if (shuttingDown || resubscribePromise) {
      return;
    }
    resubscribePromise = Promise.resolve()
      .then(async () => {
        startDomainEventSubscription();
        if (liveDomainEventsReady) {
          await recoverMissingDomainEvents(appliedSequence);
          await flushPendingDomainEvents();
          updateRuntimeMetadata({
            runtimeAttachFailure: null,
            runtimeSubscriptionState: "live",
          });
        }
      })
      .catch((error) => {
        updateRuntimeMetadata({
          runtimeSubscriptionState: "error",
          runtimeAttachFailure: normalizeNonEmptyString(error?.message) || runtimeMetadata.runtimeAttachFailure,
        });
      })
      .finally(() => {
        resubscribePromise = null;
      });
  }

  function queueDomainEvent(event) {
    if (!event || typeof event !== "object") {
      return;
    }
    pendingDomainEvents.push(event);
    if (liveDomainEventsReady) {
      void flushPendingDomainEvents().catch(() => {
        // Error state is already published by flushPendingDomainEvents.
      });
    }
  }

  function flushPendingDomainEvents() {
    pendingEventFlush = pendingEventFlush.catch(() => undefined).then(async () => {
      if (!liveDomainEventsReady || pendingDomainEvents.length === 0) {
        return;
      }
      pendingDomainEvents.sort((left, right) => normalizeSequenceNumber(left?.sequence) - normalizeSequenceNumber(right?.sequence));
      while (pendingDomainEvents.length > 0) {
        const nextEvent = pendingDomainEvents[0];
        const nextSequence = normalizeSequenceNumber(nextEvent?.sequence);
        if (!Number.isFinite(nextSequence) || nextSequence <= appliedSequence) {
          pendingDomainEvents.shift();
          duplicateSuppressionCount += 1;
          publishReadModelMetadata();
          continue;
        }

        if (appliedSequence > 0 && nextSequence > appliedSequence + 1) {
          const recoveredSequence = await recoverMissingDomainEvents(appliedSequence);
          if (recoveredSequence < nextSequence - 1) {
            throw new Error(
              `T3 event gap detected after sequence ${appliedSequence}; replay did not supply sequence ${nextSequence - 1}.`
            );
          }
          pendingDomainEvents.sort((left, right) => normalizeSequenceNumber(left?.sequence) - normalizeSequenceNumber(right?.sequence));
          continue;
        }

        pendingDomainEvents.shift();
        applyEventsToReadModel([nextEvent]);
      }
    }).catch((error) => {
      updateRuntimeMetadata({
        runtimeSubscriptionState: "error",
        runtimeAttachFailure: normalizeNonEmptyString(error?.message) || runtimeMetadata.runtimeAttachFailure,
      });
      throw error;
    });
    return pendingEventFlush;
  }

  function recoverMissingDomainEvents(fromSequenceExclusive) {
    if (replayRecoveryPromise) {
      return replayRecoveryPromise;
    }

    updateRuntimeMetadata({
      runtimeSubscriptionState: "replaying",
    });
    replayRecoveryPromise = transport.request("orchestration.replayEvents", {
      fromSequenceExclusive,
    }, {
      timeoutMs: T3_ATTACH_TIMEOUT_MS,
    }).then((events) => {
      applyEventsToReadModel(Array.isArray(events) ? events : []);
      return appliedSequence;
    }).finally(() => {
      replayRecoveryPromise = null;
      if (!shuttingDown) {
        updateRuntimeMetadata({
          runtimeAttachFailure: null,
          runtimeSubscriptionState: "live",
        });
      }
    });
    return replayRecoveryPromise;
  }

  function applyEventsToReadModel(events) {
    let workingSnapshot = snapshotCache;
    let appliedCount = 0;
    let duplicateCount = 0;
    let lastSequence = appliedSequence;
    const orderedEvents = [...(Array.isArray(events) ? events : [])]
      .filter((event) => event && typeof event === "object")
      .sort((left, right) => normalizeSequenceNumber(left?.sequence) - normalizeSequenceNumber(right?.sequence));

    for (const event of orderedEvents) {
      const beforeSnapshot = workingSnapshot;
      const result = applyT3EventsToSnapshot({
        snapshot: beforeSnapshot,
        events: [event],
      });
      workingSnapshot = result.snapshot;
      appliedCount += normalizeSequenceNumber(result.appliedCount);
      duplicateCount += normalizeSequenceNumber(result.duplicateCount);
      lastSequence = Math.max(lastSequence, normalizeSequenceNumber(result.lastSequence));
      if (normalizeSequenceNumber(result.appliedCount) > 0) {
        emitLiveNotificationsForAppliedT3Event({
          beforeSnapshot,
          afterSnapshot: workingSnapshot,
          event,
        });
      }
    }

    snapshotCache = workingSnapshot;
    appliedSequence = Math.max(appliedSequence, lastSequence);
    duplicateSuppressionCount += duplicateCount;
    persistAppliedReplaySequence(appliedSequence);
    publishReadModelMetadata();
    return {
      appliedCount,
      duplicateCount,
      lastSequence: appliedSequence,
      snapshot: snapshotCache,
    };
  }

  function emitLiveNotificationsForAppliedT3Event({
    beforeSnapshot,
    afterSnapshot,
    event,
  }) {
    const eventType = normalizeNonEmptyString(event?.type);
    if (!eventType.startsWith("thread.")) {
      return;
    }
    const threadId = normalizeNonEmptyString(event?.payload?.threadId)
      || normalizeNonEmptyString(event?.aggregateId);
    if (!threadId || !resumedThreadIds.has(threadId)) {
      return;
    }

    const beforeThread = findT3Thread(beforeSnapshot, threadId);
    const afterThread = findT3Thread(afterSnapshot, threadId);
    if (!afterThread) {
      resumedThreadIds.delete(threadId);
      return;
    }
    if (!isT3ThreadEligibleForLiveUpdates(afterSnapshot, afterThread)) {
      resumedThreadIds.delete(threadId);
      return;
    }

    if (eventType === "thread.meta-updated") {
      const beforeTitle = normalizeNonEmptyString(beforeThread?.title);
      const afterTitle = normalizeNonEmptyString(afterThread?.title);
      if (afterTitle && afterTitle !== beforeTitle) {
        emitNotification("thread/name/updated", {
          threadId,
          title: afterTitle,
        });
      }
    }

    if (eventType === "thread.message-sent") {
      emitAssistantMessageNotification({
        beforeThread,
        afterThread,
        event,
        threadId,
      });
    }

    if (T3_LIVE_TURN_EVENT_TYPES.has(eventType)) {
      for (const notification of buildTurnLifecycleNotifications({
        beforeThread,
        afterThread,
        threadId,
      })) {
        emitNotification(notification.method, notification.params);
      }
    }
  }

  function emitAssistantMessageNotification({
    beforeThread,
    afterThread,
    event,
    threadId,
  }) {
    const payload = event?.payload && typeof event.payload === "object" ? event.payload : {};
    if (normalizeNonEmptyString(payload.role).toLowerCase() !== "assistant") {
      return;
    }

    const messageId = normalizeNonEmptyString(payload.messageId);
    if (!messageId || findThreadMessage(beforeThread, messageId)) {
      return;
    }
    const message = findThreadMessage(afterThread, messageId);
    const messageText = typeof message?.text === "string" ? message.text : "";
    if (!messageText) {
      return;
    }

    emitNotification("codex/event/agent_message", {
      threadId,
      turnId: normalizeNonEmptyString(message?.turnId)
        || normalizeNonEmptyString(afterThread?.latestTurn?.turnId)
        || null,
      messageId,
      message: messageText,
    });
  }

  function persistAppliedReplaySequence(sequence) {
    if (!Number.isFinite(sequence) || sequence < 0 || !replayScope?.runtimeStateRoot) {
      return;
    }
    try {
      persistReplayCursor?.({
        ...replayScope,
        sequence,
      });
    } catch {
      // Persistence is best-effort and should not break the active runtime.
    }
  }

  function publishReadModelMetadata() {
    updateRuntimeMetadata({
      runtimeSnapshotSequence: normalizeSequenceNumber(snapshotCache?.snapshotSequence),
      runtimeReplaySequence: appliedSequence,
      runtimeDuplicateSuppressionCount: duplicateSuppressionCount,
    });
  }

  function emitMessage(rawMessage) {
    messageListeners.emit(rawMessage);
  }

  function emitRpcResult(requestId, result) {
    emitMessage(JSON.stringify({
      id: requestId,
      result,
    }));
  }

  function emitRpcError(requestId, message) {
    emitMessage(JSON.stringify({
      id: requestId,
      error: {
        code: -32000,
        message,
      },
    }));
  }

  function emitNotification(method, params) {
    emitMessage(JSON.stringify({
      method,
      params,
    }));
  }

  function handleReadOnlyRequest(rawMessage) {
    const parsed = safeParseJSON(rawMessage);
    const requestId = parsed?.id;
    const method = normalizeNonEmptyString(parsed?.method);
    if (requestId == null || !method) {
      return false;
    }

    if (method === "thread/list") {
      void ensureSnapshotCache()
        .then((snapshot) => {
          emitRpcResult(requestId, buildT3ThreadListResult({
            snapshot,
            cursor: parsed?.params?.cursor,
            limit: parsed?.params?.limit,
          }));
        })
        .catch((error) => {
          emitRpcError(requestId, normalizeNonEmptyString(error?.message) || "Failed to load T3 threads.");
        });
      return true;
    }

    if (method === "thread/read") {
      void ensureSnapshotCache()
        .then((snapshot) => {
          emitRpcResult(requestId, buildT3ThreadReadResult({
            snapshot,
            threadId: parsed?.params?.threadId,
          }));
        })
        .catch((error) => {
          emitRpcError(requestId, normalizeNonEmptyString(error?.message) || "Failed to load the T3 thread.");
        });
      return true;
    }

    if (method === "thread/resume") {
      void ensureSnapshotCache()
        .then((snapshot) => {
          const threadId = normalizeNonEmptyString(parsed?.params?.threadId);
          const resumeSupport = resolveT3ResumeSupport(snapshot, threadId);
          if (!resumeSupport.ok) {
            emitRpcResult(requestId, resumeSupport.result);
            return;
          }
          resumedThreadIds.add(threadId);
          emitRpcResult(requestId, {
            ok: true,
            resumed: true,
            liveUpdatesAttached: true,
            reason: "The read-only T3 adapter attached bridge-managed live updates for this T3 thread.",
          });
        })
        .catch((error) => {
          emitRpcError(requestId, normalizeNonEmptyString(error?.message) || "Failed to resume the T3 thread.");
        });
      return true;
    }

    return false;
  }

  return {
    backendProviderKind: runtimeTarget.backendProviderKind,
    describe: transport.describe,
    getRuntimeMetadata() {
      return { ...runtimeMetadata };
    },
    kind: runtimeTarget.kind,
    mode: transport.mode,
    onClose: transport.onClose,
    onError: transport.onError,
    onMessage(handler) {
      messageListeners.add(handler);
    },
    onMetadata(handler) {
      metadataListeners.add(handler);
    },
    readOnly: true,
    send(message) {
      const parsed = safeParseJSON(message);
      const method = normalizeNonEmptyString(parsed?.method);
      if (!isRuntimeTargetMethodAllowed({
        targetKind: runtimeTarget.kind,
        method,
      })) {
        throw new Error(
          buildRuntimeTargetMethodRejectionMessage({
            targetKind: runtimeTarget.kind,
            method,
          })
        );
      }

      if (handleReadOnlyRequest(message)) {
        return true;
      }

      return transport.send(message);
    },
    shutdown() {
      shuttingDown = true;
      unsubscribeDomainEvents?.();
      transport.shutdown();
    },
    whenReady() {
      return transport.whenReady().catch((error) => {
        updateRuntimeMetadata({
          runtimeAttachState: "rejected",
          runtimeAttachFailure: normalizeNonEmptyString(error?.message) || "T3 attach failed.",
        });
        throw error;
      });
    },
  };
}

function createListenerBag() {
  return {
    onMessage: null,
    onClose: null,
    onError: null,
    emitMessage(message) {
      this.onMessage?.(message);
    },
    emitClose(...args) {
      this.onClose?.(...args);
    },
    emitError(error) {
      this.onError?.(error);
    },
  };
}

function createHandlerBag() {
  const handlers = new Set();
  return {
    add(handler) {
      if (typeof handler === "function") {
        handlers.add(handler);
      }
    },
    emit(value) {
      for (const handler of handlers) {
        handler(value);
      }
    },
  };
}

function resolveT3ResumeSupport(snapshot, threadId) {
  const normalizedThreadId = normalizeNonEmptyString(threadId);
  if (!normalizedThreadId) {
    throw new Error("T3 thread resume requires a threadId.");
  }

  const thread = findT3Thread(snapshot, normalizedThreadId);
  if (!thread) {
    throw new Error(`T3 thread not found: ${normalizedThreadId}`);
  }

  if (!isT3ThreadEligibleForLiveUpdates(snapshot, thread)) {
    const provider = normalizeNonEmptyString(thread?.modelSelection?.provider).toLowerCase();
    const workspacePath = resolveT3ThreadWorkspacePath(snapshot, thread);
    return {
      ok: false,
      result: {
        ok: false,
        resumed: false,
        liveUpdatesAttached: false,
        reason: provider && provider !== "codex"
          ? `The read-only T3 adapter only attaches live updates for Codex-backed threads; this thread uses ${provider}.`
          : workspacePath
            ? "The read-only T3 adapter only attaches live updates for threads whose local workspace mapping still resolves."
            : "The read-only T3 adapter only attaches live updates for threads with a usable local workspace mapping.",
      },
    };
  }

  return {
    ok: true,
  };
}

function buildTurnLifecycleNotifications({
  beforeThread,
  afterThread,
  threadId,
}) {
  const beforeTurn = normalizeLatestTurn(beforeThread?.latestTurn);
  const afterTurn = normalizeLatestTurn(afterThread?.latestTurn);
  if (!afterTurn?.turnId) {
    return [];
  }

  if (beforeTurn?.state !== "running" && afterTurn.state === "running") {
    return [{
      method: "turn/started",
      params: {
        threadId,
        turnId: afterTurn.turnId,
      },
    }];
  }

  if (beforeTurn?.state === "running" && afterTurn.state === "running" && beforeTurn.turnId !== afterTurn.turnId) {
    return [{
      method: "turn/started",
      params: {
        threadId,
        turnId: afterTurn.turnId,
      },
    }];
  }

  if (beforeTurn?.state === "running" && afterTurn.state !== "running") {
    if (afterTurn.state === "error") {
      return [{
        method: "turn/failed",
        params: {
          threadId,
          turnId: afterTurn.turnId,
        },
      }];
    }

    return [{
      method: "turn/completed",
      params: {
        threadId,
        turnId: afterTurn.turnId,
        status: afterTurn.state === "interrupted" ? "interrupted" : "completed",
      },
    }];
  }

  return [];
}

function normalizeLatestTurn(latestTurn) {
  if (!latestTurn || typeof latestTurn !== "object") {
    return null;
  }
  const turnId = normalizeNonEmptyString(latestTurn.turnId);
  if (!turnId) {
    return null;
  }

  return {
    turnId,
    state: normalizeNonEmptyString(latestTurn.state).toLowerCase() || "completed",
  };
}

function findT3Thread(snapshot, threadId) {
  const normalizedThreadId = normalizeNonEmptyString(threadId);
  if (!normalizedThreadId) {
    return null;
  }
  const threads = Array.isArray(snapshot?.threads) ? snapshot.threads : [];
  return threads.find((thread) =>
    normalizeNonEmptyString(thread?.id) === normalizedThreadId && !thread?.deletedAt) || null;
}

function findT3Project(snapshot, projectId) {
  const normalizedProjectId = normalizeNonEmptyString(projectId);
  if (!normalizedProjectId) {
    return null;
  }
  const projects = Array.isArray(snapshot?.projects) ? snapshot.projects : [];
  return projects.find((project) =>
    normalizeNonEmptyString(project?.id) === normalizedProjectId && !project?.deletedAt) || null;
}

function resolveT3ThreadWorkspacePath(snapshot, thread) {
  const worktreePath = normalizeNonEmptyString(thread?.worktreePath);
  if (worktreePath) {
    return worktreePath;
  }
  const project = findT3Project(snapshot, thread?.projectId);
  return normalizeNonEmptyString(project?.workspaceRoot) || "";
}

function isT3ThreadEligibleForLiveUpdates(snapshot, thread) {
  const provider = normalizeNonEmptyString(thread?.modelSelection?.provider).toLowerCase();
  if (provider && provider !== "codex") {
    return false;
  }

  const workspacePath = resolveT3ThreadWorkspacePath(snapshot, thread);
  if (!workspacePath) {
    return false;
  }
  return pathExists(workspacePath);
}

function findThreadMessage(thread, messageId) {
  const normalizedMessageId = normalizeNonEmptyString(messageId);
  if (!normalizedMessageId) {
    return null;
  }
  const messages = Array.isArray(thread?.messages) ? thread.messages : [];
  return messages.find((message) => normalizeNonEmptyString(message?.id) === normalizedMessageId) || null;
}

function pathExists(candidatePath) {
  try {
    return fs.existsSync(path.normalize(candidatePath));
  } catch {
    return false;
  }
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

function safeParseJSON(value) {
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

module.exports = {
  createRuntimeAdapter,
};
