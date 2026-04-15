const { randomBytes } = require("node:crypto");
const { WebSocketServer, WebSocket } = require("ws");

const DESKTOP_TUNNEL_CONNECT_PATH = "/desktop-tunnel/connect";
const DESKTOP_TUNNEL_PUBLIC_PREFIX = "/desktop";
const MAX_PROXY_BODY_BYTES = 2 * 1024 * 1024;
const DEFAULT_REQUEST_TIMEOUT_MS = 30_000;
const DEFAULT_HEARTBEAT_INTERVAL_MS = 30_000;
const HOST_UNAVAILABLE_STATUS_CODE = 502;
const HOST_UNAVAILABLE_CLOSE_CODE = 4102;
const HOP_BY_HOP_HEADERS = new Set([
  "connection",
  "content-length",
  "host",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
]);

function createDesktopTunnelService({
  requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS,
  heartbeatIntervalMs = DEFAULT_HEARTBEAT_INTERVAL_MS,
} = {}) {
  const controlWss = new WebSocketServer({ noServer: true });
  const publicWss = new WebSocketServer({ noServer: true });
  const hostsByRouteId = new Map();
  const pendingHttpRequests = new Map();
  const publicWebSockets = new Map();

  const heartbeat = setInterval(() => {
    for (const hostRecord of hostsByRouteId.values()) {
      const socket = hostRecord.socket;
      if (!socket || socket.readyState !== WebSocket.OPEN) {
        continue;
      }
      if (hostRecord.isAlive === false) {
        socket.terminate();
        continue;
      }
      hostRecord.isAlive = false;
      try {
        socket.send(JSON.stringify({ type: "ping" }));
      } catch {
        socket.terminate();
      }
    }
  }, heartbeatIntervalMs);
  heartbeat.unref?.();

  controlWss.on("connection", (socket, req) => {
    const descriptor = parseControlDescriptor(req.url);
    if (!descriptor) {
      socket.close(1008, "Missing routeId or routeToken.");
      return;
    }

    const existingRecord = hostsByRouteId.get(descriptor.routeId);
    if (existingRecord?.routeToken && existingRecord.routeToken !== descriptor.routeToken) {
      socket.close(1008, "Invalid route token.");
      return;
    }

    const hostRecord =
      existingRecord ??
      {
        routeId: descriptor.routeId,
        routeToken: descriptor.routeToken,
        socket: null,
        isAlive: true,
      };

    if (hostRecord.socket && hostRecord.socket.readyState === WebSocket.OPEN) {
      hostRecord.socket.close(4001, "Replaced by new desktop tunnel connection");
    }

    hostRecord.routeToken = descriptor.routeToken;
    hostRecord.socket = socket;
    hostRecord.isAlive = true;
    hostsByRouteId.set(descriptor.routeId, hostRecord);

    socket.on("message", (rawMessage, isBinary) => {
      handleHostMessage(hostRecord, isBinary ? rawMessage.toString("utf8") : String(rawMessage));
    });
    socket.on("close", () => {
      if (hostRecord.socket === socket) {
        hostRecord.socket = null;
      }
      hostRecord.isAlive = false;
      failPendingHttpRequestsForRoute(hostRecord.routeId);
      closePublicWebSocketsForRoute(hostRecord.routeId, 1012, "Desktop tunnel disconnected");
    });
    socket.on("error", () => undefined);
    socket.send(JSON.stringify({ type: "registered", routeId: descriptor.routeId }));
  });

  publicWss.on("connection", (socket, req) => {
    const descriptor = req.desktopTunnelDescriptor;
    if (!descriptor) {
      socket.close(HOST_UNAVAILABLE_CLOSE_CODE, "Desktop host is unavailable.");
      return;
    }

    const hostRecord = hostsByRouteId.get(descriptor.routeId);
    if (!isHostAvailable(hostRecord)) {
      socket.close(HOST_UNAVAILABLE_CLOSE_CODE, "Desktop host is unavailable.");
      return;
    }

    const sessionId = makeId("ws");
    publicWebSockets.set(sessionId, {
      routeId: descriptor.routeId,
      socket,
    });
    sendToHost(hostRecord, {
      type: "ws-open",
      sessionId,
      path: descriptor.targetPath,
    });

    socket.on("message", (payload, isBinary) => {
      const currentHostRecord = hostsByRouteId.get(descriptor.routeId);
      if (!isHostAvailable(currentHostRecord)) {
        return;
      }
      sendToHost(currentHostRecord, {
        type: "ws-frame",
        sessionId,
        ...(isBinary
          ? { bodyBase64: payload.toString("base64") }
          : { text: payload.toString("utf8") }),
      });
    });

    socket.on("close", (code, reasonBuffer) => {
      publicWebSockets.delete(sessionId);
      const currentHostRecord = hostsByRouteId.get(descriptor.routeId);
      if (!isHostAvailable(currentHostRecord)) {
        return;
      }
      sendToHost(currentHostRecord, {
        type: "ws-close",
        sessionId,
        code,
        reason: reasonBuffer.toString(),
      });
    });

    socket.on("error", () => undefined);
  });

  function getStats() {
    let activeHosts = 0;
    for (const hostRecord of hostsByRouteId.values()) {
      if (isHostAvailable(hostRecord)) {
        activeHosts += 1;
      }
    }
    return {
      enabled: true,
      knownRoutes: hostsByRouteId.size,
      activeHosts,
      activePublicSockets: publicWebSockets.size,
      pendingHttpRequests: pendingHttpRequests.size,
    };
  }

  function maybeHandleHttpRequest(req, res) {
    const descriptor = parsePublicDescriptor(req.url);
    if (!descriptor) {
      return false;
    }

    const hostRecord = hostsByRouteId.get(descriptor.routeId);
    if (!isHostAvailable(hostRecord)) {
      writeProxyError(res, HOST_UNAVAILABLE_STATUS_CODE, "Desktop host is unavailable.");
      return true;
    }

    readRequestBody(req, MAX_PROXY_BODY_BYTES)
      .then((body) => {
        const requestId = makeId("http");
        const timeout = setTimeout(() => {
          pendingHttpRequests.delete(requestId);
          writeProxyError(res, 504, "Desktop tunnel request timed out.");
        }, requestTimeoutMs);
        timeout.unref?.();

        pendingHttpRequests.set(requestId, {
          routeId: descriptor.routeId,
          res,
          timeout,
        });

        sendToHost(hostRecord, {
          type: "http-request",
          requestId,
          method: req.method || "GET",
          path: descriptor.targetPath,
          headers: normalizeHeaders(req.headers),
          ...(body.length > 0 ? { bodyBase64: body.toString("base64") } : {}),
        });
      })
      .catch((error) => {
        writeProxyError(res, error.statusCode || 400, error.message || "Invalid proxy request.");
      });

    return true;
  }

  function maybeHandleUpgrade(req, socket, head) {
    if (safePathname(req.url) === DESKTOP_TUNNEL_CONNECT_PATH) {
      controlWss.handleUpgrade(req, socket, head, (ws) => {
        controlWss.emit("connection", ws, req);
      });
      return true;
    }

    const descriptor = parsePublicDescriptor(req.url);
    if (!descriptor) {
      return false;
    }

    req.desktopTunnelDescriptor = descriptor;
    publicWss.handleUpgrade(req, socket, head, (ws) => {
      publicWss.emit("connection", ws, req);
    });
    return true;
  }

  function close() {
    clearInterval(heartbeat);
    for (const pending of pendingHttpRequests.values()) {
      clearTimeout(pending.timeout);
      writeProxyError(pending.res, HOST_UNAVAILABLE_STATUS_CODE, "Desktop tunnel stopped.");
    }
    pendingHttpRequests.clear();

    for (const publicSocketRecord of publicWebSockets.values()) {
      try {
        publicSocketRecord.socket.close(1012, "Desktop tunnel stopped");
      } catch {
        // Best effort only.
      }
    }
    publicWebSockets.clear();

    for (const hostRecord of hostsByRouteId.values()) {
      if (hostRecord.socket && hostRecord.socket.readyState === WebSocket.OPEN) {
        hostRecord.socket.close(1001, "Desktop tunnel stopped");
      }
    }
    controlWss.close();
    publicWss.close();
  }

  function handleHostMessage(hostRecord, rawMessage) {
    let parsed;
    try {
      parsed = JSON.parse(rawMessage);
    } catch {
      return;
    }

    if (parsed.type === "pong") {
      hostRecord.isAlive = true;
      return;
    }

    if (parsed.type === "http-response") {
      const pending = pendingHttpRequests.get(parsed.requestId);
      if (!pending) {
        return;
      }
      pendingHttpRequests.delete(parsed.requestId);
      clearTimeout(pending.timeout);
      writeProxyResponse(pending.res, parsed);
      return;
    }

    if (parsed.type === "ws-frame") {
      const publicSocketRecord = publicWebSockets.get(parsed.sessionId);
      if (!publicSocketRecord) {
        return;
      }
      if (parsed.text !== undefined) {
        publicSocketRecord.socket.send(parsed.text);
      } else if (typeof parsed.bodyBase64 === "string" && parsed.bodyBase64.length > 0) {
        publicSocketRecord.socket.send(Buffer.from(parsed.bodyBase64, "base64"));
      }
      return;
    }

    if (parsed.type === "ws-close") {
      const publicSocketRecord = publicWebSockets.get(parsed.sessionId);
      if (!publicSocketRecord) {
        return;
      }
      publicWebSockets.delete(parsed.sessionId);
      publicSocketRecord.socket.close(parsed.code || 1000, parsed.reason || "");
      return;
    }
  }

  function failPendingHttpRequestsForRoute(routeId) {
    for (const [requestId, pending] of pendingHttpRequests) {
      if (pending.routeId !== routeId) {
        continue;
      }
      pendingHttpRequests.delete(requestId);
      clearTimeout(pending.timeout);
      writeProxyError(pending.res, HOST_UNAVAILABLE_STATUS_CODE, "Desktop host is unavailable.");
    }
  }

  function closePublicWebSocketsForRoute(routeId, code, reason) {
    for (const [sessionId, publicSocketRecord] of publicWebSockets) {
      if (publicSocketRecord.routeId !== routeId) {
        continue;
      }
      publicWebSockets.delete(sessionId);
      try {
        publicSocketRecord.socket.close(code, reason);
      } catch {
        // Best effort only.
      }
    }
  }

  return {
    maybeHandleHttpRequest,
    maybeHandleUpgrade,
    getStats,
    close,
  };
}

function isHostAvailable(hostRecord) {
  return Boolean(hostRecord?.socket && hostRecord.socket.readyState === WebSocket.OPEN);
}

function sendToHost(hostRecord, payload) {
  if (!isHostAvailable(hostRecord)) {
    return;
  }
  hostRecord.socket.send(JSON.stringify(payload));
}

function parseControlDescriptor(rawUrl) {
  try {
    const url = new URL(rawUrl || "/", "http://localhost");
    const routeId = normalizeRouteId(url.searchParams.get("routeId"));
    const routeToken = normalizeRouteToken(url.searchParams.get("routeToken"));
    if (!routeId || !routeToken) {
      return null;
    }
    return { routeId, routeToken };
  } catch {
    return null;
  }
}

function parsePublicDescriptor(rawUrl) {
  try {
    const url = new URL(rawUrl || "/", "http://localhost");
    const segments = url.pathname.split("/").filter(Boolean);
    if (segments[0] !== DESKTOP_TUNNEL_PUBLIC_PREFIX.slice(1)) {
      return null;
    }
    const routeId = normalizeRouteId(segments[1]);
    if (!routeId) {
      return null;
    }
    const remainingSegments = segments.slice(2);
    const targetPath = `/${remainingSegments.join("/")}${url.search || ""}`;
    return {
      routeId,
      targetPath: targetPath === "/?" ? "/" : targetPath,
    };
  } catch {
    return null;
  }
}

function normalizeRouteId(value) {
  if (typeof value !== "string") {
    return null;
  }
  const normalized = value.trim();
  return normalized.length > 0 ? normalized : null;
}

function normalizeRouteToken(value) {
  if (typeof value !== "string") {
    return null;
  }
  const normalized = value.trim();
  return normalized.length > 0 ? normalized : null;
}

function normalizeHeaders(headers) {
  const normalized = {};
  for (const [name, value] of Object.entries(headers)) {
    if (HOP_BY_HOP_HEADERS.has(name.toLowerCase())) {
      continue;
    }
    if (Array.isArray(value)) {
      normalized[name] = value.join(", ");
      continue;
    }
    if (typeof value === "string") {
      normalized[name] = value;
    }
  }
  return normalized;
}

function readRequestBody(req, maxBytes) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let totalBytes = 0;

    req.on("data", (chunk) => {
      totalBytes += chunk.length;
      if (totalBytes > maxBytes) {
        reject(createHttpError(413, "Request body too large."));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });

    req.on("end", () => {
      resolve(Buffer.concat(chunks));
    });

    req.on("error", () => {
      reject(createHttpError(400, "Failed to read proxy request body."));
    });
  });
}

function writeProxyResponse(res, response) {
  if (res.writableEnded) {
    return;
  }
  const headers = {};
  for (const [name, value] of Object.entries(response.headers || {})) {
    if (HOP_BY_HOP_HEADERS.has(name.toLowerCase())) {
      continue;
    }
    headers[name] = value;
  }
  res.writeHead(response.status || 200, headers);
  if (typeof response.bodyBase64 === "string" && response.bodyBase64.length > 0) {
    res.end(Buffer.from(response.bodyBase64, "base64"));
    return;
  }
  res.end();
}

function writeProxyError(res, statusCode, message) {
  if (res.writableEnded) {
    return;
  }
  res.writeHead(statusCode, {
    "content-type": "application/json; charset=utf-8",
  });
  res.end(JSON.stringify({ ok: false, error: message }));
}

function createHttpError(statusCode, message) {
  const error = new Error(message);
  error.statusCode = statusCode;
  return error;
}

function makeId(prefix) {
  return `${prefix}_${randomBytes(8).toString("hex")}`;
}

function safePathname(rawUrl) {
  try {
    return new URL(rawUrl || "/", "http://localhost").pathname;
  } catch {
    return "/";
  }
}

module.exports = {
  createDesktopTunnelService,
  DESKTOP_TUNNEL_CONNECT_PATH,
  DESKTOP_TUNNEL_PUBLIC_PREFIX,
};
