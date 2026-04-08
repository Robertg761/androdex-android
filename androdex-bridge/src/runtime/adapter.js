// FILE: runtime/adapter.js
// Purpose: Provides the first runtime-target adapter seam so the bridge can switch host runtimes without pushing target-specific lifecycle into workspace orchestration.
// Layer: CLI helper
// Exports: createRuntimeAdapter
// Depends on: ../codex/transport, ./method-policy, ./t3-protocol, ./t3-suitability, ./target-config

const { createCodexTransport } = require("../codex/transport");
const { createHash, randomUUID } = require("crypto");
const {
  applyT3EventsToSnapshot,
  buildT3ThreadRollbackResult,
  buildT3ThreadListResult,
  buildT3ThreadReadResult,
  createEmptyT3Snapshot,
  describeT3ThreadCapabilities,
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
const T3_RECONNECT_DELAY_MS = 50;
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
  logEvent = null,
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
      logEvent,
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
  logEvent = null,
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
  let reconnectPromise = null;
  let replayScope = null;
  let persistedReplayCursor = 0;
  let shuttingDown = false;
  let hasReachedReadyState = false;
  const resumedThreadIds = new Set();
  const liveActivityStateByThread = new Map();
  const syntheticApprovalRequestsByBridgeId = new Map();
  const syntheticApprovalBridgeIdByThreadRequest = new Map();
  const syntheticUserInputRequestsByBridgeId = new Map();
  const syntheticUserInputBridgeIdByThreadRequest = new Map();
  const appliedDomainEventWaiters = new Set();
  let transport = null;
  const emitAdapterLog = createStructuredRuntimeLogger({
    logEvent,
    runtimeTarget: runtimeTarget.kind,
    getReplayScope() {
      return replayScope;
    },
    getRuntimeMetadata() {
      return runtimeMetadata;
    },
  });
  const closeListeners = createHandlerBag();
  const errorListeners = createHandlerBag();
  const initialTransport = createAndBindTransport();

  function updateRuntimeMetadata(nextValues) {
    runtimeMetadata = {
      ...runtimeMetadata,
      ...(nextValues || {}),
    };
    metadataListeners.emit(runtimeMetadata);
  }

  async function performSuitabilityProbe({ request }) {
    emitAdapterLog("attach_probe_started", {
      reasonCode: "attach_probe_started",
    });
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
    emitAdapterLog("attach_probe_validated", {
      reasonCode: "attach_probe_validated",
    });
    replayScope = {
      runtimeStateRoot: normalizeNonEmptyString(validatedMetadata.runtimeStateRoot),
      runtimeTarget: runtimeTarget.kind,
    };
    persistedReplayCursor = normalizeSequenceNumber(loadReplayCursor?.(replayScope));
    const resumeSequence = Math.max(
      appliedSequence,
      persistedReplayCursor,
    );
    updateRuntimeMetadata({
      runtimeReplaySequence: persistedReplayCursor,
    });
    startDomainEventSubscription();
    await refreshSnapshotCache();
    appliedSequence = normalizeSequenceNumber(snapshotCache?.snapshotSequence);
    if (resumeSequence > appliedSequence) {
      await recoverMissingDomainEvents(appliedSequence, {
        reason: "bootstrap_resume",
        suppressNotificationsThroughSequence: resumeSequence,
      });
    } else {
      persistAppliedReplaySequence(appliedSequence);
    }
    publishReadModelMetadata();
    liveDomainEventsReady = true;
    await flushPendingDomainEvents();
    updateRuntimeMetadata({
      runtimeAttachState: "ready",
      runtimeAttachFailure: null,
      runtimeSubscriptionState: "live",
    });
    emitAdapterLog("attach_ready", {
      reasonCode: "attach_ready",
      restoredReplaySequence: resumeSequence,
    });
    hasReachedReadyState = true;
  }

  async function refreshSnapshotCache() {
    const snapshot = await transport.request("orchestration.getSnapshot", {}, {
      timeoutMs: T3_ATTACH_TIMEOUT_MS,
    });
    snapshotCache = snapshot && typeof snapshot === "object" ? snapshot : createEmptyT3Snapshot();
    updateRuntimeMetadata({
      runtimeSnapshotSequence: normalizeSequenceNumber(snapshotCache?.snapshotSequence),
    });
    emitAdapterLog("snapshot_loaded", {
      reasonCode: hasReachedReadyState ? "snapshot_refresh" : "snapshot_bootstrap",
      threadCount: Array.isArray(snapshotCache?.threads) ? snapshotCache.threads.length : 0,
      projectCount: Array.isArray(snapshotCache?.projects) ? snapshotCache.projects.length : 0,
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
          await recoverMissingDomainEvents(appliedSequence, {
            reason: "resubscribe",
          });
          await flushPendingDomainEvents();
          updateRuntimeMetadata({
            runtimeAttachFailure: null,
            runtimeSubscriptionState: "live",
          });
          emitAdapterLog("subscription_resynced", {
            reasonCode: "subscription_resynced",
          });
        }
      })
      .catch((error) => {
        updateRuntimeMetadata({
          runtimeSubscriptionState: "error",
          runtimeAttachFailure: normalizeNonEmptyString(error?.message) || runtimeMetadata.runtimeAttachFailure,
        });
        emitAdapterLog("subscription_resync_failed", {
          reasonCode: classifyRuntimeFailure(error?.message),
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
          emitAdapterLog("live_gap_detected", {
            reasonCode: "live_gap_detected",
            fromSequenceExclusive: appliedSequence,
            nextSequence,
          });
          const recoveredSequence = await recoverMissingDomainEvents(appliedSequence, {
            reason: "live_gap",
          });
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

  function recoverMissingDomainEvents(fromSequenceExclusive, {
    reason = "replay",
    suppressNotificationsThroughSequence = 0,
  } = {}) {
    if (replayRecoveryPromise) {
      return replayRecoveryPromise;
    }

    updateRuntimeMetadata({
      runtimeSubscriptionState: "replaying",
    });
    emitAdapterLog("replay_requested", {
      reasonCode: reason,
      fromSequenceExclusive,
      suppressNotificationsThroughSequence,
    });
    replayRecoveryPromise = transport.request("orchestration.replayEvents", {
      fromSequenceExclusive,
    }, {
      timeoutMs: T3_ATTACH_TIMEOUT_MS,
    }).then((events) => {
      const replayResult = applyEventsToReadModel(Array.isArray(events) ? events : [], {
        suppressNotificationsThroughSequence,
      });
      emitAdapterLog("replay_applied", {
        reasonCode: reason,
        fromSequenceExclusive,
        suppressNotificationsThroughSequence,
        appliedCount: replayResult.appliedCount,
        duplicateCount: replayResult.duplicateCount,
        toSequenceInclusive: replayResult.lastSequence,
      });
      return appliedSequence;
    }).catch((error) => {
      emitAdapterLog("replay_failed", {
        reasonCode: classifyRuntimeFailure(error?.message),
        fromSequenceExclusive,
      });
      throw error;
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

  function applyEventsToReadModel(events, {
    suppressNotificationsThroughSequence = 0,
  } = {}) {
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
        const eventSequence = normalizeSequenceNumber(event?.sequence);
        resolveAppliedDomainEventWaiters(event);
        if (eventSequence > suppressNotificationsThroughSequence) {
          emitLiveNotificationsForAppliedT3Event({
            beforeSnapshot,
            afterSnapshot: workingSnapshot,
            event,
          });
        }
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

  function createAppliedDomainEventWaiter({
    match,
    timeoutMs = T3_ATTACH_TIMEOUT_MS,
    errorMessage = "Timed out waiting for the T3 event to apply.",
  }) {
    if (typeof match !== "function") {
      throw new Error("Applied domain event waiters require a match function.");
    }

    let settled = false;
    let waiter = null;
    let timer = null;
    const promise = new Promise((resolve, reject) => {
      timer = setTimeout(() => {
        if (settled) {
          return;
        }
        settled = true;
        appliedDomainEventWaiters.delete(waiter);
        reject(new Error(errorMessage));
      }, timeoutMs);
      waiter = {
        match,
        resolve(event) {
          if (settled) {
            return;
          }
          settled = true;
          clearTimeout(timer);
          appliedDomainEventWaiters.delete(waiter);
          resolve(event);
        },
      };
      appliedDomainEventWaiters.add(waiter);
    });

    return {
      promise,
      cancel() {
        if (settled) {
          return;
        }
        settled = true;
        clearTimeout(timer);
        appliedDomainEventWaiters.delete(waiter);
      },
    };
  }

  function resolveAppliedDomainEventWaiters(event) {
    if (appliedDomainEventWaiters.size === 0) {
      return;
    }
    for (const waiter of [...appliedDomainEventWaiters]) {
      let matched = false;
      try {
        matched = waiter.match(event) === true;
      } catch {
        matched = false;
      }
      if (matched) {
        waiter.resolve(event);
      }
    }
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

    if (eventType === "thread.activity-appended") {
      const activity = event?.payload?.activity;
      for (const notification of buildThreadActivityNotifications({
        activity,
        onApprovalCleared({ requestId }) {
          emitSyntheticApprovalCleared({
            requestId,
            threadId,
          });
        },
        onApprovalRequested({ activity: nextActivity, payload, turnId }) {
          emitSyntheticApprovalRequest({
            activity: nextActivity,
            payload,
            threadId,
            turnId,
          });
        },
        onUserInputCleared({ requestId }) {
          emitSyntheticUserInputCleared({
            requestId,
            threadId,
          });
        },
        onUserInputRequested({ activity: nextActivity, payload, turnId }) {
          emitSyntheticUserInputRequest({
            activity: nextActivity,
            payload,
            threadId,
            turnId,
          });
        },
        threadId,
        threadActivityState: getOrCreateThreadLiveActivityState(threadId),
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

  function getOrCreateThreadLiveActivityState(threadId) {
    const normalizedThreadId = normalizeNonEmptyString(threadId);
    const existing = liveActivityStateByThread.get(normalizedThreadId);
    if (existing) {
      return existing;
    }

    const created = {
      notificationFingerprintByActivityId: new Map(),
      approvalItemIdsByRequestId: new Map(),
      taskItemIdsByTaskId: new Map(),
      toolItemIdsByKey: new Map(),
      userInputItemIdsByRequestId: new Map(),
    };
    liveActivityStateByThread.set(normalizedThreadId, created);
    return created;
  }

  function buildSyntheticApprovalRequestId({
    threadId,
    requestId,
  }) {
    return `t3-approval-request:${threadId}:${requestId}`;
  }

  function buildSyntheticApprovalMethod(requestKind) {
    const normalizedRequestKind = normalizeApprovalRequestKind(requestKind);
    if (normalizedRequestKind === "file-read") {
      return "item/fileRead/requestApproval";
    }
    if (normalizedRequestKind === "file-change") {
      return "item/fileChange/requestApproval";
    }
    return "item/commandExecution/requestApproval";
  }

  function emitSyntheticApprovalRequest({
    activity,
    payload,
    threadId,
    turnId,
  }) {
    const requestId = normalizeNonEmptyString(payload?.requestId);
    const requestKind = normalizeApprovalRequestKind(
      payload?.requestKind || payload?.requestType
    );
    if (!requestId || !requestKind) {
      return;
    }

    const key = `${threadId}:${requestId}`;
    if (syntheticApprovalBridgeIdByThreadRequest.has(key)) {
      return;
    }

    const bridgeRequestId = buildSyntheticApprovalRequestId({
      threadId,
      requestId,
    });
    syntheticApprovalBridgeIdByThreadRequest.set(key, bridgeRequestId);
    syntheticApprovalRequestsByBridgeId.set(bridgeRequestId, {
      requestId,
      requestKind,
      threadId,
      turnId,
    });

    emitMessage(JSON.stringify({
      id: bridgeRequestId,
      method: buildSyntheticApprovalMethod(requestKind),
      params: {
        command: requestKind === "command"
          ? firstNonEmptyString([
            payload?.detail,
            activity?.summary,
          ])
          : null,
        reason: firstNonEmptyString([
          payload?.detail,
          activity?.summary,
        ]),
        requestId,
        requestKind,
        threadId,
        turnId,
      },
    }));
  }

  function emitSyntheticApprovalCleared({
    requestId,
    threadId,
  }) {
    if (!requestId || !threadId) {
      return;
    }
    const key = `${threadId}:${requestId}`;
    const bridgeRequestId = syntheticApprovalBridgeIdByThreadRequest.get(key);
    if (bridgeRequestId) {
      syntheticApprovalBridgeIdByThreadRequest.delete(key);
      syntheticApprovalRequestsByBridgeId.delete(bridgeRequestId);
    }
    emitNotification("approval/cleared", {
      requestId,
      threadId,
    });
  }

  function emitOutstandingSyntheticApprovalRequest({
    threadId,
    thread,
  }) {
    const pendingApprovals = listPendingT3Approvals(thread);
    if (pendingApprovals.length === 0) {
      return;
    }
    const latestPendingApproval = pendingApprovals[pendingApprovals.length - 1];
    emitSyntheticApprovalRequest({
      activity: latestPendingApproval,
      payload: latestPendingApproval.payload,
      threadId,
      turnId: normalizeNonEmptyString(latestPendingApproval.turnId) || null,
    });
  }

  function buildSyntheticUserInputRequestId({
    threadId,
    requestId,
  }) {
    return `t3-user-input-request:${threadId}:${requestId}`;
  }

  function emitSyntheticUserInputRequest({
    activity,
    payload,
    threadId,
    turnId,
  }) {
    const requestId = normalizeNonEmptyString(payload?.requestId);
    const questions = Array.isArray(payload?.questions) ? payload.questions : [];
    if (!requestId || questions.length === 0) {
      return;
    }

    const key = `${threadId}:${requestId}`;
    if (syntheticUserInputBridgeIdByThreadRequest.has(key)) {
      return;
    }

    const bridgeRequestId = buildSyntheticUserInputRequestId({
      threadId,
      requestId,
    });
    syntheticUserInputBridgeIdByThreadRequest.set(key, bridgeRequestId);
    syntheticUserInputRequestsByBridgeId.set(bridgeRequestId, {
      requestId,
      threadId,
      turnId,
    });

    emitMessage(JSON.stringify({
      id: bridgeRequestId,
      method: "item/tool/requestUserInput",
      params: {
        title: firstNonEmptyString([
          payload?.title,
          activity?.summary,
        ]) || null,
        message: firstNonEmptyString([
          payload?.message,
          payload?.detail,
          activity?.summary,
        ]) || null,
        questions,
        requestId,
        threadId,
        turnId,
      },
    }));
  }

  function emitSyntheticUserInputCleared({
    requestId,
    threadId,
  }) {
    if (!requestId || !threadId) {
      return;
    }
    const key = `${threadId}:${requestId}`;
    const bridgeRequestId = syntheticUserInputBridgeIdByThreadRequest.get(key);
    if (bridgeRequestId) {
      syntheticUserInputBridgeIdByThreadRequest.delete(key);
      syntheticUserInputRequestsByBridgeId.delete(bridgeRequestId);
    }
    emitNotification("user-input/cleared", {
      requestId,
      threadId,
    });
  }

  function emitOutstandingSyntheticUserInputRequest({
    threadId,
    thread,
  }) {
    const pendingUserInputs = listPendingT3UserInputs(thread);
    if (pendingUserInputs.length === 0) {
      return;
    }
    const latestPendingUserInput = pendingUserInputs[pendingUserInputs.length - 1];
    emitSyntheticUserInputRequest({
      activity: latestPendingUserInput,
      payload: latestPendingUserInput.payload,
      threadId,
      turnId: normalizeNonEmptyString(latestPendingUserInput.turnId) || null,
    });
  }

  function createAndBindTransport() {
    const nextTransport = createT3EndpointTransport({
      endpoint: normalizedEndpoint,
      WebSocketImpl,
      onBeforeReadyRequest: performSuitabilityProbe,
    });

    nextTransport.onMessage((message) => {
      if (transport !== nextTransport) {
        return;
      }
      messageListeners.emit(message);
    });

    nextTransport.onClose((_code, reason) => {
      if (transport !== nextTransport) {
        return;
      }
      transport = null;
      handleTransportDisconnect(
        normalizeNonEmptyString(reason) || "The T3 endpoint connection closed."
      );
    });

    nextTransport.onError((error) => {
      if (transport !== nextTransport) {
        return;
      }
      const detail = normalizeNonEmptyString(error?.message);
      if (detail) {
        updateRuntimeMetadata({
          runtimeAttachFailure: detail,
        });
      }
    });

    transport = nextTransport;
    return nextTransport;
  }

  function handleTransportDisconnect(detail) {
    if (shuttingDown) {
      closeListeners.emit();
      return;
    }

    if (!hasReachedReadyState) {
      return;
    }

    unsubscribeDomainEvents?.();
    unsubscribeDomainEvents = null;
    pendingDomainEvents = [];
    pendingEventFlush = Promise.resolve();
    liveDomainEventsReady = false;
    replayRecoveryPromise = null;
    resubscribePromise = null;
    updateRuntimeMetadata({
      runtimeAttachState: "reconnecting",
      runtimeAttachFailure: normalizeNonEmptyString(detail) || runtimeMetadata.runtimeAttachFailure,
      runtimeSubscriptionState: "reconnecting",
    });
    emitAdapterLog("transport_disconnected", {
      reasonCode: classifyRuntimeFailure(detail),
    });
    scheduleReconnect();
  }

  function scheduleReconnect() {
    if (shuttingDown || reconnectPromise) {
      return reconnectPromise;
    }

    reconnectPromise = Promise.resolve()
      .then(async () => {
        while (!shuttingDown) {
          const nextTransport = createAndBindTransport();
          try {
            await nextTransport.whenReady();
            return;
          } catch (error) {
            const detail = normalizeNonEmptyString(error?.message) || "T3 reconnect failed.";
            updateRuntimeMetadata({
              runtimeAttachState: "reconnecting",
              runtimeAttachFailure: detail,
              runtimeSubscriptionState: "error",
            });
            emitAdapterLog("reconnect_retry_failed", {
              reasonCode: classifyRuntimeFailure(detail),
            });
            if (shuttingDown) {
              return;
            }
            await wait(T3_RECONNECT_DELAY_MS);
          }
        }
      })
      .finally(() => {
        reconnectPromise = null;
      });
    return reconnectPromise;
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
            emitAdapterLog("action_gated", {
              reasonCode: "resume_live_updates_unsupported",
              method,
              threadId,
              companionSupportState: normalizeNonEmptyString(
                resumeSupport.result?.threadCapabilities?.companionSupportState
              ) || null,
            });
            emitRpcResult(requestId, resumeSupport.result);
            return;
          }
          resumedThreadIds.add(threadId);
          emitOutstandingSyntheticApprovalRequest({
            threadId,
            thread: findT3Thread(snapshot, threadId),
          });
          emitOutstandingSyntheticUserInputRequest({
            threadId,
            thread: findT3Thread(snapshot, threadId),
          });
          emitRpcResult(requestId, {
            ok: true,
            resumed: true,
            liveUpdatesAttached: true,
            reason: "The read-only T3 adapter attached bridge-managed live updates for this T3 thread.",
            threadCapabilities: resumeSupport.threadCapabilities,
          });
        })
        .catch((error) => {
          emitRpcError(requestId, normalizeNonEmptyString(error?.message) || "Failed to resume the T3 thread.");
        });
      return true;
    }

    if (method === "bridge/approval/respond") {
      void ensureSnapshotCache()
        .then(() => {
          const bridgeRequestId = normalizeNonEmptyString(
            parsed?.params?.bridgeRequestId || parsed?.params?.requestId
          );
          if (!bridgeRequestId) {
            throw new Error("T3 approval response requires a bridgeRequestId.");
          }

          const pendingApproval = syntheticApprovalRequestsByBridgeId.get(bridgeRequestId);
          if (!pendingApproval) {
            throw new Error("Approval request is no longer pending.");
          }

          const thread = findT3Thread(snapshotCache, pendingApproval.threadId);
          if (!thread || !isT3ApprovalRequestPending(thread, pendingApproval.requestId)) {
            syntheticApprovalRequestsByBridgeId.delete(bridgeRequestId);
            syntheticApprovalBridgeIdByThreadRequest.delete(
              `${pendingApproval.threadId}:${pendingApproval.requestId}`
            );
            throw new Error("Approval request is no longer pending.");
          }

          const decision = normalizeApprovalDecision(
            parsed?.params?.decision || parsed?.params?.result
          );
          if (!decision) {
            throw new Error(
              "T3 approval responses must be one of: accept, acceptForSession, decline, cancel."
            );
          }

          return transport.request("orchestration.dispatchCommand", {
            type: "thread.approval.respond",
            commandId: randomUUID(),
            threadId: pendingApproval.threadId,
            requestId: pendingApproval.requestId,
            decision,
            createdAt: new Date().toISOString(),
          }, {
            timeoutMs: T3_ATTACH_TIMEOUT_MS,
          }).then((dispatchResult) => {
            emitAdapterLog("command_dispatched", {
              reasonCode: "approval_response_dispatched",
              method,
              threadId: pendingApproval.threadId,
              sequence: normalizeSequenceNumber(dispatchResult?.sequence),
            });
            emitRpcResult(requestId, {
              ok: true,
              accepted: decision === "accept" || decision === "acceptForSession",
              sequence: normalizeSequenceNumber(dispatchResult?.sequence),
            });
          });
        })
        .catch((error) => {
          emitRpcError(
            requestId,
            normalizeNonEmptyString(error?.message) || "Failed to respond to the T3 approval request."
          );
        });
      return true;
    }

    if (method === "bridge/user-input/respond") {
      void ensureSnapshotCache()
        .then(() => {
          const bridgeRequestId = normalizeNonEmptyString(
            parsed?.params?.bridgeRequestId || parsed?.params?.requestId
          );
          if (!bridgeRequestId) {
            throw new Error("T3 user-input response requires a bridgeRequestId.");
          }

          const pendingUserInput = syntheticUserInputRequestsByBridgeId.get(bridgeRequestId);
          if (!pendingUserInput) {
            throw new Error("Tool input request is no longer pending.");
          }

          const thread = findT3Thread(snapshotCache, pendingUserInput.threadId);
          if (!thread || !isT3UserInputRequestPending(thread, pendingUserInput.requestId)) {
            syntheticUserInputRequestsByBridgeId.delete(bridgeRequestId);
            syntheticUserInputBridgeIdByThreadRequest.delete(
              `${pendingUserInput.threadId}:${pendingUserInput.requestId}`
            );
            throw new Error("Tool input request is no longer pending.");
          }

          const answers = sanitizeBridgeUserInputAnswers(parsed?.params?.answers);
          if (!answers || Object.keys(answers).length === 0) {
            throw new Error("T3 user-input responses require at least one answer.");
          }

          return transport.request("orchestration.dispatchCommand", {
            type: "thread.user-input.respond",
            commandId: randomUUID(),
            threadId: pendingUserInput.threadId,
            requestId: pendingUserInput.requestId,
            answers,
            createdAt: new Date().toISOString(),
          }, {
            timeoutMs: T3_ATTACH_TIMEOUT_MS,
          }).then((dispatchResult) => {
            emitAdapterLog("command_dispatched", {
              reasonCode: "user_input_response_dispatched",
              method,
              threadId: pendingUserInput.threadId,
              sequence: normalizeSequenceNumber(dispatchResult?.sequence),
            });
            emitRpcResult(requestId, {
              ok: true,
              answered: true,
              sequence: normalizeSequenceNumber(dispatchResult?.sequence),
            });
          });
        })
        .catch((error) => {
          emitRpcError(
            requestId,
            normalizeNonEmptyString(error?.message) || "Failed to respond to the T3 user-input request."
          );
        });
      return true;
    }

    if (method === "turn/start") {
      void ensureSnapshotCache()
        .then((snapshot) => {
          const threadId = normalizeNonEmptyString(parsed?.params?.threadId);
          if (!threadId) {
            throw new Error("T3 turn start requires a threadId.");
          }

          const thread = findT3Thread(snapshot, threadId);
          if (!thread) {
            throw new Error(`T3 thread not found: ${threadId}`);
          }

          const threadCapabilities = describeT3ThreadCapabilities({
            snapshot,
            thread,
          });
          if (!threadCapabilities?.turnStart?.supported) {
            emitAdapterLog("action_gated", {
              reasonCode: "turn_start_unsupported",
              method,
              threadId,
              companionSupportState: normalizeNonEmptyString(
                threadCapabilities?.companionSupportState
              ) || null,
            });
            emitRpcError(
              requestId,
              normalizeNonEmptyString(threadCapabilities?.turnStart?.reason)
                || "This T3 thread does not support turn start from Androdex yet."
            );
            return;
          }

          const command = buildT3TurnStartCommand({
            threadId,
            thread,
            params: parsed?.params,
          });
          return prepareT3ThreadForTurnStart({
            threadId,
            thread,
            params: parsed?.params,
            transport,
          }).then(() => transport.request("orchestration.dispatchCommand", command, {
            timeoutMs: T3_ATTACH_TIMEOUT_MS,
          })).then((dispatchResult) => {
            emitAdapterLog("command_dispatched", {
              reasonCode: "turn_start_dispatched",
              method,
              threadId,
              sequence: normalizeSequenceNumber(dispatchResult?.sequence),
            });
            emitRpcResult(requestId, {
              ok: true,
              started: true,
              sequence: normalizeSequenceNumber(dispatchResult?.sequence),
            });
          });
        })
        .catch((error) => {
          emitRpcError(
            requestId,
            normalizeNonEmptyString(error?.message) || "Failed to start the T3 turn."
          );
        });
      return true;
    }

    if (method === "thread/rollback") {
      void ensureSnapshotCache()
        .then((snapshot) => {
          const threadId = normalizeNonEmptyString(parsed?.params?.threadId);
          if (!threadId) {
            throw new Error("T3 rollback requires a threadId.");
          }

          const thread = findT3Thread(snapshot, threadId);
          if (!thread) {
            throw new Error(`T3 thread not found: ${threadId}`);
          }

          const threadCapabilities = describeT3ThreadCapabilities({
            snapshot,
            thread,
          });
          if (!threadCapabilities?.checkpointRollback?.supported) {
            emitAdapterLog("action_gated", {
              reasonCode: "checkpoint_rollback_unsupported",
              method,
              threadId,
              companionSupportState: normalizeNonEmptyString(
                threadCapabilities?.companionSupportState
              ) || null,
            });
            emitRpcError(
              requestId,
              normalizeNonEmptyString(threadCapabilities?.checkpointRollback?.reason)
                || "This T3 thread does not support rollback from Androdex yet."
            );
            return;
          }

          const optimisticRollback = buildT3ThreadRollbackResult({
            snapshot,
            threadId,
            numTurns: parsed?.params?.numTurns,
            occurredAt: new Date().toISOString(),
          });
          const revertWaiter = createAppliedDomainEventWaiter({
            match(event) {
              const eventType = normalizeNonEmptyString(event?.type);
              const eventThreadId = normalizeNonEmptyString(event?.payload?.threadId)
                || normalizeNonEmptyString(event?.aggregateId);
              return eventType === "thread.reverted"
                && eventThreadId === threadId
                && normalizeSequenceNumber(event?.payload?.turnCount) === optimisticRollback.targetTurnCount;
            },
            errorMessage: "Timed out waiting for the T3 rollback to finish.",
          });

          return transport.request("orchestration.dispatchCommand", {
            type: "thread.checkpoint.revert",
            commandId: randomUUID(),
            threadId,
            turnCount: optimisticRollback.targetTurnCount,
            createdAt: new Date().toISOString(),
          }, {
            timeoutMs: T3_ATTACH_TIMEOUT_MS,
          }).then(async (dispatchResult) => {
            let resolvedSnapshot = await refreshSnapshotCache();
            if (!doesSnapshotReflectT3Rollback({
              snapshot: resolvedSnapshot,
              threadId,
              targetTurnCount: optimisticRollback.targetTurnCount,
            })) {
              await revertWaiter.promise;
              resolvedSnapshot = await ensureSnapshotCache();
            }
            emitAdapterLog("command_dispatched", {
              reasonCode: "checkpoint_rollback_dispatched",
              method,
              threadId,
              sequence: normalizeSequenceNumber(dispatchResult?.sequence),
              turnCount: optimisticRollback.targetTurnCount,
            });
            emitRpcResult(requestId, {
              ok: true,
              thread: buildT3ThreadReadResult({
                snapshot: resolvedSnapshot,
                threadId,
              }).thread,
              sequence: normalizeSequenceNumber(dispatchResult?.sequence),
            });
          }).finally(() => {
            revertWaiter.cancel();
          });
        })
        .catch((error) => {
          emitRpcError(
            requestId,
            normalizeNonEmptyString(error?.message) || "Failed to roll back the T3 thread."
          );
        });
      return true;
    }

    if (method === "turn/interrupt") {
      void ensureSnapshotCache()
        .then((snapshot) => {
          const threadId = normalizeNonEmptyString(parsed?.params?.threadId);
          if (!threadId) {
            throw new Error("T3 turn interrupt requires a threadId.");
          }

          const thread = findT3Thread(snapshot, threadId);
          if (!thread) {
            throw new Error(`T3 thread not found: ${threadId}`);
          }

          const threadCapabilities = describeT3ThreadCapabilities({
            snapshot,
            thread,
          });
          if (!threadCapabilities?.turnInterrupt?.supported) {
            emitAdapterLog("action_gated", {
              reasonCode: "interrupt_unsupported",
              method,
              threadId,
              companionSupportState: normalizeNonEmptyString(
                threadCapabilities?.companionSupportState
              ) || null,
            });
            emitRpcError(
              requestId,
              normalizeNonEmptyString(threadCapabilities?.turnInterrupt?.reason)
                || "This T3 thread does not support interrupt from Androdex yet."
            );
            return;
          }

          const requestedTurnId = normalizeNonEmptyString(parsed?.params?.turnId);
          if (!isT3InterruptCurrentlyAvailable(thread, requestedTurnId)) {
            emitAdapterLog("action_gated", {
              reasonCode: "interrupt_not_running",
              method,
              threadId,
            });
            emitRpcError(requestId, "No active run is available to stop.");
            return;
          }

          return transport.request("orchestration.dispatchCommand", {
            type: "thread.turn.interrupt",
            commandId: randomUUID(),
            threadId,
            ...(requestedTurnId ? { turnId: requestedTurnId } : {}),
            createdAt: new Date().toISOString(),
          }, {
            timeoutMs: T3_ATTACH_TIMEOUT_MS,
          }).then((dispatchResult) => {
            emitAdapterLog("command_dispatched", {
              reasonCode: "turn_interrupt_dispatched",
              method,
              threadId,
              sequence: normalizeSequenceNumber(dispatchResult?.sequence),
            });
            emitRpcResult(requestId, {
              ok: true,
              interrupted: true,
              sequence: normalizeSequenceNumber(dispatchResult?.sequence),
            });
          });
        })
        .catch((error) => {
          emitRpcError(
            requestId,
            normalizeNonEmptyString(error?.message) || "Failed to interrupt the T3 run."
          );
        });
      return true;
    }

    return false;
  }

  return {
    backendProviderKind: runtimeTarget.backendProviderKind,
    describe: initialTransport.describe,
    getRuntimeMetadata() {
      return { ...runtimeMetadata };
    },
    kind: runtimeTarget.kind,
    mode: initialTransport.mode,
    onClose(handler) {
      closeListeners.add(handler);
    },
    onError(handler) {
      errorListeners.add(handler);
    },
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
        emitAdapterLog("action_gated", {
          reasonCode: "runtime_method_rejected",
          method,
          threadId: normalizeNonEmptyString(parsed?.params?.threadId) || null,
        });
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

      return transport?.send(message) ?? false;
    },
    shutdown() {
      shuttingDown = true;
      unsubscribeDomainEvents?.();
      transport?.shutdown();
    },
    whenReady() {
      return initialTransport.whenReady().catch((error) => {
        updateRuntimeMetadata({
          runtimeAttachState: "rejected",
          runtimeAttachFailure: normalizeNonEmptyString(error?.message) || "T3 attach failed.",
        });
        emitAdapterLog("attach_rejected", {
          reasonCode: classifyRuntimeFailure(error?.message),
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

  const threadCapabilities = describeT3ThreadCapabilities({
    snapshot,
    thread,
  });
  if (!threadCapabilities.liveUpdates.supported) {
    return {
      ok: false,
      result: {
        ok: false,
        resumed: false,
        liveUpdatesAttached: false,
        reason: threadCapabilities.liveUpdates.reason,
        threadCapabilities,
      },
    };
  }

  return {
    ok: true,
    threadCapabilities,
  };
}

function isT3InterruptCurrentlyAvailable(thread, requestedTurnId) {
  const normalizedRequestedTurnId = normalizeNonEmptyString(requestedTurnId);
  const latestTurnId = normalizeNonEmptyString(thread?.latestTurn?.turnId);
  const latestTurnState = normalizeNonEmptyString(thread?.latestTurn?.state).toLowerCase();
  const sessionStatus = normalizeNonEmptyString(thread?.session?.status).toLowerCase();
  if (normalizedRequestedTurnId && latestTurnId && normalizedRequestedTurnId !== latestTurnId) {
    return false;
  }
  return latestTurnState === "running" || sessionStatus === "running";
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

function doesSnapshotReflectT3Rollback({
  snapshot,
  threadId,
  targetTurnCount,
}) {
  const thread = findT3Thread(snapshot, threadId);
  if (!thread) {
    return false;
  }
  const maxCheckpointTurnCount = (Array.isArray(thread?.checkpoints) ? thread.checkpoints : []).reduce(
    (maxValue, checkpoint) => Math.max(maxValue, normalizeSequenceNumber(checkpoint?.checkpointTurnCount)),
    0
  );
  return maxCheckpointTurnCount <= Math.max(0, normalizeSequenceNumber(targetTurnCount));
}

function isT3ThreadEligibleForLiveUpdates(snapshot, thread) {
  return describeT3ThreadCapabilities({
    snapshot,
    thread,
  }).liveUpdates.supported;
}

function findThreadMessage(thread, messageId) {
  const normalizedMessageId = normalizeNonEmptyString(messageId);
  if (!normalizedMessageId) {
    return null;
  }
  const messages = Array.isArray(thread?.messages) ? thread.messages : [];
  return messages.find((message) => normalizeNonEmptyString(message?.id) === normalizedMessageId) || null;
}

function normalizeNonEmptyString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

function buildThreadActivityNotifications({
  activity,
  onApprovalCleared = () => {},
  onApprovalRequested = () => {},
  onUserInputCleared = () => {},
  onUserInputRequested = () => {},
  threadId,
  threadActivityState,
}) {
  if (!activity || typeof activity !== "object") {
    return [];
  }

  const kind = normalizeNonEmptyString(activity.kind).toLowerCase();
  const payload = activity.payload && typeof activity.payload === "object" ? activity.payload : {};
  const turnId = normalizeNonEmptyString(activity.turnId) || null;

  if (kind === "turn.plan.updated") {
    const steps = extractPlanStepsFromActivityPayload(payload);
    const explanation = normalizeNonEmptyString(payload.explanation) || null;
    if ((!steps || steps.length === 0) && !explanation) {
      return [];
    }
    return dedupeActivityNotifications({
      activity,
      notifications: [{
        method: "turn/plan/updated",
        params: {
          threadId,
          turnId,
          ...(explanation ? { explanation } : {}),
          ...(steps.length > 0 ? { steps } : {}),
        },
      }],
      threadActivityState,
    });
  }

  if (kind === "task.progress" || kind === "task.completed") {
    const itemId = resolveTaskActivityItemId({
      activity,
      threadActivityState,
      threadId,
    });
    const item = buildExecutionActivityItem({
      activity,
      itemId,
      fallbackType: "activity",
      fallbackStatus: kind === "task.completed"
        ? normalizeNonEmptyString(payload.status) || "completed"
        : "in_progress",
      detail: firstNonEmptyString([
        payload.detail,
        payload.summary,
      ]),
    });
    return dedupeActivityNotifications({
      activity,
      notifications: [{
        method: kind === "task.completed" ? "item/completed" : "item/updated",
        params: {
          threadId,
          turnId,
          itemId,
          item,
        },
      }],
      threadActivityState,
    });
  }

  if (kind === "approval.requested" || kind === "approval.resolved") {
    const requestId = normalizeNonEmptyString(payload.requestId);
    if (kind === "approval.requested") {
      onApprovalRequested({
        activity,
        payload,
        turnId,
      });
    } else if (requestId) {
      onApprovalCleared({
        requestId,
      });
    }
    const itemId = resolveApprovalActivityItemId({
      activity,
      threadActivityState,
      threadId,
    });
    const item = buildExecutionActivityItem({
      activity,
      itemId,
      fallbackType: "activity",
      fallbackStatus: kind === "approval.resolved" ? "completed" : "in_progress",
      detail: firstNonEmptyString([
        payload.detail,
        payload.requestType,
      ]),
    });
    return dedupeActivityNotifications({
      activity,
      notifications: [{
        method: kind === "approval.resolved" ? "item/completed" : "item/updated",
        params: {
          threadId,
          turnId,
          itemId,
          item,
        },
      }],
      threadActivityState,
    });
  }

  if (kind === "tool.started" || kind === "tool.updated" || kind === "tool.completed") {
    const itemId = resolveToolActivityItemId({
      activity,
      threadActivityState,
      threadId,
    });
    const item = buildExecutionActivityItem({
      activity,
      itemId,
      fallbackType: normalizeNonEmptyString(payload.itemType) || "activity",
      fallbackStatus: kind === "tool.completed"
        ? normalizeNonEmptyString(payload.status) || "completed"
        : normalizeNonEmptyString(payload.status) || "in_progress",
      detail: normalizeNonEmptyString(payload.detail),
    });
    return dedupeActivityNotifications({
      activity,
      notifications: [{
        method: kind === "tool.completed" ? "item/completed" : "item/updated",
        params: {
          threadId,
          turnId,
          itemId,
          item,
        },
      }],
      threadActivityState,
    });
  }

  if (kind === "user-input.requested" || kind === "user-input.resolved") {
    const requestId = normalizeNonEmptyString(payload.requestId);
    if (kind === "user-input.requested") {
      onUserInputRequested({
        activity,
        payload,
        turnId,
      });
    } else if (requestId) {
      onUserInputCleared({
        requestId,
      });
    }
    const itemId = resolveUserInputActivityItemId({
      activity,
      threadActivityState,
      threadId,
    });
    const item = buildExecutionActivityItem({
      activity,
      itemId,
      fallbackType: "activity",
      fallbackStatus: kind === "user-input.resolved" ? "completed" : "in_progress",
      detail: normalizeNonEmptyString(payload.detail),
    });
    return dedupeActivityNotifications({
      activity,
      notifications: [{
        method: kind === "user-input.resolved" ? "item/completed" : "item/updated",
        params: {
          threadId,
          turnId,
          itemId,
          item,
        },
      }],
      threadActivityState,
    });
  }

  return [];
}

function isT3ApprovalRequestPending(thread, requestId) {
  const normalizedRequestId = normalizeNonEmptyString(requestId);
  if (!normalizedRequestId) {
    return false;
  }

  return listPendingT3Approvals(thread).some((activity) => {
    const payload = activity?.payload && typeof activity.payload === "object" ? activity.payload : {};
    return normalizeNonEmptyString(payload.requestId) === normalizedRequestId;
  });
}

function listPendingT3Approvals(thread) {
  const openApprovalsByRequestId = new Map();
  const activities = [...(Array.isArray(thread?.activities) ? thread.activities : [])]
    .filter((activity) => activity && typeof activity === "object")
    .sort(compareActivitiesBySequenceAndTime);

  for (const activity of activities) {
    const kind = normalizeNonEmptyString(activity?.kind).toLowerCase();
    const payload = activity?.payload && typeof activity.payload === "object" ? activity.payload : {};
    const activityRequestId = normalizeNonEmptyString(payload.requestId);
    if (!activityRequestId) {
      continue;
    }
    if (kind === "approval.requested") {
      openApprovalsByRequestId.set(activityRequestId, activity);
      continue;
    }
    if (
      kind === "approval.resolved"
      || (kind === "provider.approval.respond.failed"
        && isStalePendingRequestFailureDetail(payload.detail))
    ) {
      openApprovalsByRequestId.delete(activityRequestId);
    }
  }

  return [...openApprovalsByRequestId.values()];
}

function isT3UserInputRequestPending(thread, requestId) {
  const normalizedRequestId = normalizeNonEmptyString(requestId);
  if (!normalizedRequestId) {
    return false;
  }

  return listPendingT3UserInputs(thread).some((activity) => {
    const payload = activity?.payload && typeof activity.payload === "object" ? activity.payload : {};
    return normalizeNonEmptyString(payload.requestId) === normalizedRequestId;
  });
}

function listPendingT3UserInputs(thread) {
  const openUserInputsByRequestId = new Map();
  const activities = [...(Array.isArray(thread?.activities) ? thread.activities : [])]
    .filter((activity) => activity && typeof activity === "object")
    .sort(compareActivitiesBySequenceAndTime);

  for (const activity of activities) {
    const kind = normalizeNonEmptyString(activity?.kind).toLowerCase();
    const payload = activity?.payload && typeof activity.payload === "object" ? activity.payload : {};
    const activityRequestId = normalizeNonEmptyString(payload.requestId);
    if (!activityRequestId) {
      continue;
    }
    if (kind === "user-input.requested") {
      openUserInputsByRequestId.set(activityRequestId, activity);
      continue;
    }
    if (
      kind === "user-input.resolved"
      || (kind === "provider.user-input.respond.failed"
        && isStalePendingRequestFailureDetail(payload.detail))
    ) {
      openUserInputsByRequestId.delete(activityRequestId);
    }
  }

  return [...openUserInputsByRequestId.values()];
}

function dedupeActivityNotifications({
  activity,
  notifications,
  threadActivityState,
}) {
  const normalizedNotifications = Array.isArray(notifications)
    ? notifications.filter(Boolean)
    : [];
  if (normalizedNotifications.length === 0) {
    return [];
  }

  const activityId = normalizeNonEmptyString(activity?.id);
  if (!activityId) {
    return normalizedNotifications;
  }

  const fingerprint = JSON.stringify(normalizedNotifications);
  const previousFingerprint = threadActivityState?.notificationFingerprintByActivityId?.get(activityId);
  if (previousFingerprint === fingerprint) {
    return [];
  }
  threadActivityState?.notificationFingerprintByActivityId?.set(activityId, fingerprint);
  return normalizedNotifications;
}

function extractPlanStepsFromActivityPayload(payload) {
  const rawSteps = Array.isArray(payload?.plan) ? payload.plan : [];
  return rawSteps
    .map((entry) => {
      if (!entry || typeof entry !== "object") {
        return null;
      }
      const step = normalizeNonEmptyString(entry.step)
        || normalizeNonEmptyString(entry.text)
        || normalizeNonEmptyString(entry.title);
      if (!step) {
        return null;
      }
      const status = normalizeNonEmptyString(entry.status) || null;
      return status ? { step, status } : { step };
    })
    .filter(Boolean);
}

function resolveTaskActivityItemId({
  activity,
  threadActivityState,
  threadId,
}) {
  const payload = activity?.payload && typeof activity.payload === "object" ? activity.payload : {};
  const taskId = normalizeNonEmptyString(payload.taskId);
  if (taskId) {
    const existing = threadActivityState.taskItemIdsByTaskId.get(taskId);
    if (existing) {
      return existing;
    }
    const created = `t3-task:${encodeURIComponent(threadId)}:${encodeURIComponent(taskId)}`;
    threadActivityState.taskItemIdsByTaskId.set(taskId, created);
    return created;
  }
  return `t3-task:${encodeURIComponent(threadId)}:${encodeURIComponent(normalizeNonEmptyString(activity?.id) || Date.now())}`;
}

function resolveApprovalActivityItemId({
  activity,
  threadActivityState,
  threadId,
}) {
  const payload = activity?.payload && typeof activity.payload === "object" ? activity.payload : {};
  const requestId = normalizeNonEmptyString(payload.requestId);
  if (requestId) {
    const existing = threadActivityState.approvalItemIdsByRequestId.get(requestId);
    if (existing) {
      return existing;
    }
    const created = `t3-approval:${encodeURIComponent(threadId)}:${encodeURIComponent(requestId)}`;
    threadActivityState.approvalItemIdsByRequestId.set(requestId, created);
    return created;
  }
  return `t3-approval:${encodeURIComponent(threadId)}:${encodeURIComponent(normalizeNonEmptyString(activity?.id) || Date.now())}`;
}

function resolveToolActivityItemId({
  activity,
  threadActivityState,
  threadId,
}) {
  const payload = activity?.payload && typeof activity.payload === "object" ? activity.payload : {};
  const explicitToolUseId = firstNonEmptyString([
    payload.toolUseId,
    payload.tool_use_id,
    payload?.data?.toolUseId,
    payload?.data?.tool_use_id,
    payload?.data?.result?.toolUseId,
    payload?.data?.result?.tool_use_id,
    payload?.data?.callId,
    payload?.data?.call_id,
  ]);
  if (explicitToolUseId) {
    return `t3-tool:${encodeURIComponent(threadId)}:${encodeURIComponent(explicitToolUseId)}`;
  }

  const correlationKey = [
    normalizeNonEmptyString(activity?.turnId) || "no-turn",
    normalizeNonEmptyString(payload.itemType) || "activity",
    normalizeNonEmptyString(payload.detail) || normalizeNonEmptyString(activity?.summary) || normalizeNonEmptyString(activity?.id),
  ].join("|");
  const existing = threadActivityState.toolItemIdsByKey.get(correlationKey);
  const normalizedKind = normalizeNonEmptyString(activity?.kind).toLowerCase();
  if (normalizedKind === "tool.started") {
    const created = `t3-tool:${encodeURIComponent(threadId)}:${encodeURIComponent(normalizeNonEmptyString(activity?.id) || correlationKey)}`;
    threadActivityState.toolItemIdsByKey.set(correlationKey, [...(existing || []), created]);
    return created;
  }
  if (Array.isArray(existing) && existing.length > 0) {
    if (normalizedKind === "tool.completed") {
      const [matchedItemId, ...remainingItemIds] = existing;
      if (remainingItemIds.length > 0) {
        threadActivityState.toolItemIdsByKey.set(correlationKey, remainingItemIds);
      } else {
        threadActivityState.toolItemIdsByKey.delete(correlationKey);
      }
      return matchedItemId;
    }
    return existing[0];
  }

  const created = `t3-tool:${encodeURIComponent(threadId)}:${encodeURIComponent(normalizeNonEmptyString(activity?.id) || correlationKey)}`;
  if (normalizedKind !== "tool.completed") {
    threadActivityState.toolItemIdsByKey.set(correlationKey, [created]);
  }
  return created;
}

function resolveUserInputActivityItemId({
  activity,
  threadActivityState,
  threadId,
}) {
  const payload = activity?.payload && typeof activity.payload === "object" ? activity.payload : {};
  const requestId = normalizeNonEmptyString(payload.requestId);
  if (requestId) {
    const existing = threadActivityState.userInputItemIdsByRequestId.get(requestId);
    if (existing) {
      return existing;
    }
    const created = `t3-user-input:${encodeURIComponent(threadId)}:${encodeURIComponent(requestId)}`;
    threadActivityState.userInputItemIdsByRequestId.set(requestId, created);
    return created;
  }
  return `t3-user-input:${encodeURIComponent(threadId)}:${encodeURIComponent(normalizeNonEmptyString(activity?.id) || Date.now())}`;
}

function buildExecutionActivityItem({
  activity,
  itemId,
  fallbackType,
  fallbackStatus,
  detail,
}) {
  const payload = activity?.payload && typeof activity.payload === "object" ? activity.payload : {};
  const type = normalizeNonEmptyString(payload.itemType) || fallbackType || "activity";
  const status = normalizeNonEmptyString(payload.status) || fallbackStatus || "completed";
  const summary = normalizeNonEmptyString(detail)
    || normalizeNonEmptyString(payload.summary)
    || normalizeNonEmptyString(activity?.summary)
    || null;
  const title = normalizeNonEmptyString(payload.title)
    || normalizeNonEmptyString(activity?.summary)
    || null;
  const item = {
    id: itemId,
    type,
    status,
  };

  if (title) {
    item.title = title;
  }
  if (summary) {
    item.summary = summary;
    item.message = summary;
    item.text = summary;
  }

  if (type === "command_execution") {
    const command = firstNonEmptyString([
      payload.command,
      payload.raw_command,
      payload.rawCommand,
      detail,
      payload?.data?.command,
      payload?.data?.raw_command,
      payload?.data?.rawCommand,
    ]);
    if (command) {
      item.command = command;
    }
  }

  if (payload.data !== undefined) {
    item.data = payload.data;
  }

  return item;
}

function firstNonEmptyString(values) {
  for (const value of Array.isArray(values) ? values : []) {
    const normalizedValue = normalizeNonEmptyString(value);
    if (normalizedValue) {
      return normalizedValue;
    }
  }
  return "";
}

function compareActivitiesBySequenceAndTime(left, right) {
  const sequenceDelta = normalizeSequenceNumber(left?.sequence) - normalizeSequenceNumber(right?.sequence);
  if (sequenceDelta !== 0) {
    return sequenceDelta;
  }
  const leftCreatedAt = normalizeNonEmptyString(left?.createdAt);
  const rightCreatedAt = normalizeNonEmptyString(right?.createdAt);
  if (leftCreatedAt && rightCreatedAt && leftCreatedAt !== rightCreatedAt) {
    return leftCreatedAt.localeCompare(rightCreatedAt);
  }
  return normalizeNonEmptyString(left?.id).localeCompare(normalizeNonEmptyString(right?.id));
}

function isStalePendingRequestFailureDetail(value) {
  const normalizedValue = normalizeNonEmptyString(value).toLowerCase();
  if (!normalizedValue) {
    return false;
  }
  return normalizedValue.includes("stale pending approval request")
    || normalizedValue.includes("unknown pending permission request")
    || normalizedValue.includes("approval request is no longer pending")
    || normalizedValue.includes("approval request not found");
}

function normalizeApprovalDecision(value) {
  const normalizedValue = normalizeNonEmptyString(value);
  if (
    normalizedValue === "accept"
    || normalizedValue === "acceptForSession"
    || normalizedValue === "decline"
    || normalizedValue === "cancel"
  ) {
    return normalizedValue;
  }
  return "";
}

function normalizeApprovalRequestKind(value) {
  const normalizedValue = normalizeNonEmptyString(value).toLowerCase();
  if (normalizedValue === "command" || normalizedValue === "command_approval") {
    return "command";
  }
  if (normalizedValue === "file-read" || normalizedValue === "file_read" || normalizedValue === "file_read_approval") {
    return "file-read";
  }
  if (normalizedValue === "file-change" || normalizedValue === "file_change" || normalizedValue === "file_change_approval") {
    return "file-change";
  }
  return "";
}

function buildT3TurnStartCommand({
  threadId,
  thread,
  params,
}) {
  const turnInput = extractT3TurnInput(params);
  const messageText = turnInput.text;
  const attachments = turnInput.attachments;
  if (!messageText && attachments.length === 0) {
    throw new Error("T3 turn start requires text or at least one image attachment.");
  }

  const interactionMode = normalizeT3InteractionMode(params?.collaborationMode?.mode);
  const modelSelection = buildT3ModelSelectionFromTurnStartParams({
    params,
    thread,
  });
  const titleSeed = buildT3TitleSeed({
    messageText,
    attachments,
  });

  return {
    type: "thread.turn.start",
    commandId: randomUUID(),
    threadId,
    message: {
      messageId: randomUUID(),
      role: "user",
      text: messageText,
      attachments,
    },
    ...(modelSelection ? { modelSelection } : {}),
    ...(titleSeed ? { titleSeed } : {}),
    interactionMode,
    createdAt: new Date().toISOString(),
  };
}

function prepareT3ThreadForTurnStart({
  threadId,
  thread,
  params,
  transport,
}) {
  const desiredInteractionMode = normalizeT3InteractionMode(params?.collaborationMode?.mode);
  const currentInteractionMode = normalizeT3InteractionMode(thread?.interactionMode);
  const desiredModelSelection = buildT3ModelSelectionFromTurnStartParams({
    params,
    thread,
  });
  const currentModelSelection = normalizeT3ComparableModelSelection(thread?.modelSelection);

  let chain = Promise.resolve();
  if (
    desiredModelSelection
    && JSON.stringify(normalizeT3ComparableModelSelection(desiredModelSelection))
      !== JSON.stringify(currentModelSelection)
  ) {
    chain = chain.then(() => transport.request("orchestration.dispatchCommand", {
      type: "thread.meta.update",
      commandId: randomUUID(),
      threadId,
      modelSelection: desiredModelSelection,
    }, {
      timeoutMs: T3_ATTACH_TIMEOUT_MS,
    }));
  }

  if (desiredInteractionMode !== currentInteractionMode) {
    chain = chain.then(() => transport.request("orchestration.dispatchCommand", {
      type: "thread.interaction-mode.set",
      commandId: randomUUID(),
      threadId,
      interactionMode: desiredInteractionMode,
      createdAt: new Date().toISOString(),
    }, {
      timeoutMs: T3_ATTACH_TIMEOUT_MS,
    }));
  }

  return chain;
}

function extractT3TurnInput(params) {
  const inputItems = Array.isArray(params?.input) ? params.input : [];
  const textParts = [];
  const attachments = [];

  for (const item of inputItems) {
    if (!item || typeof item !== "object") {
      continue;
    }
    const type = normalizeNonEmptyString(item.type).toLowerCase();
    if (type === "text") {
      const text = normalizeNonEmptyString(item.text);
      if (text) {
        textParts.push(text);
      }
      continue;
    }
    if (type === "image") {
      attachments.push(buildT3UploadImageAttachment(item, attachments.length));
    }
  }

  return {
    text: textParts.join("\n\n"),
    attachments,
  };
}

function buildT3UploadImageAttachment(item, index) {
  const dataUrl = firstNonEmptyString([
    item?.dataUrl,
    item?.url,
    item?.imageUrl,
    item?.image_url,
  ]);
  if (!dataUrl) {
    throw new Error("T3 turn start image attachments require a data URL.");
  }

  const parsed = parseImageDataUrl(dataUrl);
  if (!parsed) {
    throw new Error("T3 turn start image attachments must use a valid base64 image data URL.");
  }

  return {
    type: "image",
    name: buildT3AttachmentName(parsed.mimeType, index),
    mimeType: parsed.mimeType,
    sizeBytes: parsed.sizeBytes,
    dataUrl,
  };
}

function parseImageDataUrl(dataUrl) {
  const match = /^data:(image\/[^;,]+)(?:;[^,]*)?;base64,([a-z0-9+/=\r\n ]+)$/i.exec(
    normalizeNonEmptyString(dataUrl)
  );
  if (!match) {
    return null;
  }

  const mimeType = normalizeNonEmptyString(match[1]).toLowerCase();
  const bytes = Buffer.from(match[2].replace(/\s+/g, ""), "base64");
  if (!mimeType || Number.isNaN(bytes.length) || bytes.length <= 0) {
    return null;
  }

  return {
    mimeType,
    sizeBytes: bytes.length,
  };
}

function buildT3AttachmentName(mimeType, index) {
  const subtype = normalizeNonEmptyString(mimeType.split("/")[1] || "").toLowerCase();
  const extension = subtype === "jpeg"
    ? "jpg"
    : subtype === "svg+xml"
      ? "svg"
      : subtype || "img";
  return `image-${index + 1}.${extension}`;
}

function buildT3ModelSelectionFromTurnStartParams({
  params,
  thread,
}) {
  const requestedModel = normalizeNonEmptyString(params?.model);
  const reasoningEffort = normalizeNonEmptyString(params?.effort);
  const serviceTier = normalizeNonEmptyString(params?.serviceTier).toLowerCase();
  if (!requestedModel && !reasoningEffort && !serviceTier) {
    return null;
  }

  const threadModelSelection = thread?.modelSelection && typeof thread.modelSelection === "object"
    ? thread.modelSelection
    : null;
  const model = requestedModel || normalizeNonEmptyString(threadModelSelection?.model);
  if (!model) {
    return null;
  }

  const options = compactObject({
    ...(reasoningEffort ? { reasoningEffort } : {}),
    ...(serviceTier === "fast" ? { fastMode: true } : {}),
  });

  return compactObject({
    provider: "codex",
    ...(model ? { model } : {}),
    ...(Object.keys(options).length > 0 ? { options } : {}),
  });
}

function normalizeT3ComparableModelSelection(modelSelection) {
  if (!modelSelection || typeof modelSelection !== "object") {
    return null;
  }

  const provider = normalizeNonEmptyString(modelSelection.provider);
  const model = normalizeNonEmptyString(modelSelection.model);
  const options = modelSelection.options && typeof modelSelection.options === "object"
    ? compactObject({
      ...(normalizeNonEmptyString(modelSelection.options.reasoningEffort)
        ? { reasoningEffort: normalizeNonEmptyString(modelSelection.options.reasoningEffort) }
        : {}),
      ...(typeof modelSelection.options.fastMode === "boolean"
        ? { fastMode: modelSelection.options.fastMode }
        : {}),
    })
    : {};
  if (!provider || !model) {
    return null;
  }

  return compactObject({
    provider,
    model,
    ...(Object.keys(options).length > 0 ? { options } : {}),
  });
}

function normalizeT3InteractionMode(value) {
  return normalizeNonEmptyString(value).toLowerCase() === "plan" ? "plan" : "default";
}

function buildT3TitleSeed({
  messageText,
  attachments,
}) {
  if (messageText) {
    return messageText.slice(0, 120);
  }
  if (attachments.length > 0) {
    return attachments[0]?.name || "Image turn";
  }
  return "";
}

function sanitizeBridgeUserInputAnswers(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return null;
  }
  return value;
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

function wait(durationMs) {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, durationMs);
    timer.unref?.();
  });
}

function createStructuredRuntimeLogger({
  logEvent = null,
  runtimeTarget = "",
  getReplayScope = null,
  getRuntimeMetadata = null,
} = {}) {
  return function emitStructuredRuntimeEvent(event, fields = {}) {
    if (typeof logEvent !== "function") {
      return;
    }

    const runtimeMetadata = getRuntimeMetadata?.() || {};
    const replayScope = getReplayScope?.() || {};
    try {
      logEvent(compactObject({
        component: "t3-runtime-adapter",
        event: normalizeNonEmptyString(event) || "runtime_event",
        runtimeTarget: normalizeNonEmptyString(runtimeTarget) || null,
        attachState: normalizeNonEmptyString(runtimeMetadata.runtimeAttachState) || null,
        subscriptionState: normalizeNonEmptyString(runtimeMetadata.runtimeSubscriptionState) || null,
        protocolVersion: normalizeNonEmptyString(runtimeMetadata.runtimeProtocolVersion) || null,
        authMode: normalizeNonEmptyString(runtimeMetadata.runtimeAuthMode) || null,
        endpointHost: normalizeNonEmptyString(runtimeMetadata.runtimeEndpointHost) || null,
        stateRootId: buildRuntimeStateRootId(
          replayScope.runtimeStateRoot || runtimeMetadata.runtimeStateRoot
        ),
        snapshotSequence: normalizeSequenceNumber(runtimeMetadata.runtimeSnapshotSequence),
        replaySequence: normalizeSequenceNumber(runtimeMetadata.runtimeReplaySequence),
        duplicateSuppressionCount: normalizeSequenceNumber(
          runtimeMetadata.runtimeDuplicateSuppressionCount
        ),
        ...compactObject(fields),
      }));
    } catch {
      // Adapter observability must never interfere with the active runtime.
    }
  };
}

function buildRuntimeStateRootId(value) {
  const normalizedValue = normalizeNonEmptyString(value);
  if (!normalizedValue) {
    return null;
  }
  return createHash("sha1").update(normalizedValue).digest("hex").slice(0, 12);
}

function compactObject(value) {
  const entries = Object.entries(value || {}).filter(([, entryValue]) => {
    if (entryValue == null) {
      return false;
    }
    if (typeof entryValue === "string") {
      return entryValue.trim() !== "";
    }
    return true;
  });
  return Object.fromEntries(entries);
}

function classifyRuntimeFailure(message) {
  const normalizedMessage = normalizeNonEmptyString(message).toLowerCase();
  if (!normalizedMessage) {
    return "runtime_error";
  }
  if (normalizedMessage.includes("state root") && normalizedMessage.includes("does not match")) {
    return "state_root_mismatch";
  }
  if (normalizedMessage.includes("protocol version") && normalizedMessage.includes("does not match")) {
    return "protocol_version_mismatch";
  }
  if (normalizedMessage.includes("auth mode") && normalizedMessage.includes("does not match")) {
    return "auth_mode_mismatch";
  }
  if (normalizedMessage.includes("missing required rpc method")) {
    return "missing_required_method";
  }
  if (normalizedMessage.includes("missing required subscription")) {
    return "missing_required_subscription";
  }
  if (normalizedMessage.includes("event gap detected")) {
    return "replay_gap_unhealed";
  }
  if (normalizedMessage.includes("timeout")) {
    return "timeout";
  }
  if (normalizedMessage.includes("closed")) {
    return "connection_closed";
  }
  return "runtime_error";
}

module.exports = {
  createRuntimeAdapter,
};
