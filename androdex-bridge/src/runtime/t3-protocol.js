// FILE: runtime/t3-protocol.js
// Purpose: Speaks the real T3 WebSocket RPC envelope so the bridge can probe, snapshot, and subscribe without assuming JSON-RPC.
// Layer: CLI helper
// Exports: createT3EndpointTransport
// Depends on: ws

const WebSocket = require("ws");

const DEFAULT_T3_REQUEST_TIMEOUT_MS = 5_000;

function createT3EndpointTransport({
  endpoint,
  authToken = "",
  WebSocketImpl = WebSocket,
  onBeforeReadyRequest = null,
} = {}) {
  const socket = new WebSocketImpl(composeT3EndpointUrl(endpoint, authToken));
  const listeners = createListenerBag();
  const openState = WebSocketImpl.OPEN ?? WebSocket.OPEN ?? 1;
  const connectingState = WebSocketImpl.CONNECTING ?? WebSocket.CONNECTING ?? 0;
  const pendingUnaryRequests = new Map();
  const pendingStreamRequests = new Map();
  let nextInternalRequestId = 0;
  let readyState = "connecting";
  let settleReady = null;

  const readyPromise = new Promise((resolve, reject) => {
    settleReady = { resolve, reject };
  });

  socket.on("open", async () => {
    try {
      await onBeforeReadyRequest?.({
        request,
        subscribe,
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

  socket.on("message", (chunk) => {
    const message = typeof chunk === "string" ? chunk : chunk.toString("utf8");
    const parsed = safeParseJSON(message);
    if (handleInternalProtocolFrame(parsed)) {
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
    rejectPendingRequests(
      pendingUnaryRequests,
      new Error(`T3 endpoint closed before the attach flow completed: ${safeReason}`)
    );
    rejectPendingStreams(
      pendingStreamRequests,
      new Error(`T3 endpoint closed before the T3 stream completed: ${safeReason}`)
    );
    listeners.emitClose(code, safeReason);
  });

  socket.on("error", (error) => {
    if (readyState !== "ready") {
      readyState = "failed";
      settleReady?.reject(error);
      settleReady = null;
    }
    rejectPendingRequests(pendingUnaryRequests, error);
    rejectPendingStreams(pendingStreamRequests, error);
    listeners.emitError(error);
  });

  function request(method, payload = {}, { timeoutMs = DEFAULT_T3_REQUEST_TIMEOUT_MS } = {}) {
    const requestId = createRequestId(++nextInternalRequestId);
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        pendingUnaryRequests.delete(requestId);
        reject(new Error(`T3 request "${method}" timed out.`));
      }, timeoutMs);
      timeout.unref?.();

      pendingUnaryRequests.set(requestId, {
        resolve,
        reject,
        timeout,
      });

      try {
        sendProtocolRequest({
          requestId,
          method,
          payload,
        });
      } catch (error) {
        clearTimeout(timeout);
        pendingUnaryRequests.delete(requestId);
        reject(error);
      }
    });
  }

  function subscribe(method, payload = {}, {
    onEnd = null,
    onError = null,
    onValue = null,
    onValues = null,
  } = {}) {
    const requestId = createRequestId(++nextInternalRequestId);
    pendingStreamRequests.set(requestId, {
      onEnd,
      onError,
      onValue,
      onValues,
    });
    sendProtocolRequest({
      requestId,
      method,
      payload,
    });
    return () => {
      pendingStreamRequests.delete(requestId);
    };
  }

  function send(message) {
    if (readyState !== "ready") {
      return false;
    }
    if (socket.readyState === openState) {
      socket.send(message);
      return true;
    }
    return false;
  }

  function sendProtocolRequest({ requestId, method, payload }) {
    if (socket.readyState !== openState) {
      throw new Error("The T3 endpoint is not open.");
    }

    socket.send(JSON.stringify({
      _tag: "Request",
      id: requestId,
      tag: method,
      payload,
    }));
  }

  function handleInternalProtocolFrame(frame) {
    if (!frame || typeof frame !== "object") {
      return false;
    }

    const frameTag = normalizeNonEmptyString(frame._tag);
    const requestId = normalizeNonEmptyString(frame.requestId);
    if (!frameTag || !requestId) {
      return false;
    }

    if (frameTag === "Chunk" && pendingStreamRequests.has(requestId)) {
      const stream = pendingStreamRequests.get(requestId);
      const values = Array.isArray(frame.values) ? frame.values : [];
      try {
        stream.onValues?.(values);
        if (!stream.onValues && typeof stream.onValue === "function") {
          for (const value of values) {
            stream.onValue(value);
          }
        }
      } catch {
        // Listener failures should not tear down the underlying stream.
      }
      return true;
    }

    if (frameTag !== "Exit") {
      return false;
    }

    if (pendingUnaryRequests.has(requestId)) {
      const pending = pendingUnaryRequests.get(requestId);
      pendingUnaryRequests.delete(requestId);
      clearTimeout(pending.timeout);
      const outcome = parseExitFrame(frame.exit);
      if (outcome.ok) {
        pending.resolve(outcome.value);
      } else {
        pending.reject(outcome.error);
      }
      return true;
    }

    if (pendingStreamRequests.has(requestId)) {
      const stream = pendingStreamRequests.get(requestId);
      pendingStreamRequests.delete(requestId);
      const outcome = parseExitFrame(frame.exit);
      if (outcome.ok) {
        try {
          stream.onEnd?.(outcome.value);
        } catch {
          // Listener failures should stay local to the caller.
        }
      } else if (typeof stream.onError === "function") {
        try {
          stream.onError(outcome.error);
        } catch {
          // Listener failures should stay local to the caller.
        }
      } else {
        listeners.emitError(outcome.error);
      }
      return true;
    }

    return false;
  }

  return {
    mode: "websocket",
    describe() {
      return endpoint;
    },
    onClose(handler) {
      listeners.onClose = handler;
    },
    onError(handler) {
      listeners.onError = handler;
    },
    onMessage(handler) {
      listeners.onMessage = handler;
    },
    request,
    send,
    shutdown() {
      if (socket.readyState === openState || socket.readyState === connectingState) {
        socket.close();
      }
    },
    subscribe,
    whenReady() {
      return readyPromise;
    },
  };
}

function composeT3EndpointUrl(endpoint, authToken = "") {
  const normalizedEndpoint = normalizeNonEmptyString(endpoint);
  const normalizedAuthToken = normalizeNonEmptyString(authToken);
  if (!normalizedAuthToken) {
    return normalizedEndpoint;
  }

  try {
    const parsed = new URL(normalizedEndpoint);
    if (!parsed.searchParams.has("token")) {
      parsed.searchParams.set("token", normalizedAuthToken);
    }
    return parsed.toString();
  } catch {
    return normalizedEndpoint;
  }
}

function createRequestId(sequence) {
  return `androdex-t3-rpc-${sequence}`;
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

function parseExitFrame(exit) {
  if (exit && typeof exit === "object" && exit._tag === "Success") {
    return {
      ok: true,
      value: Object.prototype.hasOwnProperty.call(exit, "value") ? exit.value : null,
    };
  }

  return {
    ok: false,
    error: new Error(extractT3ProtocolErrorMessage(exit)),
  };
}

function extractT3ProtocolErrorMessage(exit) {
  const directMessage = normalizeNonEmptyString(exit?.message)
    || normalizeNonEmptyString(exit?.error?.message)
    || normalizeNonEmptyString(exit?.cause?.message);
  if (directMessage) {
    return directMessage;
  }

  const nestedMessage = extractNestedCauseMessage(exit?.cause)
    || extractNestedCauseMessage(exit?.defect)
    || extractNestedCauseMessage(exit?.failure);
  if (nestedMessage) {
    return nestedMessage;
  }

  return "T3 request failed.";
}

function extractNestedCauseMessage(value) {
  if (!value) {
    return "";
  }
  if (typeof value === "string" && value.trim()) {
    return value.trim();
  }
  if (typeof value?.message === "string" && value.message.trim()) {
    return value.message.trim();
  }

  const nestedFields = [
    value?.error,
    value?.cause,
    value?.defect,
    value?.failure,
    value?.left,
    value?.right,
  ];
  for (const nested of nestedFields) {
    const nestedMessage = extractNestedCauseMessage(nested);
    if (nestedMessage) {
      return nestedMessage;
    }
  }

  if (Array.isArray(value)) {
    for (const entry of value) {
      const nestedMessage = extractNestedCauseMessage(entry);
      if (nestedMessage) {
        return nestedMessage;
      }
    }
  }

  return "";
}

function normalizeNonEmptyString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

function rejectPendingRequests(pendingRequests, error) {
  for (const [requestId, request] of pendingRequests.entries()) {
    pendingRequests.delete(requestId);
    clearTimeout(request.timeout);
    request.reject(error);
  }
}

function rejectPendingStreams(pendingStreams, error) {
  for (const [requestId, stream] of pendingStreams.entries()) {
    pendingStreams.delete(requestId);
    try {
      stream.onError?.(error);
    } catch {
      // Listener failures should stay local to the caller.
    }
  }
}

function safeParseJSON(value) {
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

module.exports = {
  createT3EndpointTransport,
};
