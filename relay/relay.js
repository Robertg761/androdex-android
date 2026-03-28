// FILE: relay.js
// Purpose: Thin WebSocket relay used by the durable Androdex host-presence flow.
// Layer: Standalone server module
// Exports: setupRelay, getRelayStats

const { WebSocket } = require("ws");

const CLEANUP_DELAY_MS = 60_000;
const HEARTBEAT_INTERVAL_MS = 30_000;
const CLOSE_CODE_SESSION_UNAVAILABLE = 4002;
const CLOSE_CODE_MOBILE_REPLACED = 4003;

// In-memory host registry for one live host daemon and one live mobile client per host id.
const sessions = new Map();

// Attaches relay behavior to a ws WebSocketServer instance.
function setupRelay(wss) {
  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (ws._relayAlive === false) {
        ws.terminate();
        continue;
      }
      ws._relayAlive = false;
      ws.ping();
    }
  }, HEARTBEAT_INTERVAL_MS);

  wss.on("close", () => clearInterval(heartbeat));

  wss.on("connection", (ws, req) => {
    const urlPath = normalizeRelayPath(req.url || "");
    const match = urlPath.match(/^\/relay\/([^/?]+)/);
    const sessionId = match?.[1];
    const role = normalizeRole(req.headers["x-role"]);

    if (!sessionId || (role !== "mac" && role !== "mobile")) {
      ws.close(4000, "Missing sessionId or invalid x-role header");
      return;
    }

    ws._relayAlive = true;
    ws.on("pong", () => {
      ws._relayAlive = true;
    });

    // Only the host daemon is allowed to create a fresh host room.
    if (role === "mobile" && !sessions.has(sessionId)) {
      ws.close(CLOSE_CODE_SESSION_UNAVAILABLE, "Host is not available");
      return;
    }

    if (!sessions.has(sessionId)) {
      sessions.set(sessionId, {
        mac: null,
        clients: new Set(),
        cleanupTimer: null,
      });
    }

    const session = sessions.get(sessionId);

    if (role === "mobile" && session.mac?.readyState !== WebSocket.OPEN) {
      ws.close(CLOSE_CODE_SESSION_UNAVAILABLE, "Host is not available");
      return;
    }

    if (session.cleanupTimer) {
      clearTimeout(session.cleanupTimer);
      session.cleanupTimer = null;
    }

    if (role === "mac") {
      if (session.mac && session.mac.readyState === WebSocket.OPEN) {
        session.mac.close(4001, "Replaced by new Mac connection");
      }
      session.mac = ws;
      console.log(`[relay] Host connected -> ${sessionId}`);
    } else {
      // Keep one live mobile RPC client per host to avoid competing sockets.
      for (const existingClient of session.clients) {
        if (existingClient === ws) {
          continue;
        }
        if (
          existingClient.readyState === WebSocket.OPEN
          || existingClient.readyState === WebSocket.CONNECTING
        ) {
          existingClient.close(CLOSE_CODE_MOBILE_REPLACED, "Replaced by newer mobile connection");
        }
        session.clients.delete(existingClient);
      }

      session.clients.add(ws);
      console.log(
        `[relay] Mobile connected -> ${sessionId} (${session.clients.size} client(s))`
      );
    }

    ws.on("message", (data) => {
      const msg = typeof data === "string" ? data : data.toString("utf-8");
      console.log(
        `[relay] forwarded ${role} -> ${sessionId} (${Buffer.byteLength(msg, "utf8")} bytes)`
      );

      if (role === "mac") {
        for (const client of session.clients) {
          if (client.readyState === WebSocket.OPEN) {
            client.send(msg);
          }
        }
      } else if (session.mac?.readyState === WebSocket.OPEN) {
        session.mac.send(msg);
      }
    });

    ws.on("close", () => {
      if (role === "mac") {
        if (session.mac === ws) {
          session.mac = null;
          console.log(`[relay] Host disconnected -> ${sessionId}`);
          for (const client of session.clients) {
            if (client.readyState === WebSocket.OPEN || client.readyState === WebSocket.CONNECTING) {
              client.close(CLOSE_CODE_SESSION_UNAVAILABLE, "Host disconnected");
            }
          }
        }
      } else {
        session.clients.delete(ws);
        console.log(
          `[relay] Mobile disconnected -> ${sessionId} (${session.clients.size} remaining)`
        );
      }
      scheduleCleanup(sessionId);
    });

    ws.on("error", (err) => {
      console.error(
        `[relay] WebSocket error (${role}, session ${sessionId}):`,
        err.message
      );
    });
  });
}

function normalizeRelayPath(rawUrl) {
  if (!rawUrl) {
    return "";
  }

  if (rawUrl.startsWith("/")) {
    return rawUrl;
  }

  try {
    return new URL(rawUrl).pathname;
  } catch {
    return rawUrl;
  }
}

function normalizeRole(rawRole) {
  if (rawRole === "mac") {
    return "mac";
  }
  if (rawRole === "android" || rawRole === "iphone") {
    return "mobile";
  }
  return rawRole;
}

function scheduleCleanup(sessionId) {
  const session = sessions.get(sessionId);
  if (!session) {
    return;
  }
  if (session.mac || session.clients.size > 0 || session.cleanupTimer) {
    return;
  }

  session.cleanupTimer = setTimeout(() => {
    const activeSession = sessions.get(sessionId);
    if (activeSession && !activeSession.mac && activeSession.clients.size === 0) {
      sessions.delete(sessionId);
      console.log(`[relay] Session ${sessionId} cleaned up`);
    }
  }, CLEANUP_DELAY_MS);
}

// Exposes lightweight runtime stats for health/status endpoints.
function getRelayStats() {
  let totalClients = 0;
  let sessionsWithMac = 0;

  for (const session of sessions.values()) {
    totalClients += session.clients.size;
    if (session.mac) {
      sessionsWithMac += 1;
    }
  }

  return {
    activeSessions: sessions.size,
    sessionsWithMac,
    totalClients,
  };
}

function hasLiveSession(sessionId) {
  const normalizedSessionId = typeof sessionId === "string" && sessionId.trim()
    ? sessionId.trim()
    : "";
  if (!normalizedSessionId) {
    return false;
  }

  const session = sessions.get(normalizedSessionId);
  return Boolean(session?.mac && session.mac.readyState === WebSocket.OPEN);
}

module.exports = {
  hasLiveSession,
  setupRelay,
  getRelayStats,
};
