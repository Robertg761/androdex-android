// FILE: runtime/adapter.js
// Purpose: Provides the first runtime-target adapter seam so the bridge can switch host runtimes without pushing target-specific lifecycle into workspace orchestration.
// Layer: CLI helper
// Exports: createRuntimeAdapter
// Depends on: ws, ../codex/transport, ./method-policy, ./t3-suitability, ./target-config

const WebSocket = require("ws");
const { createCodexTransport } = require("../codex/transport");
const {
  buildT3ThreadListResult,
  buildT3ThreadReadResult,
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
  WebSocketImpl = WebSocket,
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
  let snapshotCache = null;
  let runtimeMetadata = {
    runtimeAttachState: "probing",
    runtimeAttachFailure: null,
    runtimeProtocolVersion: null,
    runtimeAuthMode: null,
    runtimeStateRoot: null,
    runtimeEndpointHost: normalizeNonEmptyString(parsedEndpoint.hostname),
    runtimeSnapshotSequence: null,
  };
  const transport = createEndpointWebSocketTransport({
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

  async function performSuitabilityProbe({ sendRequest }) {
    updateRuntimeMetadata({
      runtimeAttachState: "probing",
      runtimeAttachFailure: null,
    });
    const configResult = await sendRequest("server.getConfig", {}, {
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
      runtimeAttachState: "ready",
      runtimeAttachFailure: null,
      ...validatedMetadata,
    });
    await refreshSnapshotCache();
  }

  async function refreshSnapshotCache() {
    const snapshot = await transport.request("orchestration.getSnapshot", {}, {
      timeoutMs: T3_ATTACH_TIMEOUT_MS,
    });
    snapshotCache = snapshot && typeof snapshot === "object" ? snapshot : {
      snapshotSequence: null,
      projects: [],
      threads: [],
      updatedAt: null,
    };
    updateRuntimeMetadata({
      runtimeSnapshotSequence: snapshotCache?.snapshotSequence ?? null,
    });
    return snapshotCache;
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
      void refreshSnapshotCache()
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
      void refreshSnapshotCache()
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
      void refreshSnapshotCache()
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
    shutdown: transport.shutdown,
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

function createEndpointWebSocketTransport({
  endpoint,
  WebSocketImpl = WebSocket,
  onBeforeReadyRequest = null,
} = {}) {
  const socket = new WebSocketImpl(endpoint);
  const listeners = createListenerBag();
  const openState = WebSocketImpl.OPEN ?? WebSocket.OPEN ?? 1;
  const connectingState = WebSocketImpl.CONNECTING ?? WebSocket.CONNECTING ?? 0;
  const pendingInternalRequests = new Map();
  let nextInternalRequestId = 0;
  let readyState = "connecting";
  let settleReady = null;
  const readyPromise = new Promise((resolve, reject) => {
    settleReady = {
      resolve,
      reject,
    };
    socket.on("open", async () => {
      try {
        await onBeforeReadyRequest?.({
          sendRequest,
        });
        readyState = "ready";
        settleReady?.resolve();
        settleReady = null;
      } catch (error) {
        readyState = "failed";
        settleReady?.reject(error);
        settleReady = null;
        try {
          socket.close();
        } catch {
          // Best effort only.
        }
        listeners.emitError(error);
      }
    });
  });

  socket.on("message", (chunk) => {
    const message = typeof chunk === "string" ? chunk : chunk.toString("utf8");
    const parsed = safeParseJSON(message);
    if (parsed?.id != null && pendingInternalRequests.has(String(parsed.id))) {
      const request = pendingInternalRequests.get(String(parsed.id));
      pendingInternalRequests.delete(String(parsed.id));
      clearTimeout(request.timeout);
      if (parsed.error) {
        request.reject(new Error(normalizeNonEmptyString(parsed.error?.message) || "T3 internal request failed."));
      } else {
        request.resolve(parsed.result ?? null);
      }
      return;
    }

    if (message.trim()) {
      listeners.emitMessage(message);
    }
  });

  socket.on("close", (code, reason) => {
    const safeReason = reason ? reason.toString("utf8") : "no reason";
    if (readyState !== "ready") {
      readyState = "failed";
      settleReady?.reject(new Error(`T3 endpoint closed during attach: ${safeReason}`));
      settleReady = null;
    }
    rejectPendingInternalRequests(
      pendingInternalRequests,
      new Error(`T3 endpoint closed before the attach flow completed: ${safeReason}`)
    );
    listeners.emitClose(code, safeReason);
  });

  socket.on("error", (error) => {
    if (readyState !== "ready") {
      readyState = "failed";
      settleReady?.reject(error);
      settleReady = null;
    }
    rejectPendingInternalRequests(pendingInternalRequests, error);
    listeners.emitError(error);
  });

  function sendRequest(method, params = {}, { timeoutMs = T3_ATTACH_TIMEOUT_MS } = {}) {
    const requestId = `androdex-t3-probe-${++nextInternalRequestId}`;
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        pendingInternalRequests.delete(requestId);
        reject(new Error(`T3 request "${method}" timed out during attach suitability probing.`));
      }, timeoutMs);
      timeout.unref?.();

      pendingInternalRequests.set(requestId, {
        resolve,
        reject,
        timeout,
      });
      const payload = JSON.stringify({
        id: requestId,
        method,
        params,
      });
      if (socket.readyState === openState) {
        socket.send(payload);
        return;
      }

      clearTimeout(timeout);
      pendingInternalRequests.delete(requestId);
      reject(new Error("The T3 endpoint is not open for attach probing."));
    });
  }

  return {
    mode: "websocket",
    describe() {
      return endpoint;
    },
    send(message) {
      if (readyState !== "ready") {
        return false;
      }
      if (socket.readyState === openState) {
        socket.send(message);
        return true;
      }
      return false;
    },
    onMessage(handler) {
      listeners.onMessage = handler;
    },
    onClose(handler) {
      listeners.onClose = handler;
    },
    onError(handler) {
      listeners.onError = handler;
    },
    shutdown() {
      if (socket.readyState === openState || socket.readyState === connectingState) {
        socket.close();
      }
    },
    request: sendRequest,
    whenReady() {
      return readyPromise;
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

function rejectPendingInternalRequests(pendingRequests, error) {
  for (const [requestId, request] of pendingRequests.entries()) {
    pendingRequests.delete(requestId);
    clearTimeout(request.timeout);
    request.reject(error);
  }
}

function normalizeNonEmptyString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
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
