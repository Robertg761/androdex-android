// FILE: runtime/adapter.js
// Purpose: Provides the first runtime-target adapter seam so the bridge can switch host runtimes without pushing target-specific lifecycle into workspace orchestration.
// Layer: CLI helper
// Exports: createRuntimeAdapter
// Depends on: ../codex/transport, ./method-policy, ./t3-protocol, ./t3-suitability, ./target-config

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
    const result = applyT3EventsToSnapshot({
      snapshot: snapshotCache,
      events,
    });
    snapshotCache = result.snapshot;
    appliedSequence = Math.max(appliedSequence, normalizeSequenceNumber(result.lastSequence));
    duplicateSuppressionCount += normalizeSequenceNumber(result.duplicateCount);
    persistAppliedReplaySequence(appliedSequence);
    publishReadModelMetadata();
    return result;
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
        .then(() => {
          emitRpcResult(requestId, {
            ok: false,
            resumed: false,
            liveUpdatesAttached: false,
            reason: "The read-only T3 adapter refreshed snapshot state but did not attach live updates for this thread.",
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
