// FILE: push-service.js
// Purpose: Stores session-scoped Android push registrations and forwards completion payloads to a configured webhook.
// Layer: Hosted service helper
// Exports: createPushSessionService, createFileBackedPushStateStore, createWebhookPushClient, resolvePushStateFilePath
// Depends on: fs, os, path

const fs = require("fs");
const os = require("os");
const path = require("path");

const PUSH_DEDUPE_TTL_MS = 24 * 60 * 60 * 1000;
const PUSH_SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const DEFAULT_WEBHOOK_TIMEOUT_MS = 10_000;
const DEFAULT_WEBHOOK_PATH = "/v1/push/webhook/notify-completion";

function createPushSessionService({
  webhookClient = createWebhookPushClient(webhookConfigFromEnv(process.env)),
  canRegisterSession = () => true,
  canNotifyCompletion = null,
  now = () => Date.now(),
  logPrefix = "[androdex-relay]",
  stateStore = createFileBackedPushStateStore({
    stateFilePath: resolvePushStateFilePath(process.env),
  }),
} = {}) {
  const resolvedCanNotifyCompletion = typeof canNotifyCompletion === "function"
    ? canNotifyCompletion
    : canRegisterSession;
  const persistedState = stateStore.read();
  const sessions = new Map(persistedState.sessions || []);
  const deliveredDedupeKeys = new Map(persistedState.deliveredDedupeKeys || []);
  pruneStaleState();

  async function registerDevice({
    sessionId,
    deviceToken,
    alertsEnabled,
    devicePlatform,
    appEnvironment,
  } = {}) {
    const normalizedSessionId = readString(sessionId);
    const normalizedDeviceToken = normalizeDeviceToken(deviceToken);
    const normalizedPlatform = normalizeDevicePlatform(devicePlatform);
    const normalizedEnvironment = normalizeAppEnvironment(appEnvironment);

    if (!normalizedSessionId || !normalizedDeviceToken) {
      throw pushServiceError(
        "invalid_request",
        "Push registration requires sessionId and deviceToken.",
        400
      );
    }

    if (!await canRegisterSession({ sessionId: normalizedSessionId })) {
      throw pushServiceError(
        "session_unavailable",
        "Push registration requires an active relay session.",
        403
      );
    }

    sessions.set(normalizedSessionId, {
      deviceToken: normalizedDeviceToken,
      alertsEnabled: Boolean(alertsEnabled),
      devicePlatform: normalizedPlatform,
      appEnvironment: normalizedEnvironment,
      updatedAt: now(),
    });
    persistState("registerDevice");
    return { ok: true };
  }

  async function notifyCompletion({
    sessionId,
    threadId,
    turnId,
    result,
    title,
    body,
    dedupeKey,
  } = {}) {
    const normalizedSessionId = readString(sessionId);
    const normalizedThreadId = readString(threadId);
    const normalizedDedupeKey = readString(dedupeKey);
    const normalizedResult = normalizeCompletionResult(result);

    if (!normalizedSessionId || !normalizedThreadId || !normalizedDedupeKey) {
      throw pushServiceError(
        "invalid_request",
        "Push completion requires sessionId, threadId, and dedupeKey.",
        400
      );
    }

    if (!await resolvedCanNotifyCompletion({ sessionId: normalizedSessionId })) {
      throw pushServiceError(
        "session_unavailable",
        "Push completion requires an active relay session.",
        403
      );
    }

    pruneDeliveredDedupeKeys();
    if (deliveredDedupeKeys.has(normalizedDedupeKey)) {
      return { ok: true, deduped: true };
    }

    const session = sessions.get(normalizedSessionId);
    if (!session) {
      throw pushServiceError(
        "session_missing",
        "No push registration exists for this session.",
        404
      );
    }

    if (!session.alertsEnabled || !session.deviceToken) {
      return { ok: true, skipped: true, reason: "alerts_disabled" };
    }

    if (!webhookClient?.hasConfiguredBaseUrl) {
      return { ok: true, skipped: true, reason: "webhook_disabled" };
    }

    await webhookClient.notifyCompletion({
      sessionId: normalizedSessionId,
      threadId: normalizedThreadId,
      turnId: readString(turnId),
      result: normalizedResult,
      title: normalizePreviewText(title) || "New Thread",
      body: normalizePreviewText(body) || fallbackBodyForResult(normalizedResult),
      dedupeKey: normalizedDedupeKey,
      deviceToken: session.deviceToken,
      devicePlatform: session.devicePlatform,
      appEnvironment: session.appEnvironment,
    });

    deliveredDedupeKeys.set(normalizedDedupeKey, now());
    persistState("notifyCompletion");
    return { ok: true };
  }

  function getStats() {
    pruneDeliveredDedupeKeys();
    return {
      enabled: true,
      registeredSessions: sessions.size,
      deliveredDedupeKeys: deliveredDedupeKeys.size,
      webhookConfigured: Boolean(webhookClient?.hasConfiguredBaseUrl),
    };
  }

  function pruneDeliveredDedupeKeys() {
    let didChange = false;
    const cutoff = now() - PUSH_DEDUPE_TTL_MS;
    for (const [key, timestamp] of deliveredDedupeKeys.entries()) {
      if (timestamp < cutoff) {
        deliveredDedupeKeys.delete(key);
        didChange = true;
      }
    }
    return didChange;
  }

  function pruneSessions() {
    let didChange = false;
    const cutoff = now() - PUSH_SESSION_TTL_MS;
    for (const [sessionId, session] of sessions.entries()) {
      if (Number(session?.updatedAt || 0) < cutoff) {
        sessions.delete(sessionId);
        didChange = true;
      }
    }
    return didChange;
  }

  function pruneStaleState() {
    if (pruneDeliveredDedupeKeys() || pruneSessions()) {
      persistState("pruneStaleState");
    }
  }

  function persistState(reason) {
    try {
      stateStore.write({
        sessions: [...sessions.entries()],
        deliveredDedupeKeys: [...deliveredDedupeKeys.entries()],
      });
    } catch (error) {
      console.error(
        `${logPrefix} push state persistence failed during ${reason}: ${error.message}`
      );
    }
  }

  return {
    registerDevice,
    notifyCompletion,
    getStats,
  };
}

function createFileBackedPushStateStore({ stateFilePath } = {}) {
  const resolvedPath = typeof stateFilePath === "string" && stateFilePath.trim()
    ? stateFilePath.trim()
    : "";

  return {
    read() {
      if (!resolvedPath || !fs.existsSync(resolvedPath)) {
        return emptyPushState();
      }

      const parsed = safeParseJSON(fs.readFileSync(resolvedPath, "utf8"));
      if (!parsed || typeof parsed !== "object") {
        return emptyPushState();
      }

      return {
        sessions: normalizeEntryList(parsed.sessions),
        deliveredDedupeKeys: normalizeEntryList(parsed.deliveredDedupeKeys),
      };
    },
    write(state) {
      if (!resolvedPath) {
        return;
      }

      const normalizedState = {
        sessions: normalizeEntryList(state?.sessions),
        deliveredDedupeKeys: normalizeEntryList(state?.deliveredDedupeKeys),
      };
      fs.mkdirSync(path.dirname(resolvedPath), { recursive: true });
      const tempPath = `${resolvedPath}.tmp`;
      fs.writeFileSync(tempPath, JSON.stringify(normalizedState), {
        encoding: "utf8",
        mode: 0o600,
      });
      fs.renameSync(tempPath, resolvedPath);
      try {
        fs.chmodSync(resolvedPath, 0o600);
      } catch {
        // Best-effort only on filesystems that support POSIX modes.
      }
    },
  };
}

function createWebhookPushClient({
  baseUrl = "",
  token = "",
  pathname = DEFAULT_WEBHOOK_PATH,
  fetchImpl = globalThis.fetch,
  logPrefix = "[androdex-relay]",
  requestTimeoutMs = DEFAULT_WEBHOOK_TIMEOUT_MS,
} = {}) {
  const normalizedBaseUrl = normalizeBaseUrl(baseUrl);
  const normalizedToken = readString(token);
  const normalizedPathname = normalizePathname(pathname);

  async function notifyCompletion(payload = {}) {
    return postJSON(normalizedPathname, payload);
  }

  async function postJSON(requestPath, payload) {
    if (!normalizedBaseUrl || typeof fetchImpl !== "function") {
      return { ok: false, skipped: true };
    }

    const controller = typeof AbortController === "function" && requestTimeoutMs > 0
      ? new AbortController()
      : null;
    const timeoutId = controller
      ? setTimeout(() => {
        controller.abort(createTimeoutAbortError(requestTimeoutMs));
      }, requestTimeoutMs)
      : null;

    let response;
    try {
      response = await fetchImpl(`${normalizedBaseUrl}${requestPath}`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          ...(normalizedToken ? { authorization: `Bearer ${normalizedToken}` } : {}),
        },
        body: JSON.stringify(payload),
        signal: controller?.signal,
      });
    } catch (error) {
      if (isAbortError(error)) {
        const timeoutError = new Error(`Push webhook request timed out after ${requestTimeoutMs}ms`);
        timeoutError.code = "push_request_timeout";
        throw timeoutError;
      }
      throw error;
    } finally {
      if (timeoutId) {
        clearTimeout(timeoutId);
      }
    }

    const responseText = await response.text();
    const parsed = safeParseJSON(responseText);
    if (!response.ok) {
      const message = parsed?.error || parsed?.message || responseText || `HTTP ${response.status}`;
      const error = new Error(message);
      error.status = response.status;
      throw error;
    }

    return parsed ?? { ok: true };
  }

  return {
    hasConfiguredBaseUrl: Boolean(normalizedBaseUrl),
    notifyCompletion,
    logUnavailable() {
      if (!normalizedBaseUrl) {
        console.log(`${logPrefix} push notifications disabled: no webhook URL configured`);
      }
    },
  };
}

function webhookConfigFromEnv(env) {
  return {
    baseUrl: readString(env?.ANDRODEX_PUSH_WEBHOOK_URL),
    token: readString(env?.ANDRODEX_PUSH_WEBHOOK_TOKEN),
    pathname: readString(env?.ANDRODEX_PUSH_WEBHOOK_PATH) || DEFAULT_WEBHOOK_PATH,
    requestTimeoutMs: parseIntegerEnv(env?.ANDRODEX_PUSH_WEBHOOK_TIMEOUT_MS, DEFAULT_WEBHOOK_TIMEOUT_MS),
  };
}

function resolvePushStateFilePath(env = process.env) {
  const explicitPath = readString(env?.ANDRODEX_PUSH_STATE_FILE);
  if (explicitPath) {
    return explicitPath;
  }

  const codexHome = readString(env?.CODEX_HOME) || path.join(os.homedir(), ".codex");
  return path.join(codexHome, "androdex", "push-state.json");
}

function normalizeDeviceToken(value) {
  const normalized = readString(value);
  if (!normalized) {
    return "";
  }

  return normalized.replace(/[^a-fA-F0-9]/g, "").toLowerCase();
}

function normalizeDevicePlatform(value) {
  const normalized = readString(value).toLowerCase();
  return normalized || "android";
}

function normalizeAppEnvironment(value) {
  const normalized = readString(value).toLowerCase();
  return normalized === "development" ? "development" : "production";
}

function normalizeCompletionResult(value) {
  return value === "failed" ? "failed" : "completed";
}

function normalizePreviewText(value) {
  const normalized = readString(value).replace(/\s+/g, " ");
  if (!normalized) {
    return "";
  }

  return normalized.length > 160
    ? `${normalized.slice(0, 159).trimEnd()}…`
    : normalized;
}

function fallbackBodyForResult(result) {
  return result === "failed" ? "Run failed" : "Response ready";
}

function normalizeBaseUrl(value) {
  if (typeof value !== "string") {
    return "";
  }
  const trimmed = value.trim();
  return trimmed ? trimmed.replace(/\/+$/, "") : "";
}

function normalizePathname(value) {
  const normalized = readString(value);
  if (!normalized) {
    return DEFAULT_WEBHOOK_PATH;
  }

  return normalized.startsWith("/") ? normalized : `/${normalized}`;
}

function parseIntegerEnv(rawValue, fallback) {
  const parsed = Number(rawValue);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function createTimeoutAbortError(timeoutMs) {
  const error = new Error(`Push webhook request timed out after ${timeoutMs}ms`);
  error.name = "AbortError";
  return error;
}

function isAbortError(error) {
  return error?.name === "AbortError" || error?.code === "ABORT_ERR";
}

function normalizeEntryList(value) {
  if (!Array.isArray(value)) {
    return [];
  }

  return value.filter((entry) => Array.isArray(entry) && entry.length === 2);
}

function emptyPushState() {
  return {
    sessions: [],
    deliveredDedupeKeys: [],
  };
}

function safeParseJSON(value) {
  if (!value || typeof value !== "string") {
    return null;
  }

  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

function readString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

function pushServiceError(code, message, status) {
  const error = new Error(message);
  error.code = code;
  error.status = status;
  return error;
}

module.exports = {
  createPushSessionService,
  createFileBackedPushStateStore,
  createWebhookPushClient,
  resolvePushStateFilePath,
};
