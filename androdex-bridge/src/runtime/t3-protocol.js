// FILE: runtime/t3-protocol.js
// Purpose: Speaks T3's real Effect RPC websocket protocol through Effect's socket/runtime layers.
// Layer: CLI helper
// Exports: createT3EndpointTransport
// Depends on: ws, effect, @effect/platform-node

const WebSocket = require("ws");

const DEFAULT_T3_REQUEST_TIMEOUT_MS = 5_000;

let effectRuntimeSupportPromise = null;

function createT3EndpointTransport({
  endpoint,
  authToken = "",
  WebSocketImpl = WebSocket,
  onBeforeReadyRequest = null,
} = {}) {
  const liveEndpoint = composeT3EndpointUrl(endpoint, authToken);
  const listeners = createListenerBag();
  const openState = WebSocketImpl.OPEN ?? WebSocket.OPEN ?? 1;
  const connectingState = WebSocketImpl.CONNECTING ?? WebSocket.CONNECTING ?? 0;
  const pendingUnaryRequests = new Map();
  const pendingStreamRequests = new Map();
  let nextInternalRequestId = 0n;
  let readyState = "connecting";
  let runtime = null;
  let protocol = null;
  let activeSocket = null;
  let shuttingDown = false;
  let settleReady = null;
  let settleProtocolReady = null;
  let settleSocketOpen = null;

  const readyPromise = new Promise((resolve, reject) => {
    settleReady = { resolve, reject };
  });
  const protocolReadyPromise = new Promise((resolve, reject) => {
    settleProtocolReady = { resolve, reject };
  });
  const socketOpenPromise = new Promise((resolve) => {
    settleSocketOpen = resolve;
  });

  const initializePromise = initialize();

  async function initialize() {
    try {
      const {
        Effect,
        Layer,
        ManagedRuntime,
        Schedule,
        RpcClient,
        RpcSerialization,
        Socket,
      } = await loadEffectRuntimeSupport();

      const socketConstructorLayer = Layer.succeed(
        Socket.WebSocketConstructor,
        (socketUrl, protocols) => {
          const socket = ensureCompatibleWebSocket(new WebSocketImpl(socketUrl, protocols));
          activeSocket = socket;
          bindSocketLifecycle(socket);
          return socket;
        }
      );

      const protocolLayer = Layer.effect(
        RpcClient.Protocol,
        RpcClient.makeProtocolSocket({
          retryPolicy: Schedule.recurs(0),
        })
      ).pipe(
        Layer.provide(Layer.effect(Socket.Socket, Socket.makeWebSocket(liveEndpoint))),
        Layer.provide(RpcSerialization.layerJson),
        Layer.provide(socketConstructorLayer)
      );

      runtime = ManagedRuntime.make(protocolLayer);
      protocol = await runtime.runPromise(Effect.gen(function* () {
        return yield* RpcClient.Protocol;
      }));
      settleProtocolReady?.resolve(protocol);
      settleProtocolReady = null;

      runtime.runFork(protocol.run((frame) => Effect.sync(() => {
        handleProtocolFrame(frame);
      })));

      await socketOpenPromise;
      await onBeforeReadyRequest?.({
        request,
        subscribe,
      });

      readyState = "ready";
      settleReady?.resolve();
      settleReady = null;
    } catch (error) {
      failInitialization(error);
      await safeDisposeRuntime();
    }
  }

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

      Promise.resolve()
        .then(() => protocolReadyPromise)
        .then(() =>
          sendProtocolMessage({
            _tag: "Request",
            id: requestId,
            tag: method,
            payload,
            headers: [],
          })
        )
        .catch((error) => {
          clearTimeout(timeout);
          pendingUnaryRequests.delete(requestId);
          reject(error);
        });
    });
  }

  function subscribe(method, payload = {}, {
    onEnd = null,
    onError = null,
    onValue = null,
    onValues = null,
  } = {}) {
    const requestId = createRequestId(++nextInternalRequestId);
    const streamState = {
      onEnd,
      onError,
      onValue,
      onValues,
      started: false,
      cancelled: false,
    };
    pendingStreamRequests.set(requestId, streamState);

    Promise.resolve()
      .then(() => protocolReadyPromise)
      .then(() =>
        sendProtocolMessage({
          _tag: "Request",
          id: requestId,
          tag: method,
          payload,
          headers: [],
        })
      )
      .then(() => {
        streamState.started = true;
        if (streamState.cancelled) {
          return sendInterrupt(requestId).catch(() => undefined);
        }
        return undefined;
      })
      .catch((error) => {
        if (pendingStreamRequests.get(requestId) === streamState) {
          pendingStreamRequests.delete(requestId);
        }
        if (!streamState.cancelled) {
          try {
            streamState.onError?.(error);
          } catch {
            // Listener failures should stay local to the caller.
          }
        }
      });

    return () => {
      streamState.cancelled = true;
      pendingStreamRequests.delete(requestId);
      if (streamState.started) {
        void sendInterrupt(requestId).catch(() => undefined);
      }
    };
  }

  function send(message) {
    if (readyState !== "ready") {
      return false;
    }
    if (activeSocket && activeSocket.readyState === openState) {
      activeSocket.send(message);
      return true;
    }
    return false;
  }

  async function sendProtocolMessage(message) {
    const currentProtocol = await protocolReadyPromise;
    if (!runtime || !currentProtocol) {
      throw new Error("The T3 endpoint is not open.");
    }
    return runtime.runPromise(currentProtocol.send(message));
  }

  function sendInterrupt(requestId) {
    return sendProtocolMessage({
      _tag: "Interrupt",
      requestId,
    });
  }

  function acknowledgeChunk(requestId) {
    if (!protocol || !runtime) {
      return;
    }
    void runtime.runPromise(protocol.send({
      _tag: "Ack",
      requestId,
    })).catch(() => undefined);
  }

  function handleProtocolFrame(frame) {
    if (!frame || typeof frame !== "object") {
      return;
    }

    const frameTag = normalizeNonEmptyString(frame._tag);
    if (!frameTag) {
      return;
    }

    if (frameTag === "Pong" || frameTag === "ClientEnd") {
      return;
    }

    if (frameTag === "ClientProtocolError") {
      const error = normalizeProtocolError(frame.error);
      failInitialization(error);
      rejectPendingRequests(pendingUnaryRequests, error);
      rejectPendingStreams(pendingStreamRequests, error);
      listeners.emitError(error);
      return;
    }

    if (frameTag === "Defect") {
      const error = new Error(extractT3ProtocolErrorMessage(frame.defect));
      failInitialization(error);
      rejectPendingRequests(pendingUnaryRequests, error);
      rejectPendingStreams(pendingStreamRequests, error);
      listeners.emitError(error);
      return;
    }

    const requestId = normalizeNonEmptyString(frame.requestId);
    if (!requestId) {
      return;
    }

    if (frameTag === "Chunk" && pendingStreamRequests.has(requestId)) {
      const stream = pendingStreamRequests.get(requestId);
      const values = Array.isArray(frame.values) ? frame.values : [];
      acknowledgeChunk(requestId);
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
      return;
    }

    if (frameTag !== "Exit") {
      return;
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
      return;
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
    }
  }

  function bindSocketLifecycle(socket) {
    addSocketListener(socket, "open", () => {
      settleSocketOpen?.();
      settleSocketOpen = null;
    });

    addSocketListener(socket, "message", (event) => {
      const message = coerceSocketMessage(event);
      if (!message.trim()) {
        return;
      }
      const parsed = safeParseJSON(message);
      if (isProtocolFrame(parsed)) {
        return;
      }
      listeners.emitMessage(message);
    });

    addSocketListener(socket, "close", (event) => {
      const code = normalizeSocketCloseCode(event);
      const reason = normalizeSocketCloseReason(event);
      if (readyState !== "ready") {
        failInitialization(new Error(`T3 endpoint closed during attach: ${reason || code || "no reason"}`));
      }
      rejectPendingRequests(
        pendingUnaryRequests,
        new Error(`T3 endpoint closed before the attach flow completed: ${reason || code || "no reason"}`)
      );
      rejectPendingStreams(
        pendingStreamRequests,
        new Error(`T3 endpoint closed before the T3 stream completed: ${reason || code || "no reason"}`)
      );
      listeners.emitClose(code, reason || "no reason");
      void safeDisposeRuntime();
    });

    addSocketListener(socket, "error", (event) => {
      const error = normalizeSocketError(event);
      failInitialization(error);
      rejectPendingRequests(pendingUnaryRequests, error);
      rejectPendingStreams(pendingStreamRequests, error);
      listeners.emitError(error);
    });
  }

  function failInitialization(error) {
    const normalizedError = normalizeProtocolError(error);
    if (readyState === "ready" || readyState === "failed") {
      return;
    }
    readyState = "failed";
    settleReady?.reject(normalizedError);
    settleReady = null;
    settleProtocolReady?.reject(normalizedError);
    settleProtocolReady = null;
  }

  async function safeDisposeRuntime() {
    if (!runtime) {
      return;
    }
    const currentRuntime = runtime;
    runtime = null;
    protocol = null;
    try {
      await currentRuntime.dispose();
    } catch {
      // Best effort only.
    }
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
      shuttingDown = true;
      if (activeSocket && (
        activeSocket.readyState === openState
        || activeSocket.readyState === connectingState
      )) {
        activeSocket.close();
      }
      void safeDisposeRuntime();
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
  return sequence.toString();
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
    || normalizeNonEmptyString(exit?.cause?.message)
    || normalizeNonEmptyString(exit?.reason?.message);
  if (directMessage) {
    return directMessage;
  }

  const nestedMessage = extractNestedCauseMessage(exit?.cause)
    || extractNestedCauseMessage(exit?.defect)
    || extractNestedCauseMessage(exit?.failure)
    || extractNestedCauseMessage(exit?.reason);
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
    value?.reason,
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

function isProtocolFrame(frame) {
  const tag = normalizeNonEmptyString(frame?._tag);
  return [
    "Request",
    "Ack",
    "Interrupt",
    "Ping",
    "Pong",
    "Chunk",
    "Exit",
    "Defect",
    "ClientProtocolError",
    "ClientEnd",
  ].includes(tag);
}

function normalizeProtocolError(error) {
  if (error instanceof Error) {
    return error;
  }
  return new Error(extractT3ProtocolErrorMessage(error));
}

function addSocketListener(socket, eventName, handler) {
  if (!socket) {
    return;
  }
  if (typeof socket.__androdexAddEventListener === "function") {
    socket.__androdexAddEventListener(eventName, handler);
    return;
  }
  if (typeof socket.addEventListener === "function") {
    socket.addEventListener(eventName, handler);
    return;
  }
  if (typeof socket.on === "function") {
    socket.on(eventName, (...args) => handler(buildLegacySocketEvent(eventName, args)));
  }
}

function buildLegacySocketEvent(eventName, args) {
  if (eventName === "message") {
    return { data: args[0] };
  }
  if (eventName === "close") {
    return {
      code: typeof args[0] === "number" ? args[0] : 1000,
      reason: normalizeCloseReasonValue(args[1]),
    };
  }
  if (eventName === "error") {
    return args[0] instanceof Error ? args[0] : { error: args[0] };
  }
  return args[0] ?? {};
}

function coerceSocketMessage(event) {
  const rawData = Object.prototype.hasOwnProperty.call(event || {}, "data") ? event.data : event;
  if (typeof rawData === "string") {
    return rawData;
  }
  if (Buffer.isBuffer(rawData)) {
    return rawData.toString("utf8");
  }
  if (rawData instanceof Uint8Array) {
    return Buffer.from(rawData).toString("utf8");
  }
  return "";
}

function normalizeSocketCloseCode(event) {
  return typeof event?.code === "number" ? event.code : 1000;
}

function normalizeSocketCloseReason(event) {
  return normalizeCloseReasonValue(event?.reason);
}

function normalizeCloseReasonValue(value) {
  if (typeof value === "string") {
    return value;
  }
  if (Buffer.isBuffer(value)) {
    return value.toString("utf8");
  }
  if (value instanceof Uint8Array) {
    return Buffer.from(value).toString("utf8");
  }
  return "";
}

function normalizeSocketError(event) {
  if (event instanceof Error) {
    return event;
  }
  if (event?.error instanceof Error) {
    return event.error;
  }
  if (typeof event?.message === "string" && event.message.trim()) {
    return new Error(event.message.trim());
  }
  return new Error("T3 websocket transport error.");
}

function ensureCompatibleWebSocket(socket) {
  if (!socket) {
    return socket;
  }
  if (typeof socket.addEventListener !== "function") {
    if (socket.handlers instanceof Map) {
      const handlerEntriesByEvent = new Map();
      socket.__androdexAddEventListener = function addEventListener(eventName, handler, options = {}) {
        const listeners = handlerEntriesByEvent.get(eventName) ?? [];
        listeners.push({
          handler,
          once: options?.once === true,
        });
        handlerEntriesByEvent.set(eventName, listeners);
        socket.handlers.set(eventName, (...args) => {
          const currentListeners = [...(handlerEntriesByEvent.get(eventName) ?? [])];
          for (const entry of currentListeners) {
            entry.handler(buildLegacySocketEvent(eventName, args));
            if (entry.once) {
              socket.removeEventListener?.(eventName, entry.handler);
            }
          }
        });
      };
      socket.removeEventListener = function removeEventListener(eventName, handler) {
        const listeners = handlerEntriesByEvent.get(eventName) ?? [];
        handlerEntriesByEvent.set(
          eventName,
          listeners.filter((entry) => entry.handler !== handler)
        );
      };
    } else {
      socket.__androdexAddEventListener = function addEventListener(eventName, handler) {
        if (typeof socket.on === "function") {
          socket.on(eventName, (...args) => handler(buildLegacySocketEvent(eventName, args)));
        }
      };
    }
    socket.addEventListener = socket.__androdexAddEventListener;
  }
  return socket;
}

async function loadEffectRuntimeSupport() {
  if (!effectRuntimeSupportPromise) {
    effectRuntimeSupportPromise = Promise.all([
      import("effect"),
      import("effect/unstable/rpc"),
      import("effect/unstable/socket/Socket"),
    ]).then(([effectModule, rpcModule, socketModule]) => ({
      Effect: effectModule.Effect,
      Layer: effectModule.Layer,
      ManagedRuntime: effectModule.ManagedRuntime,
      Schedule: effectModule.Schedule,
      RpcClient: rpcModule.RpcClient,
      RpcSerialization: rpcModule.RpcSerialization,
      Socket: socketModule,
    }));
  }
  return effectRuntimeSupportPromise;
}

module.exports = {
  createT3EndpointTransport,
};
