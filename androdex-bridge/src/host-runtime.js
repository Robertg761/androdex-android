// FILE: host-runtime.js
// Purpose: Keeps a durable relay presence alive and activates the local Codex workspace on demand.
// Layer: CLI service
// Exports: HostRuntime
// Depends on: fs, ws, ./codex-desktop-refresher, ./codex-transport, ./session-state, ./secure-device-state, ./secure-transport

const fs = require("fs");
const WebSocket = require("ws");
const {
  CodexDesktopRefresher,
  readBridgeConfig,
} = require("./codex-desktop-refresher");
const { createCodexTransport } = require("./codex-transport");
const { rememberActiveThread } = require("./session-state");
const { handleGitRequest } = require("./git-handler");
const { handleWorkspaceRequest } = require("./workspace-handler");
const { loadOrCreateBridgeDeviceState } = require("./secure-device-state");
const { createBridgeSecureTransport } = require("./secure-transport");
const { readDaemonRuntimeState, writeDaemonRuntimeState } = require("./daemon-store");

class HostRuntime {
  constructor({ env = process.env, platform = process.platform } = {}) {
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
    this.socket = null;
    this.codex = null;
    this.currentCwd = "";
    this.reconnectAttempt = 0;
    this.reconnectTimer = null;
    this.activationQueue = Promise.resolve();
    this.activationSequence = 0;
    this.relayStatus = "disconnected";
    this.codexHandshakeState = "cold";
    this.forwardedInitializeRequestIds = new Set();
    this.cachedInitializeParams = null;
    this.cachedLegacyInitializeParams = null;
    this.cachedInitializedNotification = false;
    this.syntheticInitializeRequest = null;
    this.syntheticInitializeCounter = 0;
    this.isStopping = false;
    this.runtimeState = readDaemonRuntimeState();
  }

  start() {
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
    this.clearCachedBridgeHandshakeState();
    this.desktopRefresher.handleTransportReset();
    if (this.socket?.readyState === WebSocket.OPEN || this.socket?.readyState === WebSocket.CONNECTING) {
      this.socket.close();
    }
    this.socket = null;
    await this.shutdownCodex();
  }

  async activateWorkspace({ cwd = "" } = {}) {
    const nextCwd = normalizeNonEmptyString(cwd) || process.cwd();
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

  clearReconnectTimer() {
    if (!this.reconnectTimer) {
      return;
    }
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
  }

  scheduleRelayReconnect(closeCode) {
    if (this.isStopping) {
      return;
    }
    if (closeCode === 4000 || closeCode === 4001) {
      this.relayStatus = "disconnected";
      return;
    }
    if (this.reconnectTimer) {
      return;
    }

    this.reconnectAttempt += 1;
    const delayMs = Math.min(1_000 * this.reconnectAttempt, 5_000);
    this.relayStatus = "connecting";
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connectRelay();
    }, delayMs);
  }

  connectRelay() {
    if (this.isStopping) {
      return;
    }

    this.relayStatus = "connecting";
    const nextSocket = new WebSocket(this.relayHostUrl, {
      headers: { "x-role": "mac" },
    });
    this.socket = nextSocket;

    nextSocket.on("open", () => {
      this.clearReconnectTimer();
      this.reconnectAttempt = 0;
      this.relayStatus = "connected";
      this.secureTransport.bindLiveSendWireMessage((wireMessage) => {
        if (nextSocket.readyState === WebSocket.OPEN) {
          nextSocket.send(wireMessage);
        }
      });
    });

    nextSocket.on("message", (data) => {
      const message = typeof data === "string" ? data : data.toString("utf8");
      if (this.secureTransport.handleIncomingWireMessage(message, {
        sendControlMessage: (controlMessage) => {
          if (nextSocket.readyState === WebSocket.OPEN) {
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
      this.relayStatus = "disconnected";
      if (this.socket === nextSocket) {
        this.socket = null;
      }
      this.clearCachedBridgeHandshakeState();
      this.desktopRefresher.handleTransportReset();
      this.scheduleRelayReconnect(code);
    });

    nextSocket.on("error", () => {
      this.relayStatus = "disconnected";
    });
  }

  async shutdownCodex() {
    const activeCodex = this.codex;
    this.syntheticInitializeRequest = null;
    if (!activeCodex) {
      this.codex = null;
      return;
    }

    this.codex = null;
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
    writeDaemonRuntimeState({ lastActiveCwd: cwd });
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
      this.trackCodexHandshakeState(message);
      this.desktopRefresher.handleOutbound(message);
      this.rememberThreadFromMessage("codex", message);
      this.secureTransport.queueOutboundApplicationMessage(message, (wireMessage) => {
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
      this.codex = null;
      this.codexHandshakeState = "cold";
    });

    this.primeCodexHandshake();
    return this.getStatus();
  }

  handleApplicationMessage(rawMessage) {
    if (this.handleBridgeManagedHandshakeMessage(rawMessage)) {
      return;
    }
    if (handleWorkspaceRequest(rawMessage, this.sendApplicationResponse.bind(this))) {
      return;
    }
    if (handleGitRequest(rawMessage, this.sendApplicationResponse.bind(this))) {
      return;
    }

    if (!this.codex) {
      this.respondWorkspaceNotActive(rawMessage);
      return;
    }

    this.desktopRefresher.handleInbound(rawMessage);
    this.rememberThreadFromMessage("phone", rawMessage);
    this.codex.send(rawMessage);
  }

  sendApplicationResponse(rawMessage) {
    this.secureTransport.queueOutboundApplicationMessage(rawMessage, (wireMessage) => {
      if (this.socket?.readyState === WebSocket.OPEN) {
        this.socket.send(wireMessage);
      }
    });
  }

  rememberThreadFromMessage(source, rawMessage) {
    const threadId = extractThreadId(rawMessage);
    if (!threadId) {
      return;
    }
    rememberActiveThread(threadId, source);
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
        message: "No active workspace on the host. Run `androdex up` in the project you want to use.",
      },
    }));
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

function extractThreadId(rawMessage) {
  const parsed = safeParseJSON(rawMessage);
  if (!parsed) {
    return null;
  }

  const method = parsed?.method;
  const params = parsed?.params;
  if (method === "turn/start") {
    return readString(params?.threadId) || readString(params?.thread_id);
  }
  if (method === "thread/start" || method === "thread/started") {
    return (
      readString(params?.threadId)
      || readString(params?.thread_id)
      || readString(params?.thread?.id)
      || readString(params?.thread?.threadId)
      || readString(params?.thread?.thread_id)
    );
  }
  if (method === "turn/completed") {
    return (
      readString(params?.threadId)
      || readString(params?.thread_id)
      || readString(params?.turn?.threadId)
      || readString(params?.turn?.thread_id)
    );
  }
  return null;
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
