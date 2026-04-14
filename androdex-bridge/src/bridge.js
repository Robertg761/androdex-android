// FILE: bridge.js
// Purpose: Runs Codex locally, bridges relay traffic, and coordinates desktop refreshes for Codex.app.
// Layer: CLI service
// Exports: startBridge
// Depends on: ws, crypto, os, ./pairing/qr, ./codex-desktop-refresher, ./workspace/runtime, ./rollout/watch, ./runtime-compat

const WebSocket = require("ws");
const { randomBytes } = require("crypto");
const os = require("os");
const {
  CodexDesktopRefresher,
  readBridgeConfig,
} = require("./codex-desktop-refresher");
const { createCodexRpcClient } = require("./codex/rpc-client");
const { createThreadRolloutActivityWatcher } = require("./rollout/watch");
const { printQR } = require("./pairing/qr");
const { rememberActiveThread } = require("./session-state");
const { handleGitRequest } = require("./git-handler");
const { composeSanitizedAuthStatusFromSettledResults } = require("./account-status");
const { handleThreadContextRequest } = require("./thread-context-handler");
const { handleWorkspaceRequest } = require("./workspace/handler");
const { createNotificationsHandler } = require("./notifications/handler");
const { resolveRuntimeTargetConfig } = require("./runtime/target-config");
const {
  rewriteBridgeMessageThreadIdsForAndroid,
  rewriteBridgeMessageThreadIdsForRuntime,
} = require("./thread-identity");
const {
  buildRuntimeTargetMethodRejectionMessage,
  isCodexNativeRuntimeTarget,
  isReadOnlyRuntimeTarget,
  isRuntimeTargetMethodAllowed,
  normalizeRuntimeMethod,
} = require("./runtime/method-policy");
const {
  getTrustedPhoneRecoveryIdentities,
  loadOrCreateBridgeDeviceState,
  resolveBridgeRelaySession,
  stableRelayHostIdForMacDeviceId,
} = require("./pairing/device-state");
const { createBridgeSecureTransport } = require("./pairing/secure-transport");
const { createPushNotificationServiceClient } = require("./notifications/service-client");
const { createPushNotificationTracker } = require("./notifications/tracker");
const { createRolloutLiveMirrorController } = require("./rollout/live-mirror");
const { createWorkspaceRuntime } = require("./workspace/runtime");
const {
  extractBridgeMessageContext,
  normalizeLegacyAndroidRpcMessage,
  sanitizeThreadHistoryImagesForRelay,
  shouldStartContextUsageWatcher,
} = require("./runtime-compat");

const RELAY_WATCHDOG_PING_INTERVAL_MS = 10_000;
// Keep the stale threshold above the relay's own heartbeat cadence so quiet but
// healthy sessions are not torn down between server pings.
const RELAY_WATCHDOG_STALE_AFTER_MS = 45_000;
const BRIDGE_STATUS_HEARTBEAT_INTERVAL_MS = 5_000;
const CODEX_INITIALIZE_REQUEST_TIMEOUT_MS = 3_000;
const CODEX_INITIALIZE_RETRY_COUNT = 3;
const CODEX_INITIALIZE_RETRY_DELAY_MS = 250;

function startBridge({
  config: explicitConfig = null,
  printPairingQr = true,
  onPairingPayload = null,
  onBridgeStatus = null,
} = {}) {
  const config = {
    ...(explicitConfig || readBridgeConfig()),
  };
  const configuredRuntimeTarget = resolveRuntimeTargetConfig({
    kind: config.runtimeTarget || config.runtimeProvider || "codex-native",
  });
  const relayBaseUrl = normalizeNonEmptyString(config.relayUrl).replace(/\/+$/, "");
  if (!relayBaseUrl) {
    console.error("[androdex] No relay URL configured.");
    process.exit(1);
  }

  let deviceState;
  try {
    deviceState = loadOrCreateBridgeDeviceState();
  } catch (error) {
    console.error(`[androdex] ${(error && error.message) || "Failed to load the saved bridge pairing state."}`);
    process.exit(1);
  }

  const relaySession = resolveBridgeRelaySession(deviceState);
  deviceState = relaySession.deviceState;
  const sessionId = relaySession.sessionId;
  const stableHostId = stableRelayHostIdForMacDeviceId(deviceState.macDeviceId) || sessionId;
  const relaySessionUrl = `${relayBaseUrl}/${sessionId}`;
  const notificationSecret = randomBytes(24).toString("hex");

  let socket = null;
  let isShuttingDown = false;
  let reconnectAttempt = 0;
  let reconnectTimer = null;
  let relayWatchdogTimer = null;
  let statusHeartbeatTimer = null;
  let lastRelayActivityAt = 0;
  let hasSeenInboundRelayTraffic = false;
  let lastPublishedBridgeStatus = null;
  let lastConnectionStatus = null;
  let codexHandshakeState = isCodexNativeRuntimeTarget(config.runtimeTarget || config.runtimeProvider || "codex-native")
    && config.runtimeEndpoint
    ? "warm"
    : "cold";
  const forwardedInitializeRequestIds = new Set();
  let lastInitializeParams = null;
  let pendingAutoWarmPromise = null;
  let pendingColdStartMessages = [];
  const relaySanitizedResponseMethodsById = new Map();
  const relaySanitizedRequestMethods = new Set([
    "thread/read",
    "thread/resume",
  ]);
  const timedForwardedRequestMethods = new Set([
    "thread/start",
    "thread/list",
    "thread/read",
    "thread/resume",
    "turn/start",
    "turn/steer",
    "review/start",
  ]);
  const debugForwardedRequestMethods = new Set([
    "thread/start",
    "turn/start",
    "turn/steer",
    "review/start",
  ]);
  const forwardedRequestMethodTTLms = 2 * 60_000;
  const forwardedRequestTimingsById = new Map();
  let contextUsageWatcher = null;
  let watchedContextUsageKey = null;
  let lastContextUsageHint = null;
  let supportsNativeTokenUsageUpdates = false;
  let lastBridgeManagedInitializeParams = null;
  let bridgeRecoveryInitializePromise = null;
  const pendingBridgeRecoveryRequestsById = new Map();

  const secureTransport = createBridgeSecureTransport({
    hostId: stableHostId,
    sessionId,
    relayUrl: relayBaseUrl,
    deviceState,
    onTrustedPhoneUpdate(nextDeviceState) {
      deviceState = nextDeviceState;
      sendRelayRegistrationUpdate(nextDeviceState);
    },
    onRecoveryProvisioning(recoveryPayload) {
      if (!recoveryPayload) {
        return;
      }
      console.log("\nRecovery payload (save this somewhere safe for remote reinstall recovery):");
      console.log(`${JSON.stringify(recoveryPayload)}\n`);
    },
  });
  const pushServiceClient = createPushNotificationServiceClient({
    baseUrl: config.pushServiceUrl,
    sessionId,
    notificationSecret,
  });
  const notificationsHandler = createNotificationsHandler({
    pushServiceClient,
  });
  const pushNotificationTracker = createPushNotificationTracker({
    sessionId,
    pushServiceClient,
    previewMaxChars: config.pushPreviewMaxChars,
  });
  const rolloutLiveMirror = !config.codexEndpoint
    ? createRolloutLiveMirrorController({
      sendApplicationResponse,
    })
    : null;
  const codexRpcClient = createCodexRpcClient({
    sendToCodex(message) {
      workspaceRuntime.sendToRuntime(message);
    },
    requestIdPrefix: `androdex-bridge-${sessionId}`,
  });
  const codexInitializeRpcClient = createCodexRpcClient({
    sendToCodex(message) {
      workspaceRuntime.sendToRuntime(message);
    },
    requestTimeoutMs: CODEX_INITIALIZE_REQUEST_TIMEOUT_MS,
    requestIdPrefix: `androdex-bridge-init-${sessionId}`,
  });
  const desktopRefresher = new CodexDesktopRefresher({
    enabled: config.refreshEnabled,
    debounceMs: config.refreshDebounceMs,
    refreshCommand: config.refreshCommand,
    bundleId: config.codexBundleId,
    appPath: config.codexAppPath,
  });
  const workspaceRuntime = createWorkspaceRuntime({
    config,
    onBeforeTransportShutdown() {
      forwardedInitializeRequestIds.clear();
      forwardedRequestTimingsById.clear();
      pendingBridgeRecoveryRequestsById.clear();
      rejectPendingColdStartMessages("The host session restarted before it finished warming up.");
      stopContextUsageWatcher({ clearHint: false });
      rolloutLiveMirror?.stopAll();
      supportsNativeTokenUsageUpdates = false;
      bridgeRecoveryInitializePromise = null;
      codexInitializeRpcClient.rejectAllPending(new Error("The active Codex workspace closed before the bridge initialize completed."));
      codexRpcClient.rejectAllPending(new Error("The active Codex workspace closed before the bridge RPC completed."));
      codexHandshakeState = "cold";
      pendingAutoWarmPromise = null;
    },
    onBeforeTransportStart() {
      forwardedInitializeRequestIds.clear();
      forwardedRequestTimingsById.clear();
      pendingBridgeRecoveryRequestsById.clear();
      bridgeRecoveryInitializePromise = null;
      codexHandshakeState = isCodexNativeRuntimeTarget(resolveConfiguredRuntimeTargetFromConfig(config))
        && config.runtimeEndpoint
        ? "warm"
        : "cold";
      supportsNativeTokenUsageUpdates = false;
      pendingAutoWarmPromise = null;
    },
    onTransportError({ error, currentCwd }) {
      if (config.runtimeEndpoint) {
        console.error(`[androdex] Failed to connect to ${workspaceRuntime.getRuntimeTarget()} endpoint: ${config.runtimeEndpoint}`);
      } else {
        console.error("[androdex] Failed to start the active host runtime for the selected workspace.");
      }
      console.error(error.message);
      publishBridgeStatus({
        state: "error",
        connectionStatus: lastConnectionStatus || "disconnected",
        pid: process.pid,
        currentCwd: currentCwd || null,
        lastError: error.message,
      });
    },
    onTransportLog(entry) {
      if (!entry || typeof entry !== "object") {
        return;
      }
      console.log(`[androdex][runtime] ${JSON.stringify(entry)}`);
    },
    onTransportMetadata() {
      publishBridgeStatus({
        state: workspaceRuntime.hasActiveWorkspace() ? "running" : "starting",
        connectionStatus: lastConnectionStatus || "connecting",
        pid: process.pid,
        currentCwd: workspaceRuntime.getCurrentCwd() || null,
        lastError: lastPublishedBridgeStatus?.lastError || "",
      });
    },
    onTransportMessage(message) {
      if (recoverForwardedRequestAfterColdCodexResponse(message)) {
        return;
      }
      const relayBoundMessage = rewriteBridgeMessageThreadIdsForAndroid(
        message,
        workspaceRuntime.getRuntimeTarget(),
      );
      const forwardedRequestTiming = resolveForwardedRequestTiming(
        relayBoundMessage,
        forwardedRequestTimingsById,
      );
      if (forwardedRequestTiming) {
        console.log(
          `[androdex] rpc ${forwardedRequestTiming.method} thread=${forwardedRequestTiming.threadId || "<none>"} durationMs=${forwardedRequestTiming.durationMs}${forwardedRequestTiming.errorMessage ? ` error=${forwardedRequestTiming.errorMessage}` : ""}`,
        );
      }
      const forwardedInitializeResponse = resolveForwardedInitializeResponse(
        relayBoundMessage,
        forwardedInitializeRequestIds,
        buildBridgeRuntimeMetadata({
          runtimeMetadata: workspaceRuntime.getRuntimeMetadata(),
          runtimeTarget: workspaceRuntime.getRuntimeTarget(),
          fallbackTargetConfig: configuredRuntimeTarget,
        }),
      );
      if (forwardedInitializeResponse) {
        if (forwardedInitializeResponse.handshakeWarm) {
          codexHandshakeState = "warm";
        }
        sendApplicationResponse(forwardedInitializeResponse.message);
        return;
      }
      if (codexInitializeRpcClient.handleCodexMessage(message)) {
        return;
      }
      if (codexRpcClient.handleCodexMessage(message)) {
        return;
      }
      desktopRefresher.handleOutbound(message);
      pushNotificationTracker.handleOutbound(relayBoundMessage);
      rememberThreadFromMessage("codex", message);
      sendApplicationResponse(sanitizeRelayBoundCodexMessage(relayBoundMessage));
    },
    onTransportClose() {
      desktopRefresher.handleTransportReset();
      forwardedInitializeRequestIds.clear();
      forwardedRequestTimingsById.clear();
      pendingBridgeRecoveryRequestsById.clear();
      bridgeRecoveryInitializePromise = null;
      rejectPendingColdStartMessages("The host session restarted before it finished warming up.");
      stopContextUsageWatcher();
      rolloutLiveMirror?.stopAll();
      codexHandshakeState = "cold";
      pendingAutoWarmPromise = null;
    },
  });

  pushServiceClient.logUnavailable();
  startBridgeStatusHeartbeat();
  publishBridgeStatus({
    state: "starting",
    connectionStatus: "starting",
    pid: process.pid,
    currentCwd: workspaceRuntime.getCurrentCwd() || null,
    lastError: "",
  });

  const pairingPayload = secureTransport.createPairingPayload();
  onPairingPayload?.(pairingPayload);
  if (printPairingQr) {
    printQR(pairingPayload);
  }

  connectRelay();
  void workspaceRuntime.restoreActiveWorkspace().then((status) => {
    if (!status.workspaceActive) {
      return;
    }
    publishBridgeStatus({
      state: "running",
      connectionStatus: lastConnectionStatus || "connecting",
      pid: process.pid,
      currentCwd: status.currentCwd || null,
      lastError: "",
    });
  }).catch((error) => {
      console.error(`[androdex] Failed to restore workspace ${workspaceRuntime.getCurrentCwd()}: ${error.message}`);
      publishBridgeStatus({
        state: "error",
        connectionStatus: lastConnectionStatus || "disconnected",
        pid: process.pid,
        currentCwd: workspaceRuntime.getCurrentCwd() || null,
        lastError: error.message,
      });
  });

  process.on("SIGINT", () => shutdown());
  process.on("SIGTERM", () => shutdown());

  return {
    activateWorkspace,
    getStatus,
    shutdown,
  };

  function getStatus() {
    const runtimeMetadata = buildBridgeRuntimeMetadata({
      runtimeMetadata: workspaceRuntime.getRuntimeMetadata(),
      runtimeTarget: workspaceRuntime.getRuntimeTarget(),
      fallbackTargetConfig: configuredRuntimeTarget,
    });
    return {
      sessionId,
      hostId: stableHostId,
      macDeviceId: deviceState.macDeviceId,
      relayUrl: relayBaseUrl,
      relayStatus: lastConnectionStatus || "disconnected",
      currentCwd: workspaceRuntime.getCurrentCwd() || null,
      workspaceActive: workspaceRuntime.hasActiveWorkspace(),
      hasTrustedPhone: Object.keys(deviceState.trustedPhones || {}).length > 0,
      ...runtimeMetadata,
    };
  }

  function getWorkspaceState() {
    return workspaceRuntime.getWorkspaceState();
  }

  function connectRelay() {
    if (isShuttingDown) {
      return;
    }

    logConnectionStatus("connecting");
    hasSeenInboundRelayTraffic = false;
    const nextSocket = new WebSocket(relaySessionUrl, {
      headers: {
        "x-role": "mac",
        "x-notification-secret": notificationSecret,
        ...buildMacRegistrationHeaders(deviceState),
      },
    });
    socket = nextSocket;

    nextSocket.on("open", () => {
      if (socket !== nextSocket) {
        return;
      }

      clearReconnectTimer();
      reconnectAttempt = 0;
      markRelayActivity();
      logConnectionStatus("connected");
      supportsNativeTokenUsageUpdates = false;
      startRelayWatchdog(nextSocket);
      secureTransport.bindLiveSendWireMessage((wireMessage) => {
        if (nextSocket.readyState !== WebSocket.OPEN) {
          return false;
        }
        nextSocket.send(wireMessage);
        return true;
      });
      sendRelayRegistrationUpdate(deviceState);
      resumeContextUsageWatcherIfNeeded();
      publishBridgeStatus({
        state: "running",
        connectionStatus: "connected",
        pid: process.pid,
        currentCwd: workspaceRuntime.getCurrentCwd() || null,
        lastError: "",
      });
    });

    nextSocket.on("message", (data) => {
      if (socket !== nextSocket) {
        return;
      }

      hasSeenInboundRelayTraffic = true;
      markRelayActivity();
      const message = typeof data === "string" ? data : data.toString("utf8");
      if (secureTransport.handleIncomingWireMessage(message, {
        sendControlMessage(controlMessage) {
          if (nextSocket.readyState === WebSocket.OPEN) {
            nextSocket.send(JSON.stringify(controlMessage));
          }
        },
        onApplicationMessage(plaintextMessage) {
          handleApplicationMessage(plaintextMessage);
        },
      })) {
        return;
      }
    });

    nextSocket.on("pong", () => {
      if (socket !== nextSocket) {
        return;
      }

      hasSeenInboundRelayTraffic = true;
      markRelayActivity();
    });
    nextSocket.on("ping", () => {
      if (socket !== nextSocket) {
        return;
      }

      hasSeenInboundRelayTraffic = true;
      markRelayActivity();
    });
    nextSocket.on("close", (code, reasonBuffer) => {
      if (socket !== nextSocket) {
        return;
      }

      clearRelayWatchdog();
      logConnectionStatus("disconnected");
      socket = null;
      const reason = normalizeCloseReason(reasonBuffer);
      console.warn(`[androdex] relay closed code=${code}${reason ? ` reason=${reason}` : ""}`);
      hasSeenInboundRelayTraffic = false;
      forwardedInitializeRequestIds.clear();
      forwardedRequestTimingsById.clear();
      supportsNativeTokenUsageUpdates = false;
      stopContextUsageWatcher({ clearHint: false });
      rolloutLiveMirror?.stopAll();
      desktopRefresher.handleTransportReset();
      publishBridgeStatus({
        state: "running",
        connectionStatus: "disconnected",
        pid: process.pid,
        currentCwd: workspaceRuntime.getCurrentCwd() || null,
        lastError: "",
      });
      scheduleRelayReconnect(code);
    });

    nextSocket.on("error", (error) => {
      if (socket !== nextSocket) {
        return;
      }

      logConnectionStatus("disconnected");
      const detail = normalizeNonEmptyString(error?.message);
      if (detail) {
        console.warn(`[androdex] relay socket error: ${detail}`);
      }
    });
  }

  async function activateWorkspace({ cwd = "" } = {}) {
    const status = await workspaceRuntime.activateWorkspace({ cwd });
    publishBridgeStatus({
      state: "running",
      connectionStatus: lastConnectionStatus || "connecting",
      pid: process.pid,
      currentCwd: status.currentCwd || null,
      lastError: "",
    });
    return getStatus();
  }

  function handleApplicationMessage(rawMessage) {
    const normalizedMessage = normalizeLegacyAndroidRpcMessage(rawMessage);
    if (handleBridgeManagedHandshakeMessage(normalizedMessage)) {
      return;
    }
    if (handleBridgeManagedRuntimeTargetRequest(normalizedMessage, sendApplicationResponse)) {
      return;
    }
    if (handleBridgeManagedRuntimeConfigRequest(normalizedMessage, sendApplicationResponse)) {
      return;
    }
    if (handleBridgeManagedAccountRequest(normalizedMessage, sendApplicationResponse)) {
      return;
    }
    if (handleWorkspaceRequest(normalizedMessage, sendApplicationResponse, {
      activateWorkspace,
      getWorkspaceState,
      platform: "darwin",
    })) {
      return;
    }
    if (notificationsHandler.handleNotificationsRequest(normalizedMessage, sendApplicationResponse)) {
      return;
    }
    if (handleGitRequest(normalizedMessage, sendApplicationResponse)) {
      return;
    }
    if (!workspaceRuntime.hasActiveWorkspace()) {
      respondWorkspaceNotActive(normalizedMessage, workspaceRuntime.getRuntimeMetadata());
      return;
    }

    const activeRuntimeTarget = workspaceRuntime.getRuntimeTarget();
    let runtimeBoundMessage = normalizedMessage;
    try {
      runtimeBoundMessage = rewriteBridgeMessageThreadIdsForRuntime(
        normalizedMessage,
        activeRuntimeTarget,
      );
    } catch (error) {
      respondForwardingFailure(
        normalizedMessage,
        normalizeNonEmptyString(error?.message) || "The selected thread does not belong to the active runtime target.",
      );
      return;
    }
    if (handleThreadContextRequest(runtimeBoundMessage, sendApplicationResponse)) {
      return;
    }
    const parsed = safeParseJSON(normalizedMessage);
    const method = normalizeNonEmptyString(parsed?.method);
    if (!isRuntimeTargetMethodAllowed({
      targetKind: activeRuntimeTarget,
      method,
    })) {
      respondForwardingFailure(
        normalizedMessage,
        buildRuntimeTargetMethodRejectionMessage({
          targetKind: activeRuntimeTarget,
          method,
        }),
      );
      return;
    }

    desktopRefresher.handleInbound(runtimeBoundMessage);
    rolloutLiveMirror?.observeInbound(runtimeBoundMessage);
    rememberForwardedRequestMethod(normalizedMessage);
    rememberForwardedRequestTiming(
      runtimeBoundMessage,
      timedForwardedRequestMethods,
      forwardedRequestTimingsById,
    );
    rememberThreadFromMessage("android", runtimeBoundMessage);
    logForwardedRequestToCodex(runtimeBoundMessage, debugForwardedRequestMethods);
    if (queueMessageUntilCodexWarm(runtimeBoundMessage)) {
      return;
    }
    try {
      const didSend = workspaceRuntime.sendToRuntime(runtimeBoundMessage);
      if (didSend === false) {
        const detail = "The host runtime transport is restarting. Retry the request once the session recovers.";
        console.warn(`[androdex] failed to forward request to Codex: ${detail}`);
        respondForwardingFailure(normalizedMessage, detail);
      }
    } catch (error) {
      const detail = normalizeNonEmptyString(error?.message)
        || "The host runtime could not accept the request.";
      console.warn(`[androdex] failed to forward request to Codex: ${detail}`);
      respondForwardingFailure(normalizedMessage, detail);
    }
  }

  function queueMessageUntilCodexWarm(rawMessage) {
    if (!shouldQueueMessageUntilCodexWarm({
      rawMessage,
      codexHandshakeState,
      lastInitializeParams,
      runtimeTarget: workspaceRuntime.getRuntimeTarget(),
    })) {
      return false;
    }

    pendingColdStartMessages.push(rawMessage);
    ensureCodexWarmAfterTransportReset();
    return true;
  }

  function ensureCodexWarmAfterTransportReset() {
    if (pendingAutoWarmPromise) {
      return pendingAutoWarmPromise;
    }

    codexHandshakeState = "warming";
    pendingAutoWarmPromise = codexRpcClient.sendRequest("initialize", lastInitializeParams)
      .then(() => {
        codexHandshakeState = "warm";
        flushPendingColdStartMessages();
      })
      .catch((error) => {
        if (isAlreadyInitializedError(error?.message)) {
          codexHandshakeState = "warm";
          flushPendingColdStartMessages();
          return;
        }

        codexHandshakeState = "cold";
        console.warn(`[androdex] local Codex warm-up failed: ${normalizeNonEmptyString(error?.message) || "unknown error"}`);
        rejectPendingColdStartMessages(
          normalizeNonEmptyString(error?.message) || "The host is not ready yet."
        );
      })
      .finally(() => {
        pendingAutoWarmPromise = null;
      });
    return pendingAutoWarmPromise;
  }

  function maybeWarmCodexAfterBridgeInitialize() {
    if (!workspaceRuntime.hasActiveWorkspace()) {
      return;
    }
    if (!isCodexNativeRuntimeTarget(workspaceRuntime.getRuntimeTarget())) {
      return;
    }
    if (!lastInitializeParams || typeof lastInitializeParams !== "object") {
      return;
    }
    if (codexHandshakeState === "warm" || codexHandshakeState === "warming") {
      return;
    }

    ensureCodexWarmAfterTransportReset().catch((error) => {
      const detail = normalizeNonEmptyString(error?.message);
      if (detail) {
        console.warn(`[androdex] local Codex warm-up failed: ${detail}`);
      }
    });
  }

  function flushPendingColdStartMessages() {
    if (pendingColdStartMessages.length === 0) {
      return;
    }

    const queuedMessages = pendingColdStartMessages;
    pendingColdStartMessages = [];
    for (const queuedMessage of queuedMessages) {
      workspaceRuntime.sendToRuntime(queuedMessage);
    }
  }

  function rejectPendingColdStartMessages(message) {
    if (pendingColdStartMessages.length === 0) {
      return;
    }

    const queuedMessages = pendingColdStartMessages;
    pendingColdStartMessages = [];
    for (const queuedMessage of queuedMessages) {
      const parsed = safeParseJSON(queuedMessage);
      if (parsed?.id == null) {
        continue;
      }
      sendApplicationResponse(JSON.stringify({
        id: parsed.id,
        error: {
          code: -32000,
          message,
        },
      }));
    }
  }

  function respondForwardingFailure(rawMessage, detail) {
    const parsed = safeParseJSON(rawMessage);
    if (parsed?.id == null) {
      return;
    }

    sendApplicationResponse(JSON.stringify({
      id: parsed.id,
      error: {
        code: -32000,
        message: detail,
      },
    }));
  }

  function handleBridgeManagedAccountRequest(rawMessage, sendResponse) {
    const parsed = safeParseJSON(rawMessage);
    const method = typeof parsed?.method === "string" ? parsed.method.trim() : "";
    if (method !== "account/status/read" && method !== "getAuthStatus") {
      return false;
    }

    const requestId = parsed.id;
    if (shouldServeBridgeManagedReadOnlySnapshot({
      runtimeTarget: workspaceRuntime.getRuntimeTarget(),
      workspaceActive: workspaceRuntime.hasActiveWorkspace(),
    })) {
      sendResponse(JSON.stringify({
        id: requestId,
        result: buildUnavailableHostAccountStatus(),
      }));
      return true;
    }

    readSanitizedAuthStatus()
      .then((result) => {
        sendResponse(JSON.stringify({ id: requestId, result }));
      })
      .catch((error) => {
        sendResponse(JSON.stringify({
          id: requestId,
          error: {
            code: -32000,
            message: error.userMessage || error.message || "Unable to read host account status.",
            data: {
              errorCode: error.errorCode || error.code || "account_status_unavailable",
            },
          },
        }));
      });

    return true;
  }

  function handleBridgeManagedRuntimeConfigRequest(rawMessage, sendResponse) {
    if (!shouldServeBridgeManagedReadOnlySnapshot({
      runtimeTarget: workspaceRuntime.getRuntimeTarget(),
      workspaceActive: workspaceRuntime.hasActiveWorkspace(),
    })) {
      return false;
    }

    const parsed = safeParseJSON(rawMessage);
    const method = normalizeNonEmptyString(parsed?.method);
    if (parsed?.id == null) {
      return false;
    }

    if (isBridgeManagedReadOnlyRuntimeConfigMethod(method)) {
      sendResponse(JSON.stringify({
        id: parsed.id,
        result: {
          items: [],
        },
      }));
      return true;
    }

    return false;
  }

  function handleBridgeManagedRuntimeTargetRequest(rawMessage, sendResponse) {
    const parsed = safeParseJSON(rawMessage);
    const method = normalizeRuntimeMethod(parsed?.method);
    if (parsed?.id == null) {
      return false;
    }

    if (method === "bridge/runtimetarget/read" || method === "bridge/runtimeTarget/read") {
      workspaceRuntime.getRuntimeTargetOptions()
        .then((runtimeTargetOptions) => {
          sendResponse(JSON.stringify({
            id: parsed.id,
            result: buildBridgeManagedRuntimeTargetResult(workspaceRuntime, runtimeTargetOptions),
          }));
        })
        .catch((error) => {
          sendResponse(JSON.stringify({
            id: parsed.id,
            error: {
              code: -32000,
              message: normalizeNonEmptyString(error?.message)
                || "Unable to read the host runtime targets.",
            },
          }));
        });
      return true;
    }

    if (method !== "bridge/runtimetarget/set" && method !== "bridge/runtimeTarget/set") {
      return false;
    }

    const targetKind = normalizeNonEmptyString(parsed?.params?.targetKind);
    const resolvedTargetConfig = runCatchingResolveRuntimeTargetConfig(targetKind);
    if (!resolvedTargetConfig) {
      sendResponse(JSON.stringify({
        id: parsed.id,
        error: {
          code: -32602,
          message: `Unsupported runtime target: ${targetKind || "unknown"}`,
        },
      }));
      return true;
    }

    workspaceRuntime.getRuntimeTargetOptions()
      .then((runtimeTargetOptions) => {
        const selectedOption = runtimeTargetOptions.find((option) => option.value === resolvedTargetConfig.kind) || null;
        if (selectedOption?.selected) {
          sendResponse(JSON.stringify({
            id: parsed.id,
            result: buildBridgeManagedRuntimeTargetResult(workspaceRuntime, runtimeTargetOptions),
          }));
          return;
        }
        if (selectedOption && !selectedOption.enabled) {
          sendResponse(JSON.stringify({
            id: parsed.id,
            error: {
              code: -32000,
              message: normalizeNonEmptyString(selectedOption.availabilityMessage)
                || `${selectedOption.title} is not ready yet.`,
            },
          }));
          return;
        }

        workspaceRuntime.updateRuntimeConfig({
          ...buildBridgeManagedRuntimeTargetUpdate({
            targetKind: resolvedTargetConfig.kind,
            codexEndpoint: config.codexEndpoint,
            currentConfig: config,
          }),
        })
          .then(() => workspaceRuntime.getRuntimeTargetOptions())
          .then((updatedRuntimeTargetOptions) => {
            sendResponse(JSON.stringify({
              id: parsed.id,
              result: buildBridgeManagedRuntimeTargetResult(workspaceRuntime, updatedRuntimeTargetOptions),
            }));
          })
          .catch((error) => {
            sendResponse(JSON.stringify({
              id: parsed.id,
              error: {
                code: -32000,
                message: normalizeNonEmptyString(error?.message)
                  || "Unable to switch the host runtime target.",
              },
            }));
          });
      })
      .catch((error) => {
        sendResponse(JSON.stringify({
          id: parsed.id,
          error: {
            code: -32000,
            message: normalizeNonEmptyString(error?.message)
              || "Unable to inspect the host runtime targets.",
          },
        }));
      });
    return true;
  }

  async function readSanitizedAuthStatus() {
    const [accountReadResult, authStatusResult] = await Promise.allSettled([
      codexRpcClient.sendRequest("account/read", {
        refreshToken: false,
      }),
      codexRpcClient.sendRequest("getAuthStatus", {
        includeToken: true,
        refreshToken: true,
      }),
    ]);

    return composeSanitizedAuthStatusFromSettledResults({
      accountReadResult,
      authStatusResult,
    });
  }

  function sendApplicationResponse(rawMessage, options = {}) {
    const relayBoundMessage = rewriteBridgeMessageThreadIdsForAndroid(
      rawMessage,
      workspaceRuntime.getRuntimeTarget(),
    );
    const allowPreResume = options.allowPreResume === true
      || shouldAllowPreResumeRelayResponse(relayBoundMessage);
    secureTransport.queueOutboundApplicationMessage(relayBoundMessage, (wireMessage) => {
      if (socket?.readyState === WebSocket.OPEN) {
        socket.send(wireMessage);
        return true;
      }
      return false;
    }, {
      ...options,
      allowPreResume,
    });
  }

  function sendRelayRegistrationUpdate(nextDeviceState = deviceState) {
    deviceState = nextDeviceState;
    if (socket?.readyState !== WebSocket.OPEN) {
      return;
    }

    socket.send(JSON.stringify({
      kind: "relayMacRegistration",
      registration: buildMacRegistration(nextDeviceState),
    }));
  }

  function rememberThreadFromMessage(source, rawMessage) {
    const context = extractBridgeMessageContext(rawMessage);
    if (!context.threadId) {
      return;
    }

    rememberActiveThread(context.threadId, source);
    if (context.method === "thread/tokenUsage/updated") {
      supportsNativeTokenUsageUpdates = true;
      stopContextUsageWatcher({ clearHint: false });
      return;
    }

    if (!supportsNativeTokenUsageUpdates && shouldStartContextUsageWatcher(context)) {
      ensureContextUsageWatcher(context);
    }
  }

  function rememberForwardedRequestMethod(rawMessage) {
    const context = extractBridgeMessageContext(rawMessage);
    const parsed = safeParseJSON(rawMessage);
    const requestId = parsed?.id;
    if (!context.method || requestId == null) {
      return;
    }

    pruneExpiredForwardedRequestMethods();
    if (relaySanitizedRequestMethods.has(context.method)) {
      relaySanitizedResponseMethodsById.set(String(requestId), {
        method: context.method,
        createdAt: Date.now(),
      });
    }
  }

  function sanitizeRelayBoundCodexMessage(rawMessage) {
    pruneExpiredForwardedRequestMethods();
    const parsed = safeParseJSON(rawMessage);
    const responseId = parsed?.id;
    if (responseId == null) {
      return rawMessage;
    }

    const trackedRequest = relaySanitizedResponseMethodsById.get(String(responseId));
    if (!trackedRequest) {
      return rawMessage;
    }
    relaySanitizedResponseMethodsById.delete(String(responseId));

    return sanitizeThreadHistoryImagesForRelay(rawMessage, trackedRequest.method);
  }

  function pruneExpiredForwardedRequestMethods(now = Date.now()) {
    for (const [requestId, trackedRequest] of relaySanitizedResponseMethodsById.entries()) {
      if (!trackedRequest || (now - trackedRequest.createdAt) >= forwardedRequestMethodTTLms) {
        relaySanitizedResponseMethodsById.delete(requestId);
      }
    }
  }

  function ensureContextUsageWatcher({ threadId, turnId }) {
    const normalizedThreadId = readString(threadId);
    const normalizedTurnId = readString(turnId);
    if (!normalizedThreadId) {
      return;
    }

    lastContextUsageHint = {
      threadId: normalizedThreadId,
      turnId: normalizedTurnId,
    };
    const nextWatcherKey = `${normalizedThreadId}|${normalizedTurnId || "pending-turn"}`;
    if (watchedContextUsageKey === nextWatcherKey && contextUsageWatcher) {
      return;
    }

    stopContextUsageWatcher({ clearHint: false });
    watchedContextUsageKey = nextWatcherKey;
    contextUsageWatcher = createThreadRolloutActivityWatcher({
      threadId: normalizedThreadId,
      turnId: normalizedTurnId,
      onUsage: ({ threadId: usageThreadId, usage }) => {
        sendContextUsageNotification(usageThreadId, usage);
      },
      onIdle: () => {
        if (watchedContextUsageKey === nextWatcherKey) {
          stopContextUsageWatcher();
        }
      },
      onTimeout: () => {
        if (watchedContextUsageKey === nextWatcherKey) {
          stopContextUsageWatcher();
        }
      },
      onError: () => {
        if (watchedContextUsageKey === nextWatcherKey) {
          stopContextUsageWatcher();
        }
      },
    });
  }

  function resumeContextUsageWatcherIfNeeded() {
    if (supportsNativeTokenUsageUpdates || contextUsageWatcher || !lastContextUsageHint?.threadId) {
      return;
    }

    ensureContextUsageWatcher(lastContextUsageHint);
  }

  function stopContextUsageWatcher({ clearHint = true } = {}) {
    if (contextUsageWatcher) {
      contextUsageWatcher.stop();
    }

    contextUsageWatcher = null;
    watchedContextUsageKey = null;
    if (clearHint) {
      lastContextUsageHint = null;
    }
  }

  function sendContextUsageNotification(threadId, usage) {
    if (!threadId || !usage || supportsNativeTokenUsageUpdates) {
      return;
    }

    sendApplicationResponse(JSON.stringify({
      method: "thread/tokenUsage/updated",
      params: {
        threadId,
        usage,
      },
    }));
  }

  function handleBridgeManagedHandshakeMessage(rawMessage) {
    const parsed = safeParseJSON(rawMessage);
    if (!parsed) {
      return false;
    }

    const method = typeof parsed?.method === "string" ? parsed.method.trim() : "";
    if (!method) {
      return false;
    }

    if (method === "initialize" && parsed.id != null) {
      lastInitializeParams = parsed?.params && typeof parsed.params === "object" && !Array.isArray(parsed.params)
        ? parsed.params
        : {};
      lastBridgeManagedInitializeParams = cloneJsonObject(parsed.params) || {};
      if (!workspaceRuntime.hasActiveWorkspace()) {
        sendApplicationResponse(JSON.stringify({
          id: parsed.id,
          result: {
            bridgeManaged: true,
            workspaceActive: false,
          },
        }), { allowPreResume: true });
        return true;
      }

      maybeWarmCodexAfterBridgeInitialize();
      sendApplicationResponse(
        createBridgeManagedInitializeSuccessResponse(
          parsed.id,
          buildBridgeRuntimeMetadata({
            runtimeMetadata: workspaceRuntime.getRuntimeMetadata(),
            runtimeTarget: workspaceRuntime.getRuntimeTarget(),
            fallbackTargetConfig: configuredRuntimeTarget,
          }),
        ),
        { allowPreResume: true },
      );
      return true;
    }

    if (method === "initialized") {
      return true;
    }

    return false;
  }

  function respondWorkspaceNotActive(rawMessage, runtimeMetadata = null) {
    const parsed = safeParseJSON(rawMessage);
    if (!parsed || parsed.id == null) {
      return;
    }

    const attachFailure = normalizeNonEmptyString(runtimeMetadata?.runtimeAttachFailure);
    sendApplicationResponse(JSON.stringify({
      id: parsed.id,
      error: {
        code: -32000,
        message: attachFailure
          || "No active workspace on the host. Choose a project from the Android app to get started.",
      },
    }));
  }

  function markRelayActivity() {
    lastRelayActivityAt = Date.now();
  }

  function clearRelayWatchdog() {
    if (!relayWatchdogTimer) {
      return;
    }

    clearInterval(relayWatchdogTimer);
    relayWatchdogTimer = null;
  }

  function startRelayWatchdog(trackedSocket) {
    clearRelayWatchdog();
    markRelayActivity();

    relayWatchdogTimer = setInterval(() => {
      runRelayWatchdogTick({
        isShuttingDown,
        activeSocket: socket,
        trackedSocket,
        hasSeenInboundRelayTraffic,
        lastRelayActivityAt,
        clearRelayWatchdog,
      });
    }, RELAY_WATCHDOG_PING_INTERVAL_MS);
    relayWatchdogTimer.unref?.();
  }

  function logConnectionStatus(status) {
    if (lastConnectionStatus === status) {
      return;
    }

    lastConnectionStatus = status;
    console.log(`[androdex] ${status}`);
  }

  function scheduleRelayReconnect(closeCode) {
    if (isShuttingDown) {
      return;
    }

    if (closeCode === 4000 || closeCode === 4001) {
      return;
    }

    if (reconnectTimer) {
      return;
    }

    reconnectAttempt += 1;
    const delayMs = Math.min(1_000 * reconnectAttempt, 5_000);
    logConnectionStatus("connecting");
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      connectRelay();
    }, delayMs);
  }

  function clearReconnectTimer() {
    if (!reconnectTimer) {
      return;
    }

    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }

  function startBridgeStatusHeartbeat() {
    if (statusHeartbeatTimer) {
      return;
    }

    statusHeartbeatTimer = setInterval(() => {
      if (!lastPublishedBridgeStatus || isShuttingDown) {
        return;
      }

      onBridgeStatus?.({
        ...lastPublishedBridgeStatus,
      });
    }, BRIDGE_STATUS_HEARTBEAT_INTERVAL_MS);
    statusHeartbeatTimer.unref?.();
  }

  function clearBridgeStatusHeartbeat() {
    if (!statusHeartbeatTimer) {
      return;
    }

    clearInterval(statusHeartbeatTimer);
    statusHeartbeatTimer = null;
  }

  function publishBridgeStatus(status) {
    const runtimeMetadata = buildBridgeRuntimeMetadata({
      runtimeMetadata: workspaceRuntime.getRuntimeMetadata(),
      runtimeTarget: workspaceRuntime.getRuntimeTarget(),
      fallbackTargetConfig: configuredRuntimeTarget,
    });
    lastPublishedBridgeStatus = {
      ...runtimeMetadata,
      ...status,
    };
    onBridgeStatus?.({
      ...runtimeMetadata,
      ...status,
    });
  }

  async function sendCodexInitializeRequest(params) {
    let lastError = null;
    for (let attempt = 1; attempt <= CODEX_INITIALIZE_RETRY_COUNT; attempt += 1) {
      try {
        return await codexInitializeRpcClient.sendRequest("initialize", params);
      } catch (error) {
        if (isAlreadyInitializedError(error?.message)) {
          return null;
        }
        lastError = error;
        if (!isCodexInitializeRetryableError(error) || attempt >= CODEX_INITIALIZE_RETRY_COUNT) {
          throw error;
        }
        await delay(CODEX_INITIALIZE_RETRY_DELAY_MS);
      }
    }

    throw lastError || new Error("Codex initialize failed.");
  }

  async function shutdown() {
    if (isShuttingDown) {
      return;
    }
    isShuttingDown = true;
    clearReconnectTimer();
    clearRelayWatchdog();
    clearBridgeStatusHeartbeat();
    forwardedInitializeRequestIds.clear();
    forwardedRequestTimingsById.clear();
    pendingBridgeRecoveryRequestsById.clear();
    bridgeRecoveryInitializePromise = null;
    stopContextUsageWatcher({ clearHint: false });

    if (socket?.readyState === WebSocket.OPEN || socket?.readyState === WebSocket.CONNECTING) {
      socket.close();
    }
    socket = null;
    await workspaceRuntime.shutdown();
    setTimeout(() => process.exit(0), 100);
  }

  function recoverForwardedRequestAfterColdCodexResponse(rawMessage) {
    if (!isCodexNativeRuntimeTarget(workspaceRuntime.getRuntimeTarget())) {
      return false;
    }

    const replayableRequest = resolveForwardedRequestReplay(
      rawMessage,
      forwardedRequestTimingsById,
    );
    if (!replayableRequest) {
      return false;
    }

    if (!workspaceRuntime.hasActiveWorkspace() || !lastBridgeManagedInitializeParams) {
      return false;
    }

    pendingBridgeRecoveryRequestsById.set(replayableRequest.requestId, replayableRequest);
    if (!bridgeRecoveryInitializePromise) {
      bridgeRecoveryInitializePromise = sendCodexInitializeRequest(lastBridgeManagedInitializeParams)
        .catch((error) => {
          if (isAlreadyInitializedError(error?.message)) {
            return null;
          }
          throw error;
      }).then(() => {
          codexHandshakeState = "warm";
          const queuedRequests = [...pendingBridgeRecoveryRequestsById.values()];
          pendingBridgeRecoveryRequestsById.clear();
          for (const request of queuedRequests) {
            workspaceRuntime.sendToRuntime(request.rawMessage);
          }
      }).catch((error) => {
          const detail = normalizeNonEmptyString(error?.message)
            || "The host runtime could not recover its initialize state.";
          const queuedRequests = [...pendingBridgeRecoveryRequestsById.values()];
          pendingBridgeRecoveryRequestsById.clear();
          for (const request of queuedRequests) {
            forwardedRequestTimingsById.delete(request.requestId);
            sendApplicationResponse(JSON.stringify({
              id: request.requestId,
              error: {
                code: -32000,
                message: detail,
              },
            }));
          }
      }).finally(() => {
          bridgeRecoveryInitializePromise = null;
      });
    }

    return true;
  }
}

function getRelayWatchdogAction({
  isShuttingDown,
  activeSocket,
  trackedSocket,
  hasSeenInboundRelayTraffic,
  lastRelayActivityAt,
  now = Date.now(),
}) {
  if (isShuttingDown || activeSocket !== trackedSocket) {
    return "clear";
  }

  if (trackedSocket?.readyState !== WebSocket.OPEN) {
    return "wait";
  }

  if (!hasSeenInboundRelayTraffic) {
    return "wait";
  }

  return "ping";
}

function runRelayWatchdogTick({
  isShuttingDown,
  activeSocket,
  trackedSocket,
  hasSeenInboundRelayTraffic,
  lastRelayActivityAt,
  now = Date.now(),
  clearRelayWatchdog = null,
  logError = console.error,
}) {
  try {
    const watchdogAction = getRelayWatchdogAction({
      isShuttingDown,
      activeSocket,
      trackedSocket,
      hasSeenInboundRelayTraffic,
      lastRelayActivityAt,
      now,
    });

    if (watchdogAction === "clear") {
      clearRelayWatchdog?.();
      return "clear";
    }

    if (watchdogAction === "wait") {
      return "wait";
    }

    try {
      trackedSocket?.ping?.();
    } catch {
      // Best effort only.
    }

    return "ping";
  } catch (error) {
    const details = error?.stack || error?.message || String(error);
    logError(`[androdex] relay watchdog failed: ${details}`);
    return "error";
  }
}

function hasRelayConnectionGoneStale(activityAt, now = Date.now()) {
  return activityAt > 0 && (now - activityAt) >= RELAY_WATCHDOG_STALE_AFTER_MS;
}

function normalizeCloseReason(value) {
  if (typeof value === "string") {
    return value.trim();
  }

  if (Buffer.isBuffer(value)) {
    return value.toString("utf8").trim();
  }

  return "";
}

function safeParseJSON(value) {
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

function readString(value) {
  return typeof value === "string" && value ? value : null;
}

function normalizeNonEmptyString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

function rememberForwardedRequestTiming(
  rawMessage,
  trackedMethods,
  forwardedRequestTimingsById,
  now = Date.now(),
) {
  if (!trackedMethods || !forwardedRequestTimingsById) {
    return;
  }

  const context = extractBridgeMessageContext(rawMessage);
  const parsed = safeParseJSON(rawMessage);
  const requestId = parsed?.id;
  if (!context.method || requestId == null || !trackedMethods.has(context.method)) {
    return;
  }

  forwardedRequestTimingsById.set(String(requestId), {
    method: context.method,
    threadId: context.threadId || "",
    startedAt: now,
    rawMessage,
  });
}

function resolveForwardedRequestTiming(
  rawMessage,
  forwardedRequestTimingsById,
  now = Date.now(),
) {
  if (!forwardedRequestTimingsById) {
    return null;
  }

  const parsed = safeParseJSON(rawMessage);
  const responseId = parsed?.id;
  if (responseId == null) {
    return null;
  }

  const timing = forwardedRequestTimingsById.get(String(responseId));
  if (!timing) {
    return null;
  }

  forwardedRequestTimingsById.delete(String(responseId));
  return {
    method: timing.method,
    threadId: timing.threadId,
    durationMs: Math.max(0, now - timing.startedAt),
    errorMessage: normalizeNonEmptyString(parsed?.error?.message),
  };
}

function resolveForwardedRequestReplay(
  rawMessage,
  forwardedRequestTimingsById,
) {
  if (!forwardedRequestTimingsById) {
    return null;
  }

  const parsed = safeParseJSON(rawMessage);
  const responseId = parsed?.id;
  if (responseId == null || !isNotInitializedError(parsed?.error?.message)) {
    return null;
  }

  const replayableRequest = forwardedRequestTimingsById.get(String(responseId));
  if (!replayableRequest?.rawMessage) {
    return null;
  }

  return {
    requestId: String(responseId),
    method: replayableRequest.method,
    threadId: replayableRequest.threadId,
    rawMessage: replayableRequest.rawMessage,
  };
}

function logForwardedRequestToCodex(rawMessage, debugMethods) {
  if (!debugMethods || debugMethods.size === 0) {
    return;
  }

  const context = extractBridgeMessageContext(rawMessage);
  if (!debugMethods.has(context.method)) {
    return;
  }

  const parsed = safeParseJSON(rawMessage);
  const requestId = parsed?.id;
  if (requestId == null) {
    return;
  }

  const params = context.params && typeof context.params === "object" ? context.params : {};
  const inputItems = Array.isArray(params.input) ? params.input.length : 0;
  const collaborationMode = normalizeNonEmptyString(params?.collaborationMode?.mode);
  const model = normalizeNonEmptyString(params?.model);
  console.log(
    `[androdex] rpc->codex ${context.method} id=${requestId} thread=${context.threadId || "<none>"} `
      + `inputItems=${inputItems}${model ? ` model=${model}` : ""}`
      + `${collaborationMode ? ` collaborationMode=${collaborationMode}` : ""}`,
  );
}

function resolveForwardedInitializeResponse(rawMessage, forwardedInitializeRequestIds, runtimeMetadata = null) {
  if (!forwardedInitializeRequestIds || typeof forwardedInitializeRequestIds.has !== "function") {
    return null;
  }

  const parsed = safeParseJSON(rawMessage);
  const responseId = parsed?.id;
  if (responseId == null) {
    return null;
  }

  const responseKey = String(responseId);
  if (!forwardedInitializeRequestIds.has(responseKey)) {
    return null;
  }

  forwardedInitializeRequestIds.delete(responseKey);
  if (parsed?.result != null || isAlreadyInitializedError(parsed?.error?.message)) {
    return {
      handshakeWarm: true,
      message: createBridgeManagedInitializeSuccessResponse(responseId, runtimeMetadata),
    };
  }

  return {
    handshakeWarm: false,
    message: rawMessage,
  };
}

function createBridgeManagedInitializeSuccessResponse(requestId, runtimeMetadata = null) {
  return JSON.stringify({
    id: requestId,
    result: {
      bridgeManaged: true,
      workspaceActive: true,
      ...(runtimeMetadata || {}),
    },
  });
}

function buildBridgeRuntimeMetadata({
  runtimeMetadata = null,
  runtimeTarget = "",
  fallbackTargetConfig = null,
  runtimeTargetOptions = null,
} = {}) {
  const resolvedTargetConfig = runCatchingResolveRuntimeTargetConfig(runtimeTarget) || fallbackTargetConfig;
  return {
    ...(runtimeMetadata && typeof runtimeMetadata === "object" ? runtimeMetadata : {}),
    runtimeTarget: resolvedTargetConfig?.kind || normalizeNonEmptyString(runtimeTarget) || null,
    runtimeTargetDisplayName: resolvedTargetConfig?.displayName || null,
    backendProvider: resolvedTargetConfig?.backendProviderKind || null,
    backendProviderDisplayName: resolvedTargetConfig?.backendProviderKind
      ? titleCaseProviderName(resolvedTargetConfig.backendProviderKind)
      : null,
    runtimeTargetOptions: Array.isArray(runtimeTargetOptions) ? runtimeTargetOptions : [],
  };
}

function buildBridgeManagedRuntimeTargetResult(workspaceRuntime, runtimeTargetOptions = null) {
  const workspaceStatus = workspaceRuntime?.getStatus?.() || {};
  return {
    workspaceActive: Boolean(workspaceStatus.workspaceActive),
    currentCwd: normalizeNonEmptyString(workspaceStatus.currentCwd) || null,
    ...buildBridgeRuntimeMetadata({
      runtimeMetadata: workspaceStatus.runtimeMetadata,
      runtimeTarget: workspaceStatus.runtimeTarget,
      runtimeTargetOptions,
    }),
  };
}

function shouldAllowPreResumeRelayResponse(rawMessage) {
  const parsed = safeParseJSON(rawMessage);
  if (!parsed || parsed.id == null) {
    return false;
  }

  return normalizeNonEmptyString(parsed.method) === "";
}

function isAlreadyInitializedError(message) {
  return normalizeNonEmptyString(message).toLowerCase().includes("already initialized");
}

function runCatchingResolveRuntimeTargetConfig(kind) {
  try {
    return resolveRuntimeTargetConfig({ kind });
  } catch {
    return null;
  }
}

function titleCaseProviderName(value) {
  const normalized = normalizeNonEmptyString(value);
  if (!normalized) {
    return null;
  }

  return normalized.slice(0, 1).toUpperCase() + normalized.slice(1);
}

function resolveConfiguredRuntimeTargetFromConfig(config) {
  return normalizeNonEmptyString(config?.runtimeTarget)
    || normalizeNonEmptyString(config?.runtimeProvider)
    || "codex-native";
}

function buildUnavailableHostAccountStatus() {
  return {
    status: "unavailable",
    authMethod: null,
    email: null,
    planType: null,
    loginInFlight: false,
    needsReauth: false,
    tokenReady: false,
    expiresAt: null,
    bridgeVersion: null,
    bridgeLatestVersion: null,
  };
}

function shouldServeBridgeManagedReadOnlySnapshot({
  runtimeTarget = "",
  workspaceActive = false,
} = {}) {
  return isReadOnlyRuntimeTarget(runtimeTarget) && workspaceActive;
}

function isBridgeManagedReadOnlyRuntimeConfigMethod(method) {
  const normalizedMethod = normalizeRuntimeMethod(method);
  return normalizedMethod === "model/list" || normalizedMethod === "collaborationmode/list";
}

function isCodexInitializeRetryableError(error) {
  return error?.code === "codex_rpc_timeout";
}

function isNotInitializedError(message) {
  return normalizeNonEmptyString(message).toLowerCase().includes("not initialized");
}

function delay(durationMs) {
  return new Promise((resolve) => setTimeout(resolve, durationMs));
}

function cloneJsonObject(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return null;
  }

  try {
    return JSON.parse(JSON.stringify(value));
  } catch {
    return null;
  }
}

function shouldQueueMessageUntilCodexWarm({
  rawMessage,
  codexHandshakeState,
  lastInitializeParams,
  runtimeTarget = "codex-native",
}) {
  if (!isCodexNativeRuntimeTarget(runtimeTarget)) {
    return false;
  }
  if (codexHandshakeState === "warm") {
    return false;
  }
  if (!lastInitializeParams || typeof lastInitializeParams !== "object") {
    return false;
  }

  const parsed = safeParseJSON(rawMessage);
  if (!parsed || parsed.id == null) {
    return false;
  }

  const method = normalizeNonEmptyString(parsed.method);
  return method !== "initialize" && method !== "initialized";
}

function buildMacRegistrationHeaders(deviceState) {
  const registration = buildMacRegistration(deviceState);
  const headers = {
    "x-mac-device-id": registration.macDeviceId,
    "x-mac-identity-public-key": registration.macIdentityPublicKey,
    "x-machine-name": registration.displayName,
  };
  if (registration.trustedPhoneDeviceId && registration.trustedPhonePublicKey) {
    headers["x-trusted-phone-device-id"] = registration.trustedPhoneDeviceId;
    headers["x-trusted-phone-public-key"] = registration.trustedPhonePublicKey;
    if (registration.trustedPhoneRecoveryPublicKey) {
      headers["x-trusted-phone-recovery-public-key"] = registration.trustedPhoneRecoveryPublicKey;
    }
    if (registration.trustedPhonePreviousRecoveryPublicKey) {
      headers["x-trusted-phone-previous-recovery-public-key"] = registration.trustedPhonePreviousRecoveryPublicKey;
    }
  }
  return headers;
}

function buildMacRegistration(deviceState) {
  const trustedPhoneEntry = Object.entries(deviceState?.trustedPhones || {})[0] || null;
  const trustedPhoneRecoveryIdentities = trustedPhoneEntry
    ? getTrustedPhoneRecoveryIdentities(deviceState, trustedPhoneEntry[0])
    : [];
  return {
    macDeviceId: normalizeNonEmptyString(deviceState?.macDeviceId),
    macIdentityPublicKey: normalizeNonEmptyString(deviceState?.macIdentityPublicKey),
    displayName: normalizeNonEmptyString(os.hostname()),
    trustedPhoneDeviceId: normalizeNonEmptyString(trustedPhoneEntry?.[0]),
    trustedPhonePublicKey: normalizeNonEmptyString(trustedPhoneEntry?.[1]),
    trustedPhoneRecoveryPublicKey: normalizeNonEmptyString(
      trustedPhoneRecoveryIdentities[0]?.recoveryIdentityPublicKey
    ),
    trustedPhonePreviousRecoveryPublicKey: normalizeNonEmptyString(
      trustedPhoneRecoveryIdentities[1]?.recoveryIdentityPublicKey
    ),
  };
}

function buildBridgeManagedRuntimeTargetUpdate({
  targetKind = "",
  codexEndpoint = "",
  currentConfig = null,
} = {}) {
  const resolvedTargetConfig = resolveRuntimeTargetConfig({ kind: targetKind });
  const runtimeTarget = resolvedTargetConfig.kind;
  const currentRuntimeEndpoint = normalizeNonEmptyString(currentConfig?.runtimeEndpoint);
  const currentRuntimeEndpointAuthToken = normalizeNonEmptyString(currentConfig?.runtimeEndpointAuthToken);
  return {
    runtimeTarget,
    runtimeProvider: resolvedTargetConfig.legacyProviderKind,
    runtimeEndpoint: runtimeTarget === "t3-server"
      ? currentRuntimeEndpoint
      : normalizeNonEmptyString(codexEndpoint),
    runtimeEndpointAuthToken: runtimeTarget === "t3-server"
      ? currentRuntimeEndpointAuthToken
      : "",
  };
}

module.exports = {
  buildUnavailableHostAccountStatus,
  buildBridgeManagedRuntimeTargetUpdate,
  createBridgeManagedInitializeSuccessResponse,
  buildBridgeManagedRuntimeTargetResult,
  getRelayWatchdogAction,
  hasRelayConnectionGoneStale,
  logForwardedRequestToCodex,
  rememberForwardedRequestTiming,
  resolveForwardedRequestReplay,
  resolveForwardedRequestTiming,
  resolveForwardedInitializeResponse,
  runRelayWatchdogTick,
  sanitizeThreadHistoryImagesForRelay,
  isBridgeManagedReadOnlyRuntimeConfigMethod,
  shouldAllowPreResumeRelayResponse,
  shouldServeBridgeManagedReadOnlySnapshot,
  shouldQueueMessageUntilCodexWarm,
  startBridge,
};
