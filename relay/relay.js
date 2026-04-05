// FILE: relay.js
// Purpose: Thin WebSocket relay used by the durable Androdex host-presence flow.
// Layer: Standalone server module
// Exports: setupRelay, getRelayStats, hasActiveMacSession, hasAuthenticatedMacSession, resolveTrustedMacSession

const { createHash, createPublicKey, verify } = require("crypto");
const { WebSocket } = require("ws");

const CLEANUP_DELAY_MS = 60_000;
const HEARTBEAT_INTERVAL_MS = 30_000;
const CLOSE_CODE_SESSION_UNAVAILABLE = 4002;
const CLOSE_CODE_MOBILE_REPLACED = 4003;
const CLOSE_CODE_MAC_ABSENCE_BUFFER_FULL = 4004;
const MAC_ABSENCE_GRACE_MS = 15_000;
const TRUSTED_SESSION_RESOLVE_TAG = "androdex-trusted-session-resolve-v1";
const TRUSTED_SESSION_RESOLVE_SKEW_MS = 90_000;
const STABLE_RELAY_HOST_PREFIX = "mac.";

// In-memory host registry for one live host daemon and one live mobile client per host id.
const sessions = new Map();
const liveSessionsByMacDeviceId = new Map();
const usedResolveNonces = new Map();

// Attaches relay behavior to a ws WebSocketServer instance.
function setupRelay(
  wss,
  {
    setTimeoutFn = setTimeout,
    clearTimeoutFn = clearTimeout,
    macAbsenceGraceMs = MAC_ABSENCE_GRACE_MS,
  } = {}
) {
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
  heartbeat.unref?.();

  wss.on("close", () => clearInterval(heartbeat));

  wss.on("connection", (ws, req) => {
    const urlPath = normalizeRelayPath(req.url || "");
    const match = urlPath.match(/^\/relay\/([^/?]+)/);
    const requestedSessionId = normalizeNonEmptyString(match?.[1]);
    const role = normalizeRole(req.headers["x-role"]);
    const sessionId = role === "mobile"
      ? resolveMobileConnectionSessionId(requestedSessionId)
      : requestedSessionId;

    if (!sessionId || (role !== "mac" && role !== "mobile")) {
      ws.close(4000, "Missing sessionId or invalid x-role header");
      return;
    }

    ws._relayAlive = true;
    ws.on("pong", () => {
      ws._relayAlive = true;
    });

    // Only the host daemon is allowed to create a fresh relay room.
    if (role === "mobile" && !sessions.has(sessionId)) {
      ws.close(CLOSE_CODE_SESSION_UNAVAILABLE, "Host is not available");
      return;
    }

    if (!sessions.has(sessionId)) {
      sessions.set(sessionId, {
        mac: null,
        macRegistration: null,
        clients: new Set(),
        cleanupTimer: null,
        macAbsenceTimer: null,
        notificationSecret: null,
      });
    }

    const session = sessions.get(sessionId);

    if (role === "mobile" && !canAcceptMobileConnection(session)) {
      ws.close(CLOSE_CODE_SESSION_UNAVAILABLE, "Host is not available");
      return;
    }

    if (session.cleanupTimer) {
      clearTimeoutFn(session.cleanupTimer);
      session.cleanupTimer = null;
    }

    if (role === "mac") {
      clearMacAbsenceTimer(session, { clearTimeoutFn });
      session.notificationSecret = readHeaderString(req.headers["x-notification-secret"]);
      session.macRegistration = readMacRegistrationHeaders(req.headers, sessionId);
      if (session.mac && session.mac.readyState === WebSocket.OPEN) {
        session.mac.close(4001, "Replaced by new Mac connection");
      }
      session.mac = ws;
      registerLiveMacSession(session.macRegistration);
      console.log(`[relay] Host connected -> ${relaySessionLogLabel(sessionId)}`);
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
          existingClient.close(
            CLOSE_CODE_MOBILE_REPLACED,
            "Replaced by newer mobile connection"
          );
        }
        session.clients.delete(existingClient);
      }

      session.clients.add(ws);
      console.log(
        `[relay] Mobile connected -> ${relaySessionLogLabel(sessionId)} `
        + `(${session.clients.size} client(s))`
      );
    }

    ws.on("message", (data) => {
      const msg = typeof data === "string" ? data : data.toString("utf-8");
      if (role === "mac" && applyMacRegistrationMessage(session, sessionId, msg)) {
        return;
      }

      if (role === "mac") {
        for (const client of session.clients) {
          if (client.readyState === WebSocket.OPEN) {
            client.send(msg);
          }
        }
      } else if (session.mac?.readyState === WebSocket.OPEN) {
        session.mac.send(msg);
      } else {
        // The relay cannot prove a buffered request really reached the bridge after
        // a reconnect, so fail fast with an explicit retry-required close instead
        // of silently dropping queued client work during a later flush.
        ws.close(CLOSE_CODE_MAC_ABSENCE_BUFFER_FULL, "Host temporarily unavailable");
      }
    });

    ws.on("close", () => {
      if (role === "mac") {
        if (session.mac === ws) {
          session.mac = null;
          unregisterLiveMacSession(session.macRegistration, sessionId);
          console.log(`[relay] Host disconnected -> ${relaySessionLogLabel(sessionId)}`);
          if (session.clients.size > 0) {
            scheduleMacAbsenceTimeout(sessionId, {
              macAbsenceGraceMs,
              setTimeoutFn,
              clearTimeoutFn,
            });
          } else {
            scheduleCleanup(sessionId, { setTimeoutFn });
          }
        }
      } else {
        session.clients.delete(ws);
        console.log(
          `[relay] Mobile disconnected -> ${relaySessionLogLabel(sessionId)} `
          + `(${session.clients.size} remaining)`
        );
      }
      scheduleCleanup(sessionId, { setTimeoutFn });
    });

    ws.on("error", (err) => {
      console.error(
        `[relay] WebSocket error (${role}, ${relaySessionLogLabel(sessionId)}):`,
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
  const normalizedRole = readHeaderString(rawRole);
  if (normalizedRole === "mac") {
    return "mac";
  }
  if (
    normalizedRole === "android"
    || normalizedRole === "iphone"
    || normalizedRole === "mobile"
  ) {
    return "mobile";
  }
  return normalizedRole;
}

function scheduleCleanup(sessionId, { setTimeoutFn = setTimeout } = {}) {
  const session = sessions.get(sessionId);
  if (!session) {
    return;
  }
  if (session.mac || session.clients.size > 0 || session.cleanupTimer || session.macAbsenceTimer) {
    return;
  }

  session.cleanupTimer = setTimeoutFn(() => {
    const activeSession = sessions.get(sessionId);
    if (
      activeSession
      && !activeSession.mac
      && activeSession.clients.size === 0
      && !activeSession.macAbsenceTimer
    ) {
      unregisterLiveMacSession(activeSession.macRegistration, sessionId);
      sessions.delete(sessionId);
      console.log(`[relay] ${relaySessionLogLabel(sessionId)} cleaned up`);
    }
  }, CLEANUP_DELAY_MS);
  session.cleanupTimer.unref?.();
}

function scheduleMacAbsenceTimeout(
  sessionId,
  {
    macAbsenceGraceMs,
    setTimeoutFn = setTimeout,
    clearTimeoutFn = clearTimeout,
  } = {}
) {
  const session = sessions.get(sessionId);
  if (!session || session.mac || session.macAbsenceTimer) {
    return;
  }

  session.macAbsenceTimer = setTimeoutFn(() => {
    const activeSession = sessions.get(sessionId);
    if (!activeSession) {
      return;
    }

    activeSession.macAbsenceTimer = null;
    activeSession.notificationSecret = null;
    unregisterLiveMacSession(activeSession.macRegistration, sessionId);
    closeSessionClients(activeSession, CLOSE_CODE_SESSION_UNAVAILABLE, "Host disconnected");
    scheduleCleanup(sessionId, { setTimeoutFn });
  }, macAbsenceGraceMs);
  session.macAbsenceTimer.unref?.();

  if (session.cleanupTimer) {
    clearTimeoutFn(session.cleanupTimer);
    session.cleanupTimer = null;
  }
}

function clearMacAbsenceTimer(session, { clearTimeoutFn = clearTimeout } = {}) {
  if (!session?.macAbsenceTimer) {
    return;
  }

  clearTimeoutFn(session.macAbsenceTimer);
  session.macAbsenceTimer = null;
}

function canAcceptMobileConnection(session) {
  if (!session) {
    return false;
  }

  if (session.mac?.readyState === WebSocket.OPEN) {
    return true;
  }

  // Lets the phone rejoin the same relay session while the host is still inside
  // the temporary-absence grace window instead of forcing a full disconnect flow.
  return Boolean(session.macAbsenceTimer);
}

function closeSessionClients(session, code, reason) {
  for (const client of session.clients) {
    if (client.readyState === WebSocket.OPEN || client.readyState === WebSocket.CONNECTING) {
      client.close(code, reason);
    }
  }
}

function relaySessionLogLabel(sessionId) {
  const normalizedSessionId = typeof sessionId === "string" ? sessionId.trim() : "";
  if (!normalizedSessionId) {
    return "session=[redacted]";
  }

  const digest = createHash("sha256")
    .update(normalizedSessionId)
    .digest("hex")
    .slice(0, 8);
  return `session#${digest}`;
}

// Resolves the current live relay session for a previously trusted host without exposing the session id publicly.
function resolveTrustedMacSession({
  macDeviceId,
  phoneDeviceId,
  phoneIdentityPublicKey,
  timestamp,
  nonce,
  signature,
  now = Date.now(),
} = {}) {
  const normalizedMacDeviceId = normalizeNonEmptyString(macDeviceId);
  const normalizedPhoneDeviceId = normalizeNonEmptyString(phoneDeviceId);
  const normalizedPhoneIdentityPublicKey = normalizeNonEmptyString(phoneIdentityPublicKey);
  const normalizedNonce = normalizeNonEmptyString(nonce);
  const normalizedSignature = normalizeNonEmptyString(signature);
  const normalizedTimestamp = Number(timestamp);

  if (
    !normalizedMacDeviceId
    || !normalizedPhoneDeviceId
    || !normalizedPhoneIdentityPublicKey
    || !normalizedNonce
    || !normalizedSignature
    || !Number.isFinite(normalizedTimestamp)
  ) {
    throw createRelayError(400, "invalid_request", "The trusted-session resolve request is missing required fields.");
  }

  if (Math.abs(now - normalizedTimestamp) > TRUSTED_SESSION_RESOLVE_SKEW_MS) {
    throw createRelayError(401, "resolve_request_expired", "This trusted-session resolve request has expired.");
  }

  pruneUsedResolveNonces(now);
  const nonceKey = `${normalizedMacDeviceId}|${normalizedPhoneDeviceId}|${normalizedNonce}`;
  if (usedResolveNonces.has(nonceKey)) {
    throw createRelayError(409, "resolve_request_replayed", "This trusted-session resolve request was already used.");
  }

  const liveSession = liveSessionsByMacDeviceId.get(normalizedMacDeviceId);
  if (!liveSession || !hasActiveMacSession(liveSession.sessionId)) {
    throw createRelayError(404, "session_unavailable", "The trusted host is offline right now.");
  }

  if (
    liveSession.trustedPhoneDeviceId !== normalizedPhoneDeviceId
    || liveSession.trustedPhonePublicKey !== normalizedPhoneIdentityPublicKey
  ) {
    throw createRelayError(403, "phone_not_trusted", "This mobile client is not trusted for the requested host.");
  }

  const transcriptBytes = buildTrustedSessionResolveBytes({
    macDeviceId: normalizedMacDeviceId,
    phoneDeviceId: normalizedPhoneDeviceId,
    phoneIdentityPublicKey: normalizedPhoneIdentityPublicKey,
    nonce: normalizedNonce,
    timestamp: normalizedTimestamp,
  });
  if (!verifyTrustedSessionResolveSignature(
    normalizedPhoneIdentityPublicKey,
    transcriptBytes,
    normalizedSignature
  )) {
    throw createRelayError(403, "invalid_signature", "The trusted-session resolve signature is invalid.");
  }

  usedResolveNonces.set(nonceKey, now + TRUSTED_SESSION_RESOLVE_SKEW_MS);
  return {
    ok: true,
    macDeviceId: normalizedMacDeviceId,
    macIdentityPublicKey: liveSession.macIdentityPublicKey,
    displayName: liveSession.displayName || null,
    sessionId: stableRelayHostIdForMacDeviceId(normalizedMacDeviceId),
  };
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

function hasActiveMacSession(sessionId) {
  if (typeof sessionId !== "string" || !sessionId.trim()) {
    return false;
  }

  const session = sessions.get(sessionId.trim());
  return Boolean(session?.mac && session.mac.readyState === WebSocket.OPEN);
}

function hasAuthenticatedMacSession(sessionId, notificationSecret) {
  if (!hasActiveMacSession(sessionId)) {
    return false;
  }

  const session = sessions.get(sessionId.trim());
  return session?.notificationSecret === readHeaderString(notificationSecret);
}

function hasLiveSession(sessionId) {
  return hasActiveMacSession(sessionId);
}

function stableRelayHostIdForMacDeviceId(macDeviceId) {
  const normalizedMacDeviceId = normalizeNonEmptyString(macDeviceId);
  if (!normalizedMacDeviceId) {
    return "";
  }
  return `${STABLE_RELAY_HOST_PREFIX}${normalizedMacDeviceId}`;
}

function macDeviceIdFromStableRelayHostId(hostId) {
  const normalizedHostId = normalizeNonEmptyString(hostId);
  if (!normalizedHostId.startsWith(STABLE_RELAY_HOST_PREFIX)) {
    return "";
  }

  return normalizeNonEmptyString(normalizedHostId.slice(STABLE_RELAY_HOST_PREFIX.length));
}

function resolveMobileConnectionSessionId(requestedSessionId) {
  const normalizedRequestedSessionId = normalizeNonEmptyString(requestedSessionId);
  if (!normalizedRequestedSessionId) {
    return "";
  }

  const macDeviceId = macDeviceIdFromStableRelayHostId(normalizedRequestedSessionId);
  if (!macDeviceId) {
    return normalizedRequestedSessionId;
  }

  const liveSession = liveSessionsByMacDeviceId.get(macDeviceId);
  if (!liveSession || !hasActiveMacSession(liveSession.sessionId)) {
    return "";
  }

  return liveSession.sessionId;
}

function registerLiveMacSession(macRegistration) {
  if (!macRegistration?.macDeviceId) {
    return;
  }
  liveSessionsByMacDeviceId.set(macRegistration.macDeviceId, macRegistration);
}

function applyMacRegistrationMessage(session, sessionId, rawMessage) {
  const parsed = safeParseJSON(rawMessage);
  if (parsed?.kind !== "relayMacRegistration" || typeof parsed.registration !== "object") {
    return false;
  }

  session.macRegistration = normalizeMacRegistration(parsed.registration, sessionId);
  registerLiveMacSession(session.macRegistration);
  return true;
}

function unregisterLiveMacSession(macRegistration, sessionId) {
  const macDeviceId = macRegistration?.macDeviceId;
  if (!macDeviceId) {
    return;
  }

  const existing = liveSessionsByMacDeviceId.get(macDeviceId);
  if (existing?.sessionId === sessionId) {
    liveSessionsByMacDeviceId.delete(macDeviceId);
  }
}

function readMacRegistrationHeaders(headers, sessionId) {
  return normalizeMacRegistration({
    macDeviceId: readHeaderString(headers["x-mac-device-id"]),
    macIdentityPublicKey: readHeaderString(headers["x-mac-identity-public-key"]),
    displayName: readHeaderString(headers["x-machine-name"]),
    trustedPhoneDeviceId: readHeaderString(headers["x-trusted-phone-device-id"]),
    trustedPhonePublicKey: readHeaderString(headers["x-trusted-phone-public-key"]),
  }, sessionId);
}

function normalizeMacRegistration(registration, sessionId) {
  return {
    sessionId,
    macDeviceId: normalizeNonEmptyString(registration?.macDeviceId),
    macIdentityPublicKey: normalizeNonEmptyString(registration?.macIdentityPublicKey),
    displayName: normalizeNonEmptyString(registration?.displayName),
    trustedPhoneDeviceId: normalizeNonEmptyString(registration?.trustedPhoneDeviceId),
    trustedPhonePublicKey: normalizeNonEmptyString(registration?.trustedPhonePublicKey),
  };
}

function buildTrustedSessionResolveBytes({
  macDeviceId,
  phoneDeviceId,
  phoneIdentityPublicKey,
  nonce,
  timestamp,
}) {
  return Buffer.concat([
    encodeLengthPrefixedUTF8(TRUSTED_SESSION_RESOLVE_TAG),
    encodeLengthPrefixedUTF8(macDeviceId),
    encodeLengthPrefixedUTF8(phoneDeviceId),
    encodeLengthPrefixedData(Buffer.from(phoneIdentityPublicKey, "base64")),
    encodeLengthPrefixedUTF8(nonce),
    encodeLengthPrefixedUTF8(String(timestamp)),
  ]);
}

function verifyTrustedSessionResolveSignature(publicKeyBase64, transcriptBytes, signatureBase64) {
  try {
    return verify(
      null,
      transcriptBytes,
      createPublicKey({
        key: {
          crv: "Ed25519",
          kty: "OKP",
          x: base64ToBase64Url(publicKeyBase64),
        },
        format: "jwk",
      }),
      Buffer.from(signatureBase64, "base64")
    );
  } catch {
    return false;
  }
}

function pruneUsedResolveNonces(now) {
  for (const [nonceKey, expiresAt] of usedResolveNonces.entries()) {
    if (now >= expiresAt) {
      usedResolveNonces.delete(nonceKey);
    }
  }
}

function encodeLengthPrefixedUTF8(value) {
  return encodeLengthPrefixedData(Buffer.from(value, "utf8"));
}

function encodeLengthPrefixedData(value) {
  const length = Buffer.allocUnsafe(4);
  length.writeUInt32BE(value.length, 0);
  return Buffer.concat([length, value]);
}

function base64ToBase64Url(value) {
  return String(value || "")
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replace(/=+$/g, "");
}

function readHeaderString(value) {
  if (typeof value === "string" && value.trim()) {
    return value.trim();
  }
  if (Array.isArray(value) && typeof value[0] === "string" && value[0].trim()) {
    return value[0].trim();
  }
  return "";
}

function normalizeNonEmptyString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

function createRelayError(status, code, message) {
  return Object.assign(new Error(message), {
    status,
    code,
  });
}

function safeParseJSON(value) {
  if (typeof value !== "string") {
    return null;
  }

  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

module.exports = {
  hasActiveMacSession,
  hasAuthenticatedMacSession,
  hasLiveSession,
  getRelayStats,
  resolveTrustedMacSession,
  setupRelay,
};
