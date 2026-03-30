// FILE: bridge.js
// Purpose: Runs Codex locally, bridges relay traffic, and coordinates desktop refreshes for Codex.app.
// Layer: CLI service
// Exports: startBridge
// Depends on: ws, crypto, os, ./qr, ./codex-desktop-refresher, ./workspace-runtime, ./rollout-watch, ./runtime-compat

const WebSocket = require("ws");
const { randomBytes } = require("crypto");
const os = require("os");
const {
  CodexDesktopRefresher,
  readBridgeConfig,
} = require("./codex-desktop-refresher");
const { createCodexRpcClient } = require("./codex-rpc-client");
const { createThreadRolloutActivityWatcher } = require("./rollout-watch");
const { printQR } = require("./qr");
const { rememberActiveThread } = require("./session-state");
const { handleGitRequest } = require("./git-handler");
const { composeSanitizedAuthStatusFromSettledResults } = require("./account-status");
const { handleThreadContextRequest } = require("./thread-context-handler");
const { handleWorkspaceRequest } = require("./workspace-handler");
const { createNotificationsHandler } = require("./notifications-handler");
const {
  loadOrCreateBridgeDeviceState,
  resolveBridgeRelaySession,
} = require("./secure-device-state");
const { createBridgeSecureTransport } = require("./secure-transport");
const { createPushNotificationServiceClient } = require("./push-notification-service-client");
const { createPushNotificationTracker } = require("./push-notification-tracker");
const { createRolloutLiveMirrorController } = require("./rollout-live-mirror");
const { createWorkspaceRuntime } = require("./workspace-runtime");
const {
  extractBridgeMessageContext,
  normalizeLegacyAndroidRpcMessage,
  sanitizeThreadHistoryImagesForRelay,
  shouldStartContextUsageWatcher,
} = require("./runtime-compat");

const RELAY_WATCHDOG_PING_INTERVAL_MS = 10_000;
const RELAY_WATCHDOG_STALE_AFTER_MS = 25_000;
const BRIDGE_STATUS_HEARTBEAT_INTERVAL_MS = 5_000;
const STALE_RELAY_STATUS_MESSAGE = "Relay heartbeat stalled; reconnect pending.";

function startBridge({
  config: explicitConfig = null,
  printPairingQr = true,
  onPairingPayload = null,
  onBridgeStatus = null,
} = {}) {
  const config = {
    ...(explicitConfig || readBridgeConfig()),
  };
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
  const relaySessionUrl = `${relayBaseUrl}/${sessionId}`;
  const notificationSecret = randomBytes(24).toString("hex");

  let socket = null;
  let isShuttingDown = false;
  let reconnectAttempt = 0;
  let reconnectTimer = null;
  let relayWatchdogTimer = null;
  let statusHeartbeatTimer = null;
  let lastRelayActivityAt = 0;
  let lastPublishedBridgeStatus = null;
  let lastConnectionStatus = null;
  let codexHandshakeState = config.codexEndpoint ? "warm" : "cold";
  const forwardedInitializeRequestIds = new Set();
  const relaySanitizedResponseMethodsById = new Map();
  const relaySanitizedRequestMethods = new Set([
    "thread/read",
    "thread/resume",
  ]);
  const forwardedRequestMethodTTLms = 2 * 60_000;
  let cachedInitializeParams = null;
  let cachedLegacyInitializeParams = null;
  let cachedInitializedNotification = false;
  let syntheticInitializeRequest = null;
  let syntheticInitializeCounter = 0;
  let contextUsageWatcher = null;
  let watchedContextUsageKey = null;
  let lastContextUsageHint = null;
  let supportsNativeTokenUsageUpdates = false;

  const secureTransport = createBridgeSecureTransport({
    hostId: sessionId,
    sessionId,
    relayUrl: relayBaseUrl,
    deviceState,
    onTrustedPhoneUpdate(nextDeviceState) {
      deviceState = nextDeviceState;
      sendRelayRegistrationUpdate(nextDeviceState);
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
      workspaceRuntime.sendToCodex(message);
    },
    requestIdPrefix: `androdex-bridge-${sessionId}`,
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
      syntheticInitializeRequest = null;
      stopContextUsageWatcher({ clearHint: false });
      rolloutLiveMirror?.stopAll();
      supportsNativeTokenUsageUpdates = false;
      codexRpcClient.rejectAllPending(new Error("The active Codex workspace closed before the bridge RPC completed."));
      codexHandshakeState = "cold";
    },
    onBeforeTransportStart() {
      forwardedInitializeRequestIds.clear();
      syntheticInitializeRequest = null;
      codexHandshakeState = config.codexEndpoint ? "warm" : "cold";
      supportsNativeTokenUsageUpdates = false;
    },
    onTransportError({ error, currentCwd }) {
      if (config.codexEndpoint) {
        console.error(`[androdex] Failed to connect to Codex endpoint: ${config.codexEndpoint}`);
      } else {
        console.error("[androdex] Failed to start `codex app-server` for the active workspace.");
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
    onTransportMessage(message) {
      if (handleSyntheticInitializeMessage(message)) {
        return;
      }
      if (codexRpcClient.handleCodexMessage(message)) {
        return;
      }
      trackCodexHandshakeState(message);
      desktopRefresher.handleOutbound(message);
      pushNotificationTracker.handleOutbound(message);
      rememberThreadFromMessage("codex", message);
      secureTransport.queueOutboundApplicationMessage(sanitizeRelayBoundCodexMessage(message), (wireMessage) => {
        if (socket?.readyState === WebSocket.OPEN) {
          socket.send(wireMessage);
        }
      });
    },
    onTransportClose() {
      desktopRefresher.handleTransportReset();
      stopContextUsageWatcher();
      rolloutLiveMirror?.stopAll();
      codexHandshakeState = "cold";
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
    primeCodexHandshake();
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
    return {
      sessionId,
      hostId: sessionId,
      macDeviceId: deviceState.macDeviceId,
      relayUrl: relayBaseUrl,
      relayStatus: lastConnectionStatus || "disconnected",
      currentCwd: workspaceRuntime.getCurrentCwd() || null,
      workspaceActive: workspaceRuntime.hasActiveWorkspace(),
      hasTrustedPhone: Object.keys(deviceState.trustedPhones || {}).length > 0,
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
    const nextSocket = new WebSocket(relaySessionUrl, {
      headers: {
        "x-role": "mac",
        "x-notification-secret": notificationSecret,
        ...buildMacRegistrationHeaders(deviceState),
      },
    });
    socket = nextSocket;

    nextSocket.on("open", () => {
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

    nextSocket.on("pong", markRelayActivity);
    nextSocket.on("close", (code) => {
      clearRelayWatchdog();
      logConnectionStatus("disconnected");
      if (socket === nextSocket) {
        socket = null;
      }
      clearCachedBridgeHandshakeState();
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

    nextSocket.on("error", () => {
      logConnectionStatus("disconnected");
    });
  }

  async function activateWorkspace({ cwd = "" } = {}) {
    const status = await workspaceRuntime.activateWorkspace({ cwd });
    primeCodexHandshake();
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
    if (handleThreadContextRequest(normalizedMessage, sendApplicationResponse)) {
      return;
    }

    if (!workspaceRuntime.hasActiveWorkspace()) {
      respondWorkspaceNotActive(normalizedMessage);
      return;
    }

    desktopRefresher.handleInbound(normalizedMessage);
    rolloutLiveMirror?.observeInbound(normalizedMessage);
    rememberForwardedRequestMethod(normalizedMessage);
    rememberThreadFromMessage("android", normalizedMessage);
    workspaceRuntime.sendToCodex(normalizedMessage);
  }

  function handleBridgeManagedAccountRequest(rawMessage, sendResponse) {
    const parsed = safeParseJSON(rawMessage);
    const method = typeof parsed?.method === "string" ? parsed.method.trim() : "";
    if (method !== "account/status/read" && method !== "getAuthStatus") {
      return false;
    }

    const requestId = parsed.id;
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

  function sendApplicationResponse(rawMessage) {
    secureTransport.queueOutboundApplicationMessage(rawMessage, (wireMessage) => {
      if (socket?.readyState === WebSocket.OPEN) {
        socket.send(wireMessage);
        return true;
      }
      return false;
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
      cacheBridgeInitialize(parsed);
      if (!workspaceRuntime.hasActiveWorkspace()) {
        sendApplicationResponse(JSON.stringify({
          id: parsed.id,
          result: {
            bridgeManaged: true,
            workspaceActive: false,
          },
        }));
        return true;
      }

      if (codexHandshakeState !== "warm") {
        forwardedInitializeRequestIds.add(String(parsed.id));
        return false;
      }

      sendApplicationResponse(JSON.stringify({
        id: parsed.id,
        result: {
          bridgeManaged: true,
          workspaceActive: true,
        },
      }));
      return true;
    }

    if (method === "initialized") {
      cachedInitializedNotification = true;
      return codexHandshakeState === "warm" || !workspaceRuntime.hasActiveWorkspace();
    }

    return false;
  }

  function respondWorkspaceNotActive(rawMessage) {
    const parsed = safeParseJSON(rawMessage);
    if (!parsed || parsed.id == null) {
      return;
    }

    sendApplicationResponse(JSON.stringify({
      id: parsed.id,
      error: {
        code: -32000,
        message: "No active workspace on the host. Choose a project from the Android app to get started.",
      },
    }));
  }

  function trackCodexHandshakeState(rawMessage) {
    const parsed = safeParseJSON(rawMessage);
    const responseId = parsed?.id;
    if (responseId == null) {
      return;
    }

    const responseKey = String(responseId);
    if (!forwardedInitializeRequestIds.has(responseKey)) {
      return;
    }

    forwardedInitializeRequestIds.delete(responseKey);
    if (parsed?.result != null) {
      codexHandshakeState = "warm";
      return;
    }

    const errorMessage = typeof parsed?.error?.message === "string"
      ? parsed.error.message.toLowerCase()
      : "";
    if (errorMessage.includes("already initialized")) {
      codexHandshakeState = "warm";
    }
  }

  function cacheBridgeInitialize(parsedMessage) {
    const params = parsedMessage?.params && typeof parsedMessage.params === "object"
      ? parsedMessage.params
      : {};
    cachedInitializeParams = params;
    const clientInfo = params?.clientInfo && typeof params.clientInfo === "object"
      ? params.clientInfo
      : null;
    cachedLegacyInitializeParams = clientInfo ? { clientInfo } : null;
  }

  function primeCodexHandshake() {
    if (!workspaceRuntime.hasActiveWorkspace() || codexHandshakeState === "warm" || !cachedInitializeParams) {
      return;
    }
    sendSyntheticInitialize(cachedInitializeParams, false);
  }

  function sendSyntheticInitialize(params, usingLegacyParams) {
    if (!workspaceRuntime.hasActiveWorkspace()) {
      return;
    }
    const requestId = `androdex-initialize-${++syntheticInitializeCounter}`;
    syntheticInitializeRequest = {
      id: requestId,
      usingLegacyParams,
    };
    workspaceRuntime.sendToCodex(JSON.stringify({
      id: requestId,
      method: "initialize",
      params,
    }));
  }

  function handleSyntheticInitializeMessage(rawMessage) {
    const pendingRequest = syntheticInitializeRequest;
    if (!pendingRequest) {
      return false;
    }

    const parsed = safeParseJSON(rawMessage);
    if (!parsed || parsed.id !== pendingRequest.id) {
      return false;
    }

    syntheticInitializeRequest = null;
    if (parsed?.result != null || isAlreadyInitializedError(parsed?.error?.message)) {
      codexHandshakeState = "warm";
      if (cachedInitializedNotification && workspaceRuntime.hasActiveWorkspace()) {
        workspaceRuntime.sendToCodex(JSON.stringify({ method: "initialized" }));
      }
      return true;
    }

    if (
      !pendingRequest.usingLegacyParams
      && cachedLegacyInitializeParams
      && isCapabilitiesMismatchError(parsed?.error?.message)
    ) {
      sendSyntheticInitialize(cachedLegacyInitializeParams, true);
      return true;
    }

    const errorMessage = parsed?.error?.message;
    if (typeof errorMessage === "string" && errorMessage.trim()) {
      console.error(`[androdex] Failed to initialize the active Codex workspace: ${errorMessage}`);
    }
    return true;
  }

  function clearCachedBridgeHandshakeState() {
    forwardedInitializeRequestIds.clear();
    cachedInitializeParams = null;
    cachedLegacyInitializeParams = null;
    cachedInitializedNotification = false;
    syntheticInitializeRequest = null;
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
      if (isShuttingDown || socket !== trackedSocket) {
        clearRelayWatchdog();
        return;
      }

      if (trackedSocket.readyState !== WebSocket.OPEN) {
        return;
      }

      if (hasRelayConnectionGoneStale(lastRelayActivityAt)) {
        console.warn("[androdex] relay heartbeat stalled; forcing reconnect");
        publishBridgeStatus({
          state: "running",
          connectionStatus: "disconnected",
          pid: process.pid,
          currentCwd: workspaceRuntime.getCurrentCwd() || null,
          lastError: STALE_RELAY_STATUS_MESSAGE,
        });
        trackedSocket.terminate();
        return;
      }

      try {
        trackedSocket.ping();
      } catch {
        // Best effort only.
      }
    }, RELAY_WATCHDOG_PING_INTERVAL_MS);
    relayWatchdogTimer.unref?.();
  }

  function hasRelayConnectionGoneStale(activityAt) {
    return activityAt > 0 && (Date.now() - activityAt) >= RELAY_WATCHDOG_STALE_AFTER_MS;
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
    lastPublishedBridgeStatus = {
      ...status,
    };
    onBridgeStatus?.({
      ...status,
    });
  }

  async function shutdown() {
    if (isShuttingDown) {
      return;
    }
    isShuttingDown = true;
    clearReconnectTimer();
    clearRelayWatchdog();
    clearBridgeStatusHeartbeat();
    clearCachedBridgeHandshakeState();
    stopContextUsageWatcher({ clearHint: false });

    if (socket?.readyState === WebSocket.OPEN || socket?.readyState === WebSocket.CONNECTING) {
      socket.close();
    }
    socket = null;
    await workspaceRuntime.shutdown();
    setTimeout(() => process.exit(0), 100);
  }
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

function isAlreadyInitializedError(message) {
  return normalizeNonEmptyString(message).toLowerCase().includes("already initialized");
}

function isCapabilitiesMismatchError(message) {
  const normalized = normalizeNonEmptyString(message).toLowerCase();
  const mentionsCapabilitiesField = normalized.includes("capabilities")
    || normalized.includes("experimentalapi");
  if (!mentionsCapabilitiesField) {
    return false;
  }

  return normalized.includes("unknown field")
    || normalized.includes("unexpected field")
    || normalized.includes("unrecognized field")
    || normalized.includes("invalid param")
    || normalized.includes("invalid params")
    || normalized.includes("failed to parse")
    || normalized.includes("unsupported");
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
  }
  return headers;
}

function buildMacRegistration(deviceState) {
  const trustedPhoneEntry = Object.entries(deviceState?.trustedPhones || {})[0] || null;
  return {
    macDeviceId: normalizeNonEmptyString(deviceState?.macDeviceId),
    macIdentityPublicKey: normalizeNonEmptyString(deviceState?.macIdentityPublicKey),
    displayName: normalizeNonEmptyString(os.hostname()),
    trustedPhoneDeviceId: normalizeNonEmptyString(trustedPhoneEntry?.[0]),
    trustedPhonePublicKey: normalizeNonEmptyString(trustedPhoneEntry?.[1]),
  };
}

module.exports = {
  sanitizeThreadHistoryImagesForRelay,
  startBridge,
};
