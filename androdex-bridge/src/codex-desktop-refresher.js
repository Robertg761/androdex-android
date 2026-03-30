// FILE: codex-desktop-refresher.js
// Purpose: Debounced Mac desktop refresh controller for Codex.app after phone-authored conversation changes.
// Layer: CLI helper
// Exports: CodexDesktopRefresher, readBridgeConfig
// Depends on: child_process, fs, path, ./rollout-watch

const { execFile } = require("child_process");
const fs = require("fs");
const path = require("path");
const { createThreadRolloutActivityWatcher } = require("./rollout-watch");

const DEFAULT_BUNDLE_ID = "com.openai.codex";
const DEFAULT_APP_PATH = "/Applications/Codex.app";
const PUBLIC_DEFAULT_RELAY_URL = "wss://relay.androdex.xyz/relay";
const DEFAULT_DEBOUNCE_MS = 1200;
const DEFAULT_FALLBACK_NEW_THREAD_MS = 2_000;
const DEFAULT_MID_RUN_REFRESH_THROTTLE_MS = 3_000;
const DEFAULT_ROLLOUT_LOOKUP_TIMEOUT_MS = 5_000;
const DEFAULT_ROLLOUT_IDLE_TIMEOUT_MS = 10_000;
const DEFAULT_CUSTOM_REFRESH_FAILURE_THRESHOLD = 3;
const REFRESH_SCRIPT_PATH = path.join(__dirname, "scripts", "codex-refresh.applescript");
const NEW_THREAD_DEEP_LINK = "codex://threads/new";

class CodexDesktopRefresher {
  constructor({
    enabled = true,
    debounceMs = DEFAULT_DEBOUNCE_MS,
    refreshCommand = "",
    bundleId = DEFAULT_BUNDLE_ID,
    appPath = DEFAULT_APP_PATH,
    logPrefix = "[androdex]",
    fallbackNewThreadMs = DEFAULT_FALLBACK_NEW_THREAD_MS,
    midRunRefreshThrottleMs = DEFAULT_MID_RUN_REFRESH_THROTTLE_MS,
    rolloutLookupTimeoutMs = DEFAULT_ROLLOUT_LOOKUP_TIMEOUT_MS,
    rolloutIdleTimeoutMs = DEFAULT_ROLLOUT_IDLE_TIMEOUT_MS,
    now = () => Date.now(),
    refreshExecutor = null,
    watchThreadRolloutFactory = createThreadRolloutActivityWatcher,
    refreshBackend = null,
    customRefreshFailureThreshold = DEFAULT_CUSTOM_REFRESH_FAILURE_THRESHOLD,
  } = {}) {
    this.enabled = enabled;
    this.debounceMs = debounceMs;
    this.refreshCommand = refreshCommand;
    this.bundleId = bundleId;
    this.appPath = appPath;
    this.logPrefix = logPrefix;
    this.fallbackNewThreadMs = fallbackNewThreadMs;
    this.midRunRefreshThrottleMs = midRunRefreshThrottleMs;
    this.rolloutLookupTimeoutMs = rolloutLookupTimeoutMs;
    this.rolloutIdleTimeoutMs = rolloutIdleTimeoutMs;
    this.now = now;
    this.refreshExecutor = refreshExecutor;
    this.watchThreadRolloutFactory = watchThreadRolloutFactory;
    this.refreshBackend = refreshBackend
      || (this.refreshCommand ? "command" : (this.refreshExecutor ? "command" : "applescript"));
    this.customRefreshFailureThreshold = customRefreshFailureThreshold;

    this.mode = "idle";
    this.pendingNewThread = false;
    this.pendingRefreshKinds = new Set();
    this.pendingCompletionRefresh = false;
    this.pendingCompletionTurnId = null;
    this.pendingCompletionTargetUrl = "";
    this.pendingCompletionTargetThreadId = "";
    this.pendingTargetUrl = "";
    this.pendingTargetThreadId = "";
    this.lastRefreshAt = 0;
    this.lastRefreshSignature = "";
    this.lastTurnIdRefreshed = null;
    this.lastMidRunRefreshAt = 0;
    this.refreshTimer = null;
    this.refreshRunning = false;
    this.fallbackTimer = null;
    this.activeWatcher = null;
    this.activeWatchedThreadId = null;
    this.watchStartAt = 0;
    this.lastRolloutSize = null;
    this.stopWatcherAfterRefreshThreadId = null;
    this.runtimeRefreshAvailable = enabled;
    this.consecutiveRefreshFailures = 0;
    this.unavailableLogged = false;
  }

  handleInbound(rawMessage) {
    const parsed = safeParseJSON(rawMessage);
    if (!parsed) {
      return;
    }

    const method = parsed.method;
    if (method === "thread/start") {
      const target = resolveInboundTarget(method, parsed);
      if (target?.threadId) {
        this.queueRefresh("phone", target, `phone ${method}`);
        this.ensureWatcher(target.threadId);
        return;
      }

      this.pendingNewThread = true;
      this.mode = "pending_new_thread";
      this.clearPendingTarget();
      this.scheduleNewThreadFallback();
      return;
    }

    if (method === "turn/start") {
      const target = resolveInboundTarget(method, parsed);
      if (!target) {
        return;
      }

      this.queueRefresh("phone", target, `phone ${method}`);
      if (target.threadId) {
        this.ensureWatcher(target.threadId);
      }
    }
  }

  handleOutbound(rawMessage) {
    const parsed = safeParseJSON(rawMessage);
    if (!parsed) {
      return;
    }

    const method = parsed.method;
    if (method === "turn/completed") {
      this.clearFallbackTimer();
      const turnId = extractTurnId(parsed);
      if (turnId && turnId === this.lastTurnIdRefreshed) {
        this.log(`refresh skipped (debounced): completion already refreshed for ${turnId}`);
        return;
      }

      const target = resolveOutboundTarget(method, parsed);
      this.queueCompletionRefresh(target, turnId, `codex ${method}`);
      return;
    }

    if (method === "thread/started") {
      const target = resolveOutboundTarget(method, parsed);
      this.pendingNewThread = false;
      this.clearFallbackTimer();
      this.queueRefresh("phone", target, `codex ${method}`);
      if (target?.threadId) {
        this.mode = "watching_thread";
        this.ensureWatcher(target.threadId);
      }
    }
  }

  handleTransportReset() {
    this.clearRefreshTimer();
    this.clearPendingState();
    this.lastRefreshAt = 0;
    this.lastRefreshSignature = "";
    this.mode = "idle";
    this.clearFallbackTimer();
    this.stopWatcher();
  }

  queueRefresh(kind, target, reason) {
    this.noteRefreshTarget(target);
    this.pendingRefreshKinds.add(kind);
    this.scheduleRefresh(reason);
  }

  queueCompletionRefresh(target, turnId, reason) {
    this.noteCompletionTarget(target);
    this.pendingCompletionRefresh = true;
    this.pendingCompletionTurnId = turnId;
    this.stopWatcherAfterRefreshThreadId = target?.threadId || null;
    this.scheduleRefresh(reason);
  }

  noteRefreshTarget(target) {
    if (!target?.url) {
      return;
    }

    this.pendingTargetUrl = target.url;
    this.pendingTargetThreadId = target.threadId || "";
    this.mode = target.threadId ? "watching_thread" : "pending_new_thread";
  }

  noteCompletionTarget(target) {
    if (!target?.url) {
      return;
    }

    this.pendingCompletionTargetUrl = target.url;
    this.pendingCompletionTargetThreadId = target.threadId || "";
  }

  clearPendingTarget() {
    this.pendingTargetUrl = "";
    this.pendingTargetThreadId = "";
  }

  clearPendingState() {
    this.pendingNewThread = false;
    this.pendingRefreshKinds.clear();
    this.pendingCompletionRefresh = false;
    this.pendingCompletionTurnId = null;
    this.pendingCompletionTargetUrl = "";
    this.pendingCompletionTargetThreadId = "";
    this.stopWatcherAfterRefreshThreadId = null;
    this.clearPendingTarget();
  }

  scheduleNewThreadFallback() {
    this.clearFallbackTimer();
    this.fallbackTimer = setTimeout(() => {
      this.queueRefresh("phone", { threadId: null, url: NEW_THREAD_DEEP_LINK }, "new-thread fallback");
    }, this.fallbackNewThreadMs);
  }

  clearFallbackTimer() {
    if (!this.fallbackTimer) {
      return;
    }

    clearTimeout(this.fallbackTimer);
    this.fallbackTimer = null;
  }

  ensureWatcher(threadId) {
    if (!threadId || this.activeWatchedThreadId === threadId) {
      return;
    }

    this.stopWatcher();
    this.activeWatchedThreadId = threadId;
    this.watchStartAt = this.now();
    this.lastRolloutSize = null;
    this.activeWatcher = this.watchThreadRolloutFactory({
      threadId,
      timeoutMs: this.rolloutLookupTimeoutMs,
      idleTimeoutMs: this.rolloutIdleTimeoutMs,
      onActivity: ({ size }) => {
        if (size === this.lastRolloutSize) {
          return;
        }

        this.lastRolloutSize = size;
        const target = { threadId, url: buildThreadDeepLink(threadId) };
        this.queueRefresh("phone", target, "rollout activity");
      },
      onIdle: () => {
        if (this.stopWatcherAfterRefreshThreadId === threadId) {
          this.stopWatcher();
        }
      },
      onTimeout: () => {
        this.stopWatcher();
      },
      onError: () => {
        this.stopWatcher();
      },
    });
  }

  stopWatcher() {
    this.activeWatcher?.stop?.();
    this.activeWatcher = null;
    this.activeWatchedThreadId = null;
    this.watchStartAt = 0;
    this.lastRolloutSize = null;
    this.stopWatcherAfterRefreshThreadId = null;
  }

  scheduleRefresh(reason) {
    if (!this.canRefresh()) {
      return;
    }

    this.clearRefreshTimer();
    this.refreshTimer = setTimeout(() => {
      this.refreshTimer = null;
      void this.executeRefresh(reason);
    }, this.debounceMs);
  }

  clearRefreshTimer() {
    if (!this.refreshTimer) {
      return;
    }

    clearTimeout(this.refreshTimer);
    this.refreshTimer = null;
  }

  async executeRefresh(reason) {
    if (this.refreshRunning || !this.canRefresh() || !this.hasPendingRefreshWork()) {
      return;
    }

    this.refreshRunning = true;
    try {
      const completionTargetUrl = this.pendingCompletionTargetUrl;
      const completionTurnId = this.pendingCompletionTurnId;
      const completionThreadId = this.pendingCompletionTargetThreadId;
      const watcherThreadIdToStop = this.stopWatcherAfterRefreshThreadId;
      const targetUrl = this.pendingTargetUrl;
      const refreshKinds = new Set(this.pendingRefreshKinds);

      this.clearPendingState();

      if (completionTargetUrl) {
        await this.runRefresh(completionTargetUrl, {
          reason,
          refreshKinds,
          isCompletionRun: true,
        });
        this.lastTurnIdRefreshed = completionTurnId || null;
        if (completionThreadId && watcherThreadIdToStop === completionThreadId) {
          this.stopWatcher();
        }
        return;
      }

      if (targetUrl) {
        await this.runRefresh(targetUrl, {
          reason,
          refreshKinds,
          isCompletionRun: false,
        });
      }
    } catch (error) {
      this.consecutiveRefreshFailures += 1;
      const message = extractErrorMessage(error);
      this.log(`refresh failed (${reason}): ${message}`);
      if (this.consecutiveRefreshFailures >= this.customRefreshFailureThreshold || isDesktopUnavailableError(message)) {
        this.disableRuntimeRefresh(message);
      }
    } finally {
      this.refreshRunning = false;
      if (this.hasPendingRefreshWork()) {
        this.scheduleRefresh("follow-up refresh");
      }
    }
  }

  async runRefresh(targetUrl, { reason, refreshKinds = new Set(), isCompletionRun = false } = {}) {
    const signature = `${targetUrl}|${isCompletionRun ? "completion" : "activity"}`;
    if (
      !isCompletionRun
      && signature === this.lastRefreshSignature
      && (this.now() - this.lastRefreshAt) < this.midRunRefreshThrottleMs
    ) {
      return;
    }

    this.lastRefreshSignature = signature;
    this.lastRefreshAt = this.now();
    this.consecutiveRefreshFailures = 0;
    this.log(`refreshing desktop (${reason}) -> ${targetUrl}`);

    if (typeof this.refreshExecutor === "function") {
      await this.refreshExecutor({
        targetUrl,
        bundleId: this.bundleId,
        appPath: this.appPath,
      });
      return;
    }

    if (this.refreshBackend === "command") {
      await execFilePromise("sh", ["-lc", this.refreshCommand]);
      return;
    }

    if (this.refreshBackend === "applescript") {
      await execFilePromise("osascript", [
        REFRESH_SCRIPT_PATH,
        this.bundleId,
        this.appPath,
        targetUrl,
      ]);
      return;
    }
  }

  log(message) {
    console.log(`${this.logPrefix} ${message}`);
  }

  disableRuntimeRefresh(reason) {
    if (!this.runtimeRefreshAvailable) {
      return;
    }

    this.runtimeRefreshAvailable = false;
    this.clearRefreshTimer();
    this.clearFallbackTimer();
    this.stopWatcher();
    this.clearPendingState();
    this.mode = "idle";

    if (!this.unavailableLogged) {
      console.error(`${this.logPrefix} desktop refresh disabled until restart: ${reason}`);
      this.unavailableLogged = true;
    }
  }

  canRefresh() {
    return this.enabled && this.runtimeRefreshAvailable;
  }

  hasPendingRefreshWork() {
    return this.pendingCompletionRefresh || this.pendingRefreshKinds.size > 0;
  }
}

function readBridgeConfig({ env = process.env } = {}) {
  const codexEndpoint = readFirstDefinedEnv(["ANDRODEX_CODEX_ENDPOINT"], "", env);
  const refreshCommand = readFirstDefinedEnv(["ANDRODEX_REFRESH_COMMAND"], "", env);
  const explicitRefreshEnabled = readOptionalBooleanEnv(["ANDRODEX_REFRESH_ENABLED"], env);
  const relayUrl = readFirstDefinedEnv(
    ["ANDRODEX_RELAY", "ANDRODEX_DEFAULT_RELAY_URL"],
    PUBLIC_DEFAULT_RELAY_URL,
    env
  );

  return {
    relayUrl,
    pushServiceUrl: readFirstDefinedEnv(
      ["ANDRODEX_PUSH_SERVICE_URL"],
      "",
      env
    ),
    pushPreviewMaxChars: parseIntegerEnv(
      readFirstDefinedEnv(["ANDRODEX_PUSH_PREVIEW_MAX_CHARS"], "160", env),
      160
    ),
    refreshEnabled: explicitRefreshEnabled == null
      ? false
      : explicitRefreshEnabled,
    refreshDebounceMs: parseIntegerEnv(
      readFirstDefinedEnv(["ANDRODEX_REFRESH_DEBOUNCE_MS"], String(DEFAULT_DEBOUNCE_MS), env),
      DEFAULT_DEBOUNCE_MS
    ),
    codexEndpoint,
    refreshCommand,
    codexBundleId: readFirstDefinedEnv(["ANDRODEX_CODEX_BUNDLE_ID"], DEFAULT_BUNDLE_ID, env),
    codexAppPath: DEFAULT_APP_PATH,
  };
}

function execFilePromise(command, args) {
  return new Promise((resolve, reject) => {
    execFile(command, args, (error, stdout, stderr) => {
      if (error) {
        error.stdout = stdout;
        error.stderr = stderr;
        reject(error);
        return;
      }
      resolve({ stdout, stderr });
    });
  });
}

function safeParseJSON(value) {
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

function extractTurnId(message) {
  const params = message?.params;
  if (!params || typeof params !== "object") {
    return null;
  }

  if (typeof params.turnId === "string" && params.turnId) {
    return params.turnId;
  }

  if (params.turn && typeof params.turn === "object" && typeof params.turn.id === "string") {
    return params.turn.id;
  }

  return null;
}

function extractThreadId(message) {
  const params = message?.params;
  if (!params || typeof params !== "object") {
    return null;
  }

  const candidates = [
    params.threadId,
    params.conversationId,
    params.thread?.id,
    params.thread?.threadId,
    params.turn?.threadId,
    params.turn?.conversationId,
  ];

  for (const candidate of candidates) {
    if (typeof candidate === "string" && candidate) {
      return candidate;
    }
  }

  return null;
}

function resolveInboundTarget(method, message) {
  const threadId = extractThreadId(message);
  if (threadId) {
    return { threadId, url: buildThreadDeepLink(threadId) };
  }

  if (method === "thread/start" || method === "turn/start") {
    return { threadId: null, url: NEW_THREAD_DEEP_LINK };
  }

  return null;
}

function resolveOutboundTarget(method, message) {
  const threadId = extractThreadId(message);
  if (threadId) {
    return { threadId, url: buildThreadDeepLink(threadId) };
  }

  if (method === "thread/started") {
    return { threadId: null, url: NEW_THREAD_DEEP_LINK };
  }

  return null;
}

function buildThreadDeepLink(threadId) {
  return `codex://threads/${threadId}`;
}

function readOptionalBooleanEnv(keys, env = process.env) {
  for (const key of keys) {
    const value = env[key];
    if (typeof value === "string" && value.trim() !== "") {
      return parseBooleanEnv(value.trim());
    }
  }
  return null;
}

function readFirstDefinedEnv(keys, fallback, env = process.env) {
  for (const key of keys) {
    const value = env[key];
    if (typeof value === "string" && value.trim() !== "") {
      return value.trim();
    }
  }
  return fallback;
}

function parseBooleanEnv(value) {
  const normalized = String(value).trim().toLowerCase();
  return normalized !== "false" && normalized !== "0" && normalized !== "no";
}

function parseIntegerEnv(value, fallback) {
  const parsed = Number.parseInt(String(value), 10);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : fallback;
}

function extractErrorMessage(error) {
  return (
    error?.stderr?.toString("utf8")
    || error?.stdout?.toString("utf8")
    || error?.message
    || "unknown refresh error"
  ).trim();
}

function isDesktopUnavailableError(message) {
  const normalized = String(message).toLowerCase();
  return [
    "unable to find application named",
    "application isn’t running",
    "application isn't running",
    "can’t get application id",
    "can't get application id",
    "does not exist",
    "no application knows how to open",
    "cannot find app",
    "could not find application",
  ].some((snippet) => normalized.includes(snippet));
}

module.exports = {
  CodexDesktopRefresher,
  readBridgeConfig,
};
