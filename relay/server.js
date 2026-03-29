// FILE: server.js
// Purpose: Standalone HTTP/HTTPS wrapper around the Androdex WebSocket relay and optional push service.
// Layer: relay runtime

const fs = require("fs");
const http = require("http");
const https = require("https");
const { WebSocketServer } = require("ws");
const {
  setupRelay,
  getRelayStats,
  hasAuthenticatedMacSession,
  resolveTrustedMacSession,
} = require("./relay");
const {
  createPushSessionService,
  createWebhookPushClient,
} = require("./push-service");

function createRelayServer({
  host = "0.0.0.0",
  port = 8787,
  tlsCertPath = "",
  tlsKeyPath = "",
  enablePushService = false,
  pushSessionService = null,
  pushWebhookUrl = "",
  pushWebhookToken = "",
  pushWebhookPath = "",
  pushWebhookTimeoutMs = 10_000,
} = {}) {
  const isTlsEnabled = Boolean(tlsCertPath && tlsKeyPath);
  const resolvedPushSessionService = pushSessionService
    || (enablePushService
      ? createPushSessionService({
        webhookClient: createWebhookPushClient({
          baseUrl: pushWebhookUrl,
          token: pushWebhookToken,
          pathname: pushWebhookPath,
          requestTimeoutMs: pushWebhookTimeoutMs,
        }),
        canRegisterSession({ sessionId, notificationSecret }) {
          return hasAuthenticatedMacSession(sessionId, notificationSecret);
        },
        canNotifyCompletion({ sessionId, notificationSecret }) {
          return hasAuthenticatedMacSession(sessionId, notificationSecret);
        },
      })
      : createDisabledPushSessionService());

  const server = createServer({
    isTlsEnabled,
    tlsCertPath,
    tlsKeyPath,
    pushSessionService: resolvedPushSessionService,
    pushServiceEnabled: Boolean(enablePushService || pushSessionService),
  });
  const wss = new WebSocketServer({ server });
  setupRelay(wss);

  return {
    server,
    wss,
    pushSessionService: resolvedPushSessionService,
  };
}

function createServer({
  isTlsEnabled,
  tlsCertPath,
  tlsKeyPath,
  pushSessionService,
  pushServiceEnabled,
}) {
  const requestListener = (req, res) => {
    const pathname = safePathname(req.url);
    if (req.method === "GET" && (pathname === "/health" || pathname === "/healthz")) {
      writeJson(res, 200, {
        ok: true,
        protocol: isTlsEnabled ? "https" : "http",
        relayPath: "/relay/{hostId}",
        ...getRelayStats(),
        push: pushSessionService.getStats(),
      });
      return;
    }

    if (pushServiceEnabled && req.method === "POST" && pathname === "/v1/push/session/register-device") {
      return handleJSONRoute(req, res, async (body) => pushSessionService.registerDevice(body));
    }

    if (pushServiceEnabled && req.method === "POST" && pathname === "/v1/push/session/notify-completion") {
      return handleJSONRoute(req, res, async (body) => pushSessionService.notifyCompletion(body));
    }

    if (req.method === "POST" && pathname === "/v1/trusted/session/resolve") {
      return handleJSONRoute(req, res, async (body) => resolveTrustedMacSession(body));
    }

    writeJson(res, 404, { ok: false, error: "Not found" });
  };

  if (!isTlsEnabled) {
    return http.createServer(requestListener);
  }

  return https.createServer(
    {
      cert: fs.readFileSync(tlsCertPath),
      key: fs.readFileSync(tlsKeyPath),
    },
    requestListener
  );
}

function handleJSONRoute(req, res, handler) {
  const chunks = [];
  let totalSize = 0;

  req.on("data", (chunk) => {
    totalSize += chunk.length;
    if (totalSize > 64 * 1024) {
      writeJson(res, 413, { ok: false, error: "Request body too large" });
      req.destroy();
      return;
    }
    chunks.push(chunk);
  });

  req.on("end", async () => {
    const rawBody = Buffer.concat(chunks).toString("utf8");
    let body = {};
    if (rawBody.trim()) {
      try {
        body = JSON.parse(rawBody);
      } catch {
        writeJson(res, 400, { ok: false, error: "Invalid JSON body" });
        return;
      }
    }

    try {
      const result = await handler(body);
      writeJson(res, 200, result);
    } catch (error) {
      writeJson(res, error.status || 500, {
        ok: false,
        error: error.message || "Internal server error",
        code: error.code || "internal_error",
      });
    }
  });

  req.on("error", () => {
    writeJson(res, 500, { ok: false, error: "Internal server error" });
  });
}

function writeJson(res, statusCode, body) {
  res.writeHead(statusCode, { "content-type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(body));
}

function safePathname(rawUrl) {
  try {
    return new URL(rawUrl || "/", "http://localhost").pathname;
  } catch {
    return "/";
  }
}

function parsePort(rawValue) {
  const parsed = Number(rawValue);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 65535) {
    throw new Error(`Invalid ANDRODEX_RELAY_PORT: ${rawValue}`);
  }
  return parsed;
}

function readEnv(name, fallback) {
  const value = process.env[name];
  return typeof value === "string" && value.trim() ? value.trim() : fallback;
}

function readOptionalBooleanEnv(names) {
  const truthy = new Set(["1", "true", "yes", "on"]);
  const falsy = new Set(["0", "false", "no", "off"]);

  for (const name of names) {
    const value = readEnv(name, "");
    if (!value) {
      continue;
    }
    const normalized = value.toLowerCase();
    if (truthy.has(normalized)) {
      return true;
    }
    if (falsy.has(normalized)) {
      return false;
    }
  }

  return undefined;
}

function parsePositiveIntegerEnv(rawValue, fallback) {
  const parsed = Number(rawValue);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function createDisabledPushSessionService() {
  return {
    getStats() {
      return {
        enabled: false,
        status: "disabled",
        registeredSessions: 0,
        deliveredDedupeKeys: 0,
        webhookConfigured: false,
        reason: "Set ANDRODEX_ENABLE_PUSH_SERVICE=true to expose the public push-session helper.",
      };
    },
    async registerDevice() {
      return { ok: false, skipped: true };
    },
    async notifyCompletion() {
      return { ok: false, skipped: true };
    },
  };
}

if (require.main === module) {
  const host = readEnv("ANDRODEX_RELAY_HOST", "0.0.0.0");
  const port = parsePort(readEnv("ANDRODEX_RELAY_PORT", "8787"));
  const tlsCertPath = readEnv("ANDRODEX_RELAY_TLS_CERT", "");
  const tlsKeyPath = readEnv("ANDRODEX_RELAY_TLS_KEY", "");
  const enablePushService = readOptionalBooleanEnv(["ANDRODEX_ENABLE_PUSH_SERVICE"]) ?? false;
  const pushWebhookUrl = readEnv("ANDRODEX_PUSH_WEBHOOK_URL", "");
  const pushWebhookToken = readEnv("ANDRODEX_PUSH_WEBHOOK_TOKEN", "");
  const pushWebhookPath = readEnv("ANDRODEX_PUSH_WEBHOOK_PATH", "");
  const pushWebhookTimeoutMs = parsePositiveIntegerEnv(
    readEnv("ANDRODEX_PUSH_WEBHOOK_TIMEOUT_MS", ""),
    10_000
  );
  const { server } = createRelayServer({
    host,
    port,
    tlsCertPath,
    tlsKeyPath,
    enablePushService,
    pushWebhookUrl,
    pushWebhookToken,
    pushWebhookPath,
    pushWebhookTimeoutMs,
  });

  server.listen(port, host, () => {
    const protocol = tlsCertPath && tlsKeyPath ? "wss" : "ws";
    console.log(`[androdex-relay] listening on ${protocol}://${host}:${port}/relay`);
    if (enablePushService) {
      console.log("[androdex-relay] push service enabled at /v1/push/session/*");
    }
  });
}

module.exports = {
  createRelayServer,
  createDisabledPushSessionService,
  parsePort,
  readEnv,
  readOptionalBooleanEnv,
  parsePositiveIntegerEnv,
};
