// FILE: host-runtime.js
// Purpose: Keeps a durable relay presence alive and activates the local Codex workspace on demand.
// Layer: CLI service
// Exports: HostRuntime
// Depends on: fs, ws, ./codex-desktop-refresher, ./codex-transport, ./rollout-watch, ./session-state, ./secure-device-state, ./secure-transport

const fs = require("fs");
const path = require("path");
const WebSocket = require("ws");
const {
  CodexDesktopRefresher,
  readBridgeConfig,
} = require("./codex-desktop-refresher");
const { createCodexTransport } = require("./codex-transport");
const { createThreadRolloutActivityWatcher } = require("./rollout-watch");
const { rememberActiveThread } = require("./session-state");
const { handleGitRequest } = require("./git-handler");
const { createAccountStatusHandler } = require("./account-handler");
const { createCodexRpcClient } = require("./codex-rpc-client");
const { createNotificationsHandler } = require("./notifications-handler");
const { createPushNotificationServiceClient } = require("./push-notification-service-client");
const { createPushNotificationTracker } = require("./push-notification-tracker");
const { createRolloutLiveMirrorController } = require("./rollout-live-mirror");
const { handleWorkspaceRequest } = require("./workspace-handler");
const { loadOrCreateBridgeDeviceState } = require("./secure-device-state");
const { createBridgeSecureTransport } = require("./secure-transport");
const { readDaemonRuntimeState, writeDaemonRuntimeState } = require("./daemon-store");
const {
  extractBridgeMessageContext,
  normalizeRuntimeCompatibleRequest,
  sanitizeThreadHistoryImagesForRelay,
  shouldStartContextUsageWatcher,
} = require("./runtime-compat");

class HostRuntime {
  constructor({
    env = process.env,
    platform = process.platform,
    WebSocketImpl = WebSocket,
    setTimeoutFn = setTimeout,
    clearTimeoutFn = clearTimeout,
    relayConnectTimeoutMs = 12_000,
  } = {}) {
    this.platform = platform;
    this.WebSocketImpl = WebSocketImpl;
    this.setTimeoutFn = setTimeoutFn;
    this.clearTimeoutFn = clearTimeoutFn;
    this.relayConnectTimeoutMs = relayConnectTimeoutMs;
    this.config = readBridgeConfig({ env, platform });
    this.relayBaseUrl = this.config.relayUrl.replace(/\/+$/, "");
    this.deviceState = loadOrCreateBridgeDeviceState();
    this.hostId = this.deviceState.hostId;
    this.relayHostUrl = `${this.relayBaseUrl}/${this.hostId}`;
    this.desktopRefresher = new CodexDesktopRefresher({
      enabled: this.config.refreshEnabled,
      debounceMs: this.config.refreshDebounceMs,
      refreshCommand: this.config.refreshCommand,
      bundleId: this.config.codexBundleId,
      appPath: this.config.codexAppPath,
    });
    this.secureTransport = createBridgeSecureTransport({
      hostId: this.hostId,
      relayUrl: this.relayBaseUrl,
      deviceState: this.deviceState,
    });
    this.pushServiceClient = createPushNotificationServiceClient({
      baseUrl: this.config.pushServiceUrl,
      sessionId: this.hostId,
    });
    this.notificationsHandler = createNotificationsHandler({
      pushServiceClient: this.pushServiceClient,
    });
    this.pushNotificationTracker = createPushNotificationTracker({
      sessionId: this.hostId,
      pushServiceClient: this.pushServiceClient,
      previewMaxChars: this.config.pushPreviewMaxChars,
    });
    this.codexRpcClient = createCodexRpcClient({
      sendToCodex: (message) => {
        if (!this.codex) {
          throw new Error("No active Codex workspace on the host.");
        }
        this.codex.send(message);
      },
      requestIdPrefix: `androdex-host-${this.hostId}`,
    });
    this.accountStatusHandler = createAccountStatusHandler({
      sendCodexRequest: (...args) => this.codexRpcClient.sendRequest(...args),
    });
    this.rolloutLiveMirror = !this.config.codexEndpoint
      ? createRolloutLiveMirrorController({
        sendApplicationResponse: this.sendApplicationResponse.bind(this),
      })
      : null;
    this.socket = null;
    this.codex = null;
    this.currentCwd = "";
    this.reconnectAttempt = 0;
    this.reconnectTimer = null;
    this.connectTimeoutTimer = null;
    this.connectGeneration = 0;
    this.activationQueue = Promise.resolve();
    this.activationSequence = 0;
    this.relayStatus = "disconnected";
    this.codexHandshakeState = "cold";
    this.forwardedInitializeRequestIds = new Set();
    this.relaySanitizedResponseMethodsById = new Map();
    this.relaySanitizedRequestMethods = new Set([
      "thread/read",
      "thread/resume",
    ]);
    this.forwardedRequestMethodTTLms = 2 * 60_000;
    this.cachedInitializeParams = null;
    this.cachedLegacyInitializeParams = null;
    this.cachedInitializedNotification = false;
    this.syntheticInitializeRequest = null;
    this.syntheticInitializeCounter = 0;
    this.contextUsageWatcher = null;
    this.watchedContextUsageKey = null;
    this.isStopping = false;
    this.runtimeState = readDaemonRuntimeState();
  }

  start() {
    this.pushServiceClient.logUnavailable();
    this.connectRelay();
    const rememberedCwd = normalizeNonEmptyString(this.runtimeState.lastActiveCwd);
    if (rememberedCwd && isExistingDirectory(rememberedCwd)) {
      void this.activateWorkspace({ cwd: rememberedCwd }).catch((error) => {
        console.error(`[androdex] Failed to restore workspace ${rememberedCwd}: ${error.message}`);
      });
    }
  }

  async stop() {
    this.isStopping = true;
    this.clearReconnectTimer();
    this.clearConnectTimeout();
    this.clearCachedBridgeHandshakeState();
    this.stopContextUsageWatcher();
    this.rolloutLiveMirror?.stopAll();
    this.desktopRefresher.handleTransportReset();
    if (
      this.socket?.readyState === this.WebSocketImpl.OPEN
      || this.socket?.readyState === this.WebSocketImpl.CONNECTING
    ) {
      this.socket.close();
    }
    this.socket = null;
    await this.shutdownCodex();
  }

  async activateWorkspace({ cwd = "" } = {}) {
    const nextCwd = normalizeWorkspacePath(normalizeNonEmptyString(cwd) || process.cwd(), this.platform);
    if (!isExistingDirectory(nextCwd)) {
      throw new Error(`Workspace directory not found: ${nextCwd}`);
    }

    const activationId = ++this.activationSequence;
    const activation = this.activationQueue.then(() => this.performWorkspaceActivation({
      cwd: nextCwd,
      activationId,
    }));

    this.activationQueue = activation.then(
      () => undefined,
      () => undefined
    );

    return activation;
  }

  getPairingPayload() {
    return this.secureTransport.createPairingPayload();
  }

  getStatus() {
    const currentDeviceState = this.secureTransport.getCurrentDeviceState();
    return {
      hostId: this.hostId,
      macDeviceId: currentDeviceState.macDeviceId,
      relayUrl: this.relayBaseUrl,
      relayStatus: this.relayStatus,
      currentCwd: this.currentCwd || null,
      workspaceActive: Boolean(this.codex),
      hasTrustedPhone: Object.keys(currentDeviceState.trustedPhones || {}).length > 0,
    };
  }

  getWorkspaceState() {
    return {
      activeCwd: this.currentCwd || "",
      recentWorkspaces: Array.isArray(this.runtimeState.recentWorkspaces)
        ? [...this.runtimeState.recentWorkspaces]
        : [],
    };
  }

  clearReconnectTimer() {
    if (!this.reconnectTimer) {
      return;
    }
    this.clearTimeoutFn(this.reconnectTimer);
    this.reconnectTimer = null;
  }

  clearConnectTimeout(timer = this.connectTimeoutTimer) {
    if (!timer) {
      return;
    }
    this.clearTimeoutFn(timer);
    if (this.connectTimeoutTimer === timer) {
      this.connectTimeoutTimer = null;
    }
  }

  scheduleRelayReconnect(closeCode) {
    if (this.isStopping) {
      return;
    }
    if (closeCode === 4000) {
      this.relayStatus = "disconnected";
      return;
    }
    if (this.reconnectTimer) {
      return;
    }

    this.reconnectAttempt += 1;
    const delayMs = Math.min(1_000 * this.reconnectAttempt, 5_000);
    this.relayStatus = "connecting";
    this.reconnectTimer = this.setTimeoutFn(() => {
      this.reconnectTimer = null;
      this.connectRelay();
    }, delayMs);
  }

  connectRelay() {
    if (this.isStopping) {
      return;
    }
    if (
      this.socket?.readyState === this.WebSocketImpl.OPEN
      || this.socket?.readyState === this.WebSocketImpl.CONNECTING
    ) {
      return;
    }

    this.clearReconnectTimer();
    this.relayStatus = "connecting";
    const connectGeneration = ++this.connectGeneration;
    const nextSocket = new this.WebSocketImpl(this.relayHostUrl, {
      headers: { "x-role": "mac" },
    });
    this.socket = nextSocket;
    this.clearConnectTimeout();
    const connectTimeoutTimer = this.setTimeoutFn(() => {
      if (!this.isCurrentRelaySocket(nextSocket, connectGeneration)) {
        return;
      }
      this.relayStatus = "disconnected";
      this.forceCloseSocket(nextSocket);
    }, this.relayConnectTimeoutMs);
    this.connectTimeoutTimer = connectTimeoutTimer;

    nextSocket.on("open", () => {
      if (!this.isCurrentRelaySocket(nextSocket, connectGeneration)) {
        this.forceCloseSocket(nextSocket);
        return;
      }
      this.clearReconnectTimer();
      this.clearConnectTimeout(connectTimeoutTimer);
      this.reconnectAttempt = 0;
      this.relayStatus = "connected";
      this.secureTransport.bindLiveSendWireMessage((wireMessage) => {
        if (nextSocket.readyState === this.WebSocketImpl.OPEN) {
          nextSocket.send(wireMessage);
        }
      });
    });

    nextSocket.on("message", (data) => {
      if (!this.isCurrentRelaySocket(nextSocket, connectGeneration)) {
        return;
      }
      const message = typeof data === "string" ? data : data.toString("utf8");
      if (this.secureTransport.handleIncomingWireMessage(message, {
        sendControlMessage: (controlMessage) => {
          if (nextSocket.readyState === this.WebSocketImpl.OPEN) {
            nextSocket.send(JSON.stringify(controlMessage));
          }
        },
        onApplicationMessage: (plaintextMessage) => {
          this.handleApplicationMessage(plaintextMessage);
        },
      })) {
        return;
      }
    });

    nextSocket.on("close", (code) => {
      if (!this.isCurrentRelaySocket(nextSocket, connectGeneration)) {
        return;
      }
      this.clearConnectTimeout(connectTimeoutTimer);
      this.relayStatus = "disconnected";
      this.socket = null;
      this.clearCachedBridgeHandshakeState();
      this.stopContextUsageWatcher();
      this.rolloutLiveMirror?.stopAll();
      this.desktopRefresher.handleTransportReset();
      this.scheduleRelayReconnect(code);
    });

    nextSocket.on("error", () => {
      if (!this.isCurrentRelaySocket(nextSocket, connectGeneration)) {
        return;
      }
      this.clearConnectTimeout(connectTimeoutTimer);
      this.relayStatus = "disconnected";
      if (
        nextSocket.readyState === this.WebSocketImpl.CONNECTING
        || nextSocket.readyState === this.WebSocketImpl.OPEN
      ) {
        this.forceCloseSocket(nextSocket);
      }
    });
  }

  isCurrentRelaySocket(socket, connectGeneration) {
    return !this.isStopping
      && this.socket === socket
      && this.connectGeneration === connectGeneration;
  }

  forceCloseSocket(socket) {
    if (!socket) {
      return;
    }
    if (typeof socket.terminate === "function") {
      socket.terminate();
      return;
    }
    try {
      socket.close();
    } catch {
      // Best effort only.
    }
  }

  async shutdownCodex() {
    const activeCodex = this.codex;
    this.syntheticInitializeRequest = null;
    this.stopContextUsageWatcher();
    this.rolloutLiveMirror?.stopAll();
    if (!activeCodex) {
      this.codex = null;
      return;
    }

    this.codex = null;
    this.codexRpcClient.rejectAllPending(new Error("The active Codex workspace closed before the bridge RPC completed."));
    this.codexHandshakeState = "cold";
    try {
      activeCodex.shutdown();
    } catch {
      // Ignore shutdown failures for stale transports.
    }
  }

  async performWorkspaceActivation({ cwd, activationId }) {
    if (activationId !== this.activationSequence) {
      return this.getStatus();
    }

    if (this.currentCwd === cwd && this.codex) {
      return this.getStatus();
    }

    await this.shutdownCodex();
    this.currentCwd = cwd;
    this.rememberRecentWorkspace(cwd);
    this.codexHandshakeState = this.config.codexEndpoint ? "warm" : "cold";
    this.forwardedInitializeRequestIds.clear();

    const codex = createCodexTransport({
      endpoint: this.config.codexEndpoint,
      env: process.env,
      cwd,
    });
    this.codex = codex;

    codex.onError((error) => {
      if (this.codex !== codex) {
        return;
      }
      if (this.config.codexEndpoint) {
        console.error(`[androdex] Failed to connect to Codex endpoint: ${this.config.codexEndpoint}`);
      } else {
        console.error("[androdex] Failed to start `codex app-server` for the active workspace.");
        console.error(`[androdex] Launch command: ${codex.describe()}`);
      }
      console.error(error.message);
      this.codex = null;
      this.codexHandshakeState = "cold";
    });

    codex.onMessage((message) => {
      if (this.codex !== codex) {
        return;
      }
      if (this.handleSyntheticInitializeMessage(message)) {
        return;
      }
      if (this.codexRpcClient.handleCodexMessage(message)) {
        return;
      }
      this.trackCodexHandshakeState(message);
      this.desktopRefresher.handleOutbound(message);
      this.pushNotificationTracker.handleOutbound(message);
      this.rememberThreadFromMessage("codex", message);
      this.secureTransport.queueOutboundApplicationMessage(this.sanitizeRelayBoundCodexMessage(message), (wireMessage) => {
        if (this.socket?.readyState === WebSocket.OPEN) {
          this.socket.send(wireMessage);
        }
      });
    });

    codex.onClose(() => {
      if (this.codex !== codex) {
        return;
      }
      this.desktopRefresher.handleTransportReset();
      this.stopContextUsageWatcher();
      this.rolloutLiveMirror?.stopAll();
      this.codex = null;
      this.codexHandshakeState = "cold";
    });

    this.primeCodexHandshake();
    return this.getStatus();
  }

  handleApplicationMessage(rawMessage) {
    const normalizedRawMessage = normalizeRuntimeCompatibleRequest(rawMessage);
    if (this.handleBridgeManagedHandshakeMessage(normalizedRawMessage)) {
      return;
    }
    if (handleWorkspaceRequest(normalizedRawMessage, this.sendApplicationResponse.bind(this), {
      activateWorkspace: this.activateWorkspace.bind(this),
      getWorkspaceState: this.getWorkspaceState.bind(this),
      platform: this.platform,
    })) {
      return;
    }
    if (this.notificationsHandler.handleNotificationsRequest(normalizedRawMessage, this.sendApplicationResponse.bind(this))) {
      return;
    }
    if (this.accountStatusHandler.handleAccountStatusRequest(normalizedRawMessage, this.sendApplicationResponse.bind(this))) {
      return;
    }
    if (handleGitRequest(normalizedRawMessage, this.sendApplicationResponse.bind(this))) {
      return;
    }

    if (!this.codex) {
      this.respondWorkspaceNotActive(normalizedRawMessage);
      return;
    }

    this.desktopRefresher.handleInbound(normalizedRawMessage);
    this.rolloutLiveMirror?.observeInbound(normalizedRawMessage);
    this.rememberForwardedRequestMethod(normalizedRawMessage);
    this.rememberThreadFromMessage("android", normalizedRawMessage);
    this.codex.send(normalizedRawMessage);
  }

  sendApplicationResponse(rawMessage) {
    this.secureTransport.queueOutboundApplicationMessage(rawMessage, (wireMessage) => {
      if (this.socket?.readyState === WebSocket.OPEN) {
        this.socket.send(wireMessage);
      }
    });
  }

  rememberThreadFromMessage(source, rawMessage) {
    const context = extractBridgeMessageContext(rawMessage);
    if (!context.threadId) {
      return;
    }
    rememberActiveThread(context.threadId, source);
    if (shouldStartContextUsageWatcher(context)) {
      this.ensureContextUsageWatcher(context);
    }
  }

  rememberForwardedRequestMethod(rawMessage) {
    const context = extractBridgeMessageContext(rawMessage);
    const parsed = safeParseJSON(rawMessage);
    const requestId = parsed?.id;
    if (!context.method || requestId == null) {
      return;
    }

    this.pruneExpiredForwardedRequestMethods();
    if (this.relaySanitizedRequestMethods.has(context.method)) {
      this.relaySanitizedResponseMethodsById.set(String(requestId), {
        method: context.method,
        createdAt: Date.now(),
      });
    }
  }

  sanitizeRelayBoundCodexMessage(rawMessage) {
    this.pruneExpiredForwardedRequestMethods();
    const parsed = safeParseJSON(rawMessage);
    const responseId = parsed?.id;
    if (responseId == null) {
      return rawMessage;
    }

    const trackedRequest = this.relaySanitizedResponseMethodsById.get(String(responseId));
    if (!trackedRequest) {
      return rawMessage;
    }
    this.relaySanitizedResponseMethodsById.delete(String(responseId));

    return sanitizeThreadHistoryImagesForRelay(rawMessage, trackedRequest.method);
  }

  pruneExpiredForwardedRequestMethods(now = Date.now()) {
    for (const [requestId, trackedRequest] of this.relaySanitizedResponseMethodsById.entries()) {
      if (!trackedRequest || (now - trackedRequest.createdAt) >= this.forwardedRequestMethodTTLms) {
        this.relaySanitizedResponseMethodsById.delete(requestId);
      }
    }
  }

  ensureContextUsageWatcher({ threadId, turnId }) {
    const normalizedThreadId = readString(threadId);
    const normalizedTurnId = readString(turnId);
    if (!normalizedThreadId) {
      return;
    }

    const nextWatcherKey = `${normalizedThreadId}|${normalizedTurnId || "pending-turn"}`;
    if (this.watchedContextUsageKey === nextWatcherKey && this.contextUsageWatcher) {
      return;
    }

    this.stopContextUsageWatcher();
    this.watchedContextUsageKey = nextWatcherKey;
    this.contextUsageWatcher = createThreadRolloutActivityWatcher({
      threadId: normalizedThreadId,
      turnId: normalizedTurnId,
      onUsage: ({ threadId: usageThreadId, usage }) => {
        this.sendContextUsageNotification(usageThreadId, usage);
      },
      onIdle: () => {
        if (this.watchedContextUsageKey === nextWatcherKey) {
          this.stopContextUsageWatcher();
        }
      },
      onTimeout: () => {
        if (this.watchedContextUsageKey === nextWatcherKey) {
          this.stopContextUsageWatcher();
        }
      },
      onError: () => {
        if (this.watchedContextUsageKey === nextWatcherKey) {
          this.stopContextUsageWatcher();
        }
      },
    });
  }

  stopContextUsageWatcher() {
    if (this.contextUsageWatcher) {
      this.contextUsageWatcher.stop();
    }

    this.contextUsageWatcher = null;
    this.watchedContextUsageKey = null;
  }

  sendContextUsageNotification(threadId, usage) {
    if (!threadId || !usage) {
      return;
    }

    this.sendApplicationResponse(JSON.stringify({
      method: "thread/tokenUsage/updated",
      params: {
        threadId,
        usage,
      },
    }));
  }

  handleBridgeManagedHandshakeMessage(rawMessage) {
    const parsed = safeParseJSON(rawMessage);
    if (!parsed) {
      return false;
    }

    const method = typeof parsed?.method === "string" ? parsed.method.trim() : "";
    if (!method) {
      return false;
    }

    if (method === "initialize" && parsed.id != null) {
      this.cacheBridgeInitialize(parsed);
      if (!this.codex) {
        this.sendApplicationResponse(JSON.stringify({
          id: parsed.id,
          result: {
            bridgeManaged: true,
            workspaceActive: false,
          },
        }));
        return true;
      }

      if (this.codexHandshakeState !== "warm") {
        this.forwardedInitializeRequestIds.add(String(parsed.id));
        return false;
      }

      this.sendApplicationResponse(JSON.stringify({
        id: parsed.id,
        result: {
          bridgeManaged: true,
          workspaceActive: true,
        },
      }));
      return true;
    }

    if (method === "initialized") {
      this.cachedInitializedNotification = true;
      return this.codexHandshakeState === "warm" || !this.codex;
    }

    return false;
  }

  respondWorkspaceNotActive(rawMessage) {
    const parsed = safeParseJSON(rawMessage);
    if (!parsed || parsed.id == null) {
      return;
    }

    this.sendApplicationResponse(JSON.stringify({
      id: parsed.id,
      error: {
        code: -32000,
        message: "No active workspace on the host. Choose a project from the Android app to get started.",
      },
    }));
  }

  rememberRecentWorkspace(cwd) {
    const normalized = normalizeWorkspacePath(cwd, this.platform);
    const recentWorkspaces = Array.isArray(this.runtimeState.recentWorkspaces)
      ? [...this.runtimeState.recentWorkspaces]
      : [];
    const targetKey = this.platform === "win32" ? normalized.toLowerCase() : normalized;
    const nextRecentWorkspaces = [
      normalized,
      ...recentWorkspaces.filter((candidate) => {
        const candidatePath = normalizeWorkspacePath(candidate, this.platform);
        const candidateKey = this.platform === "win32"
          ? candidatePath.toLowerCase()
          : candidatePath;
        return candidatePath && candidateKey !== targetKey;
      }),
    ].slice(0, 25);

    this.runtimeState = {
      ...this.runtimeState,
      lastActiveCwd: normalized,
      recentWorkspaces: nextRecentWorkspaces,
    };
    writeDaemonRuntimeState(this.runtimeState);
  }

  trackCodexHandshakeState(rawMessage) {
    const parsed = safeParseJSON(rawMessage);
    const responseId = parsed?.id;
    if (responseId == null) {
      return;
    }

    const responseKey = String(responseId);
    if (!this.forwardedInitializeRequestIds.has(responseKey)) {
      return;
    }

    this.forwardedInitializeRequestIds.delete(responseKey);
    if (parsed?.result != null) {
      this.codexHandshakeState = "warm";
      return;
    }

    const errorMessage = typeof parsed?.error?.message === "string"
      ? parsed.error.message.toLowerCase()
      : "";
    if (errorMessage.includes("already initialized")) {
      this.codexHandshakeState = "warm";
    }
  }

  cacheBridgeInitialize(parsedMessage) {
    const params = parsedMessage?.params && typeof parsedMessage.params === "object"
      ? parsedMessage.params
      : {};
    this.cachedInitializeParams = params;
    const clientInfo = params?.clientInfo && typeof params.clientInfo === "object"
      ? params.clientInfo
      : null;
    this.cachedLegacyInitializeParams = clientInfo ? { clientInfo } : null;
  }

  primeCodexHandshake() {
    if (!this.codex || this.codexHandshakeState === "warm" || !this.cachedInitializeParams) {
      return;
    }
    this.sendSyntheticInitialize(this.cachedInitializeParams, false);
  }

  sendSyntheticInitialize(params, usingLegacyParams) {
    if (!this.codex) {
      return;
    }
    const requestId = `androdex-initialize-${++this.syntheticInitializeCounter}`;
    this.syntheticInitializeRequest = {
      id: requestId,
      usingLegacyParams,
    };
    this.codex.send(JSON.stringify({
      id: requestId,
      method: "initialize",
      params,
    }));
  }

  handleSyntheticInitializeMessage(rawMessage) {
    const pendingRequest = this.syntheticInitializeRequest;
    if (!pendingRequest) {
      return false;
    }

    const parsed = safeParseJSON(rawMessage);
    if (!parsed || parsed.id !== pendingRequest.id) {
      return false;
    }

    this.syntheticInitializeRequest = null;
    if (parsed?.result != null || isAlreadyInitializedError(parsed?.error?.message)) {
      this.codexHandshakeState = "warm";
      if (this.cachedInitializedNotification && this.codex) {
        this.codex.send(JSON.stringify({ method: "initialized" }));
      }
      return true;
    }

    if (
      !pendingRequest.usingLegacyParams
      && this.cachedLegacyInitializeParams
      && isCapabilitiesMismatchError(parsed?.error?.message)
    ) {
      this.sendSyntheticInitialize(this.cachedLegacyInitializeParams, true);
      return true;
    }

    const errorMessage = parsed?.error?.message;
    if (typeof errorMessage === "string" && errorMessage.trim()) {
      console.error(`[androdex] Failed to initialize the active Codex workspace: ${errorMessage}`);
    }
    return true;
  }

  clearCachedBridgeHandshakeState() {
    this.forwardedInitializeRequestIds.clear();
    this.cachedInitializeParams = null;
    this.cachedLegacyInitializeParams = null;
    this.cachedInitializedNotification = false;
    this.syntheticInitializeRequest = null;
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
  return typeof value === "string" ? value.trim() : "";
}

function normalizeWorkspacePath(value, platform = process.platform) {
  const trimmed = normalizeNonEmptyString(value);
  if (!trimmed) {
    return "";
  }
  const normalized = path.normalize(trimmed);
  return platform === "win32" && /^[a-z]:/.test(normalized)
    ? `${normalized.charAt(0).toUpperCase()}${normalized.slice(1)}`
    : normalized;
}

function isAlreadyInitializedError(message) {
  return normalizeNonEmptyString(message).toLowerCase().includes("already initialized");
}

function isCapabilitiesMismatchError(message) {
  const normalized = normalizeNonEmptyString(message).toLowerCase();
  return normalized.includes("capabilities") || normalized.includes("experimentalapi");
}

function isExistingDirectory(targetPath) {
  try {
    return fs.statSync(targetPath).isDirectory();
  } catch {
    return false;
  }
}

module.exports = {
  HostRuntime,
};
