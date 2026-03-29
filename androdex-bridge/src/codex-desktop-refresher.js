// FILE: codex-desktop-refresher.js
// Purpose: Debounced Mac desktop refresh controller for Codex.app after phone-authored conversation changes.
// Layer: CLI helper
// Exports: CodexDesktopRefresher, readBridgeConfig
// Depends on: child_process, path, ./codex-desktop-launcher, ./rollout-watch

const { execFile } = require("child_process");
const path = require("path");
const { openCodexDesktopTarget } = require("./codex-desktop-launcher");
const { continueOnMacDesktop } = require("./codex-desktop-macos-handoff");
const {
  DEFAULT_WINDOWS_REMOTE_DEBUGGING_PORT,
  navigateCodexDesktopRendererToRouteViaTrustedRenderer,
  resolveWindowsRemoteDebuggingPort,
  restartCodexDesktopAppServerViaTrustedRenderer,
} = require("./codex-desktop-windows-devtools");
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
const DEFAULT_APPLESCRIPT_TARGET_RETRY_DELAY_MS = 220;
const DEFAULT_WINDOWS_THREAD_BOUNCE_DELAY_MS = 120;
const DEFAULT_WINDOWS_TRUSTED_RESTART_WAIT_MS = 800;
const DEFAULT_WINDOWS_TRUSTED_REOPEN_RETRY_DELAY_MS = 700;
const DEFAULT_WINDOWS_HARD_RESTART_COOLDOWN_MS = 8_000;
const REFRESH_SCRIPT_PATH = path.join(__dirname, "scripts", "codex-refresh.applescript");
const WINDOWS_RESTART_SCRIPT_PATH = path.join(__dirname, "scripts", "codex-relaunch-windows.ps1");
const SETTINGS_DEEP_LINK = "codex://settings";
const NEW_THREAD_DEEP_LINK = "codex://threads/new";

class CodexDesktopRefresher {
  constructor({
    enabled = true,
    debounceMs = DEFAULT_DEBOUNCE_MS,
    refreshCommand = "",
    bundleId = DEFAULT_BUNDLE_ID,
    appPath = DEFAULT_APP_PATH,
    platformAdapter = null,
    platform = process.platform,
    logPrefix = "[androdex]",
    fallbackNewThreadMs = DEFAULT_FALLBACK_NEW_THREAD_MS,
    midRunRefreshThrottleMs = DEFAULT_MID_RUN_REFRESH_THROTTLE_MS,
    rolloutLookupTimeoutMs = DEFAULT_ROLLOUT_LOOKUP_TIMEOUT_MS,
    rolloutIdleTimeoutMs = DEFAULT_ROLLOUT_IDLE_TIMEOUT_MS,
    now = () => Date.now(),
    refreshExecutor = null,
    macCompletionRefreshExecutor = null,
    watchThreadRolloutFactory = createThreadRolloutActivityWatcher,
    protocolRefreshExecutor = openCodexDesktopTarget,
    hardRefreshExecutor = null,
    windowsTrustedRestartExecutor = restartCodexDesktopAppServerViaTrustedRenderer,
    windowsTrustedRendererNavigationExecutor = navigateCodexDesktopRendererToRouteViaTrustedRenderer,
    sleepFn = waitForDelay,
    threadStateSyncExecutor = null,
    refreshBackend = null,
    customRefreshFailureThreshold = DEFAULT_CUSTOM_REFRESH_FAILURE_THRESHOLD,
    applescriptTargetRetryDelayMs = DEFAULT_APPLESCRIPT_TARGET_RETRY_DELAY_MS,
    windowsRemoteDebuggingPort = DEFAULT_WINDOWS_REMOTE_DEBUGGING_PORT,
    windowsThreadBounceDelayMs = DEFAULT_WINDOWS_THREAD_BOUNCE_DELAY_MS,
    windowsTrustedRestartWaitMs = DEFAULT_WINDOWS_TRUSTED_RESTART_WAIT_MS,
    windowsTrustedReopenRetryDelayMs = DEFAULT_WINDOWS_TRUSTED_REOPEN_RETRY_DELAY_MS,
    windowsHardRestartCooldownMs = DEFAULT_WINDOWS_HARD_RESTART_COOLDOWN_MS,
  } = {}) {
    this.enabled = enabled;
    this.debounceMs = debounceMs;
    this.refreshCommand = refreshCommand;
    this.bundleId = bundleId;
    this.appPath = appPath;
    this.platformAdapter = platformAdapter;
    this.platform = platform;
    this.logPrefix = logPrefix;
    this.fallbackNewThreadMs = fallbackNewThreadMs;
    this.midRunRefreshThrottleMs = midRunRefreshThrottleMs;
    this.rolloutLookupTimeoutMs = rolloutLookupTimeoutMs;
    this.rolloutIdleTimeoutMs = rolloutIdleTimeoutMs;
    this.now = now;
    this.refreshExecutor = refreshExecutor;
    this.macCompletionRefreshExecutor = macCompletionRefreshExecutor;
    this.watchThreadRolloutFactory = watchThreadRolloutFactory;
    this.protocolRefreshExecutor = protocolRefreshExecutor;
    this.hardRefreshExecutor = hardRefreshExecutor;
    this.windowsTrustedRestartExecutor = windowsTrustedRestartExecutor;
    this.windowsTrustedRendererNavigationExecutor = windowsTrustedRendererNavigationExecutor;
    this.sleepFn = sleepFn;
    this.threadStateSyncExecutor = threadStateSyncExecutor;
    this.refreshBackend = refreshBackend
      || (this.refreshCommand
        ? "command"
        : (this.refreshExecutor
          ? "command"
          : (this.platform === "darwin" ? "applescript" : "protocol")));
    this.customRefreshFailureThreshold = customRefreshFailureThreshold;
    this.applescriptTargetRetryDelayMs = applescriptTargetRetryDelayMs;
    this.windowsRemoteDebuggingPort = resolveWindowsRemoteDebuggingPort(windowsRemoteDebuggingPort);
    this.windowsThreadBounceDelayMs = windowsThreadBounceDelayMs;
    this.windowsTrustedRestartWaitMs = windowsTrustedRestartWaitMs;
    this.windowsTrustedReopenRetryDelayMs = windowsTrustedReopenRetryDelayMs;
    this.windowsHardRestartCooldownMs = windowsHardRestartCooldownMs;

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
    this.lastWindowsHardRestartAt = 0;
    this.lastWindowsHardRestartTargetUrl = "";
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

    if (method === "turn/start" || method === "turn/steer") {
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

  // Stops volatile watcher/fallback state when transport drops or bridge exits.
  handleTransportReset() {
    this.clearRefreshTimer();
    this.clearPendingState();
    this.lastRefreshAt = 0;
    this.lastRefreshSignature = "";
    this.lastWindowsHardRestartAt = 0;
    this.lastWindowsHardRestartTargetUrl = "";
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
  }

  clearPendingTarget() {
    this.pendingTargetUrl = "";
    this.pendingTargetThreadId = "";
  }

  noteCompletionTarget(target) {
    if (!target?.url) {
      return;
    }

    this.pendingCompletionTargetUrl = target.url;
    this.pendingCompletionTargetThreadId = target.threadId || "";
  }

  clearPendingCompletionTarget() {
    this.pendingCompletionTargetUrl = "";
    this.pendingCompletionTargetThreadId = "";
  }

  scheduleRefresh(reason) {
    if (!this.canRefresh()) {
      return;
    }

    if (this.refreshTimer) {
      this.log(`refresh already pending: ${reason}`);
      return;
    }

    const elapsedSinceLastRefresh = this.now() - this.lastRefreshAt;
    const waitMs = Math.max(0, this.debounceMs - elapsedSinceLastRefresh);
    this.log(`refresh scheduled: ${reason}`);
    this.refreshTimer = setTimeout(() => {
      this.refreshTimer = null;
      void this.runPendingRefresh();
    }, waitMs);
  }

  async runPendingRefresh() {
    if (!this.canRefresh()) {
      this.clearPendingState();
      return;
    }

    if (!this.hasPendingRefreshWork()) {
      return;
    }

    if (this.refreshRunning) {
      this.log("refresh skipped (debounced): another refresh is already running");
      return;
    }

    const isCompletionRun = this.pendingCompletionRefresh;
    const pendingRefreshKinds = isCompletionRun
      ? new Set(["completion"])
      : new Set(this.pendingRefreshKinds);
    const completionTurnId = this.pendingCompletionTurnId;
    const targetUrl = isCompletionRun ? this.pendingCompletionTargetUrl : this.pendingTargetUrl;
    const targetThreadId = isCompletionRun
      ? this.pendingCompletionTargetThreadId
      : this.pendingTargetThreadId;
    const stopWatcherAfterRefreshThreadId = isCompletionRun
      ? this.stopWatcherAfterRefreshThreadId
      : null;
    const shouldForceCompletionRefresh = isCompletionRun;

    if (isCompletionRun) {
      this.pendingCompletionRefresh = false;
      this.pendingCompletionTurnId = null;
      this.clearPendingCompletionTarget();
      this.stopWatcherAfterRefreshThreadId = null;
    } else {
      this.pendingRefreshKinds.clear();
      this.clearPendingTarget();
    }
    this.refreshRunning = true;
    this.log(
      `refresh running: ${Array.from(pendingRefreshKinds).join("+")}${targetThreadId ? ` thread=${targetThreadId}` : ""}`
    );

    let didRefresh = false;
    try {
      const refreshSignature = `${targetUrl || "app"}|${targetThreadId || "no-thread"}`;
      if (
        !shouldForceCompletionRefresh
        && refreshSignature === this.lastRefreshSignature
        && this.now() - this.lastRefreshAt < this.debounceMs
      ) {
        this.log(`refresh skipped (duplicate target): ${refreshSignature}`);
      } else {
        if (!this.usesRemodexMacRefreshPath()) {
          await this.syncThreadState(targetThreadId, {
            targetUrl,
            refreshKinds: pendingRefreshKinds,
            isCompletionRun,
          });
        }
        await this.executeRefresh(targetUrl, {
          refreshKinds: pendingRefreshKinds,
          isCompletionRun,
        });
        this.lastRefreshAt = this.now();
        this.lastRefreshSignature = refreshSignature;
        this.consecutiveRefreshFailures = 0;
        didRefresh = true;
      }
      if (completionTurnId && didRefresh) {
        this.lastTurnIdRefreshed = completionTurnId;
      }
    } catch (error) {
      this.handleRefreshFailure(error);
    } finally {
      this.refreshRunning = false;
      if (
        didRefresh
        && stopWatcherAfterRefreshThreadId
        && stopWatcherAfterRefreshThreadId === this.activeWatchedThreadId
      ) {
        this.stopWatcher();
        this.mode = this.pendingNewThread ? "pending_new_thread" : "idle";
      }
      // A completion refresh can queue while another refresh is still running,
      // so retry whenever either queue still has work.
      if (this.hasPendingRefreshWork()) {
        this.scheduleRefresh("pending follow-up refresh");
      }
    }
  }

  usesRemodexMacRefreshPath() {
    return this.platform === "darwin" && this.refreshBackend === "applescript";
  }

  async syncThreadState(threadId, options = {}) {
    if (!threadId || typeof this.threadStateSyncExecutor !== "function") {
      return;
    }

    try {
      await this.threadStateSyncExecutor({
        threadId,
        targetUrl: options.targetUrl || "",
        refreshKinds: options.refreshKinds || new Set(),
        isCompletionRun: Boolean(options.isCompletionRun),
      });
    } catch (error) {
      this.log(`thread state sync failed thread=${threadId}: ${extractErrorMessage(error)}`);
    }
  }

  async executeRefresh(targetUrl, options = {}) {
    const refreshTarget = targetUrl || "";
    const useRemodexMacRefreshPath = this.usesRemodexMacRefreshPath();
    const isMacCompletionRefresh = useRemodexMacRefreshPath
      && options.isCompletionRun
      && isConcreteThreadDeepLink(refreshTarget);

    if (isMacCompletionRefresh) {
      await this.executeMacCompletionRefresh(refreshTarget);
      return;
    }

    if (this.refreshExecutor) {
      await this.refreshExecutor(refreshTarget);
      if (!useRemodexMacRefreshPath) {
        await this.retryAppleScriptThreadOpen(refreshTarget);
      }
      return;
    }

    if (this.refreshCommand) {
      if (this.platform === "win32") {
        await execFilePromise("cmd.exe", ["/d", "/c", this.refreshCommand], {
          windowsHide: true,
        });
        return;
      }

      await execFilePromise("/bin/sh", ["-lc", this.refreshCommand]);
      return;
    }

    if (useRemodexMacRefreshPath && !options.isCompletionRun) {
      await this.protocolRefreshExecutor({
        targetUrl: refreshTarget,
        bundleId: this.bundleId,
        appPath: this.appPath,
        platformAdapter: this.platformAdapter,
        platform: this.platform,
        windowsRemoteDebuggingPort: this.windowsRemoteDebuggingPort,
      });
      return;
    }

    if (this.refreshBackend === "applescript") {
      await execFilePromise("osascript", [
        REFRESH_SCRIPT_PATH,
        this.bundleId,
        this.appPath,
        refreshTarget,
      ]);
      if (!useRemodexMacRefreshPath) {
        await this.retryAppleScriptThreadOpen(refreshTarget);
      }
      return;
    }

    if (this.refreshBackend === "protocol") {
      await this.executeProtocolRefresh(refreshTarget, options);
      return;
    }

    await openCodexDesktopTarget({
      targetUrl: refreshTarget,
      bundleId: this.bundleId,
      appPath: this.appPath,
      platformAdapter: this.platformAdapter,
      platform: this.platform,
      windowsRemoteDebuggingPort: this.windowsRemoteDebuggingPort,
    });
  }

  async executeMacCompletionRefresh(targetUrl) {
    if (typeof this.macCompletionRefreshExecutor === "function") {
      await this.macCompletionRefreshExecutor({
        targetUrl,
        bundleId: this.bundleId,
        appPath: this.appPath,
      });
      return;
    }

    await continueOnMacDesktop(extractThreadIdFromTargetUrl(targetUrl), {
      bundleId: this.bundleId,
      appPath: this.appPath,
      sleepFn: this.sleepFn,
      executor: execFilePromise,
    });
  }

  async retryAppleScriptThreadOpen(targetUrl) {
    if (this.refreshBackend !== "applescript" || !isConcreteThreadDeepLink(targetUrl)) {
      return;
    }

    // Newer Codex desktop builds occasionally coalesce the first thread deep
    // link after the Remodex-style settings bounce, so reopen the target once.
    await this.sleepFn(this.applescriptTargetRetryDelayMs);
    await this.protocolRefreshExecutor({
      targetUrl,
      bundleId: this.bundleId,
      appPath: this.appPath,
      platformAdapter: this.platformAdapter,
      platform: this.platform,
      windowsRemoteDebuggingPort: this.windowsRemoteDebuggingPort,
    });
  }

  async executeProtocolRefresh(targetUrl, {
    refreshKinds = new Set(),
    isCompletionRun = false,
  } = {}) {
    const refreshTarget = targetUrl || "";
    if (this.shouldUseWindowsTrustedRestart(refreshTarget)) {
      const didTrustedRestart = await this.tryWindowsTrustedRestart(refreshTarget);
      if (didTrustedRestart) {
        return;
      }
    }

    if (this.shouldHardRestartThreadRefresh({
      targetUrl: refreshTarget,
      refreshKinds,
      isCompletionRun,
    })) {
      return this.executeWindowsHardRestart(refreshTarget);
    }

    if (this.shouldBounceThreadRefresh(refreshTarget)) {
      await this.protocolRefreshExecutor({
        targetUrl: SETTINGS_DEEP_LINK,
        bundleId: this.bundleId,
        appPath: this.appPath,
        platformAdapter: this.platformAdapter,
        platform: this.platform,
        windowsRemoteDebuggingPort: this.windowsRemoteDebuggingPort,
      });
      await this.sleepFn(this.windowsThreadBounceDelayMs);
    }

    return this.protocolRefreshExecutor({
      targetUrl,
      bundleId: this.bundleId,
      appPath: this.appPath,
      platformAdapter: this.platformAdapter,
      platform: this.platform,
      windowsRemoteDebuggingPort: this.windowsRemoteDebuggingPort,
    });
  }

  executeWindowsHardRestart(targetUrl) {
    const refreshTarget = targetUrl || "";
    const executor = this.hardRefreshExecutor || ((payload) => execFilePromise(
      "powershell.exe",
      [
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        WINDOWS_RESTART_SCRIPT_PATH,
        "-TargetUrl",
        payload.targetUrl || "",
        "-RemoteDebuggingPort",
        String(payload.windowsRemoteDebuggingPort || this.windowsRemoteDebuggingPort),
      ],
      {
        stdio: "ignore",
        windowsHide: true,
      }
    ));

    return Promise.resolve(executor({
      targetUrl: refreshTarget,
      bundleId: this.bundleId,
      appPath: this.appPath,
      platform: this.platform,
      windowsRemoteDebuggingPort: this.windowsRemoteDebuggingPort,
    })).then((result) => {
      this.lastWindowsHardRestartAt = this.now();
      this.lastWindowsHardRestartTargetUrl = refreshTarget;
      return result;
    });
  }

  async tryWindowsTrustedRestart(targetUrl) {
    if (typeof this.windowsTrustedRestartExecutor !== "function") {
      return false;
    }

    try {
      const usedTrustedNavigation = typeof this.windowsTrustedRendererNavigationExecutor === "function";
      if (usedTrustedNavigation) {
        await this.windowsTrustedRendererNavigationExecutor({
          port: this.windowsRemoteDebuggingPort,
          path: "/settings",
        });
        await this.sleepFn(this.windowsThreadBounceDelayMs);
      }

      await this.windowsTrustedRestartExecutor({
        port: this.windowsRemoteDebuggingPort,
      });
      await this.sleepFn(this.windowsTrustedRestartWaitMs);

      await this.protocolRefreshExecutor({
        targetUrl,
        bundleId: this.bundleId,
        appPath: this.appPath,
        platformAdapter: this.platformAdapter,
        platform: this.platform,
        windowsRemoteDebuggingPort: this.windowsRemoteDebuggingPort,
      });
      if (usedTrustedNavigation) {
        await this.sleepFn(this.windowsTrustedReopenRetryDelayMs);
        await this.protocolRefreshExecutor({
          targetUrl,
          bundleId: this.bundleId,
          appPath: this.appPath,
          platformAdapter: this.platformAdapter,
          platform: this.platform,
          windowsRemoteDebuggingPort: this.windowsRemoteDebuggingPort,
        });
      }
      this.log(`windows trusted settings navigation and restart dispatched for ${targetUrl}`);
      return true;
    } catch (error) {
      const errorCode = typeof error?.code === "string" ? error.code : "unknown";
      this.log(
        `windows trusted restart unavailable (${errorCode}): ${extractErrorMessage(error)}`
      );
      return false;
    }
  }

  shouldUseWindowsTrustedRestart(targetUrl) {
    return this.refreshBackend === "protocol"
      && this.platform === "win32"
      && isConcreteThreadDeepLink(targetUrl)
      && this.lastRefreshSignature.split("|")[0] === targetUrl;
  }

  shouldHardRestartThreadRefresh({
    targetUrl,
    refreshKinds = new Set(),
    isCompletionRun = false,
  } = {}) {
    // Windows Codex deep links already support a dedicated settings route, so
    // we prefer a lightweight route remount over terminating the desktop app.
    // Keep the heavier relaunch machinery available as an explicit fallback,
    // but do not force it by default for same-thread refreshes.
    void targetUrl;
    void refreshKinds;
    void isCompletionRun;
    return false;
  }

  shouldBounceThreadRefresh(targetUrl) {
    return this.shouldUseWindowsTrustedRestart(targetUrl);
  }

  clearPendingState() {
    this.pendingNewThread = false;
    this.pendingRefreshKinds.clear();
    this.pendingCompletionRefresh = false;
    this.pendingCompletionTurnId = null;
    this.clearPendingCompletionTarget();
    this.clearPendingTarget();
    this.stopWatcherAfterRefreshThreadId = null;
  }

  clearRefreshTimer() {
    if (!this.refreshTimer) {
      return;
    }

    clearTimeout(this.refreshTimer);
    this.refreshTimer = null;
  }

  // Schedules a single low-cost fallback when a brand new thread id is still unknown.
  scheduleNewThreadFallback() {
    if (!this.canRefresh()) {
      return;
    }

    if (this.fallbackTimer) {
      return;
    }

    this.fallbackTimer = setTimeout(() => {
      this.fallbackTimer = null;
      if (!this.pendingNewThread || this.pendingTargetThreadId) {
        return;
      }

      this.noteRefreshTarget({ threadId: null, url: NEW_THREAD_DEEP_LINK });
      this.pendingRefreshKinds.add("phone");
      this.scheduleRefresh("fallback thread/start");
    }, this.fallbackNewThreadMs);
  }

  clearFallbackTimer() {
    if (!this.fallbackTimer) {
      return;
    }

    clearTimeout(this.fallbackTimer);
    this.fallbackTimer = null;
  }

  // Keeps one lightweight rollout watcher alive for the current Androdex-controlled thread.
  ensureWatcher(threadId) {
    if (!this.canRefresh() || !threadId) {
      return;
    }

    if (this.activeWatchedThreadId === threadId && this.activeWatcher) {
      return;
    }

    this.stopWatcher();
    this.activeWatchedThreadId = threadId;
    this.watchStartAt = this.now();
    this.lastRolloutSize = null;
    this.mode = "watching_thread";
    this.activeWatcher = this.watchThreadRolloutFactory({
      threadId,
      lookupTimeoutMs: this.rolloutLookupTimeoutMs,
      idleTimeoutMs: this.rolloutIdleTimeoutMs,
      onEvent: (event) => this.handleWatcherEvent(event),
      onIdle: () => {
        this.log(`rollout watcher idle thread=${threadId}`);
        this.stopWatcher();
        this.mode = this.pendingNewThread ? "pending_new_thread" : "idle";
      },
      onTimeout: () => {
        this.log(`rollout watcher timeout thread=${threadId}`);
        this.stopWatcher();
        this.mode = this.pendingNewThread ? "pending_new_thread" : "idle";
      },
      onError: (error) => {
        this.log(`rollout watcher failed thread=${threadId}: ${error.message}`);
        this.stopWatcher();
        this.mode = this.pendingNewThread ? "pending_new_thread" : "idle";
      },
    });
  }

  stopWatcher() {
    if (!this.activeWatcher) {
      this.activeWatchedThreadId = null;
      this.watchStartAt = 0;
      this.lastRolloutSize = null;
      return;
    }

    this.activeWatcher.stop();
    this.activeWatcher = null;
    this.activeWatchedThreadId = null;
    this.watchStartAt = 0;
    this.lastRolloutSize = null;
  }

  // Converts rollout growth into occasional refreshes without spamming the desktop.
  handleWatcherEvent(event) {
    if (!event?.threadId || event.threadId !== this.activeWatchedThreadId) {
      return;
    }

    const previousSize = this.lastRolloutSize;
    this.lastRolloutSize = event.size;
    this.noteRefreshTarget({
      threadId: event.threadId,
      url: buildThreadDeepLink(event.threadId),
    });

    if (this.usesRemodexMacRefreshPath()) {
      return;
    }

    if (event.reason === "materialized") {
      this.queueRefresh("rollout_materialized", {
        threadId: event.threadId,
        url: buildThreadDeepLink(event.threadId),
      }, `rollout ${event.reason}`);
      return;
    }

    if (event.reason !== "growth") {
      return;
    }

    if (previousSize == null) {
      this.queueRefresh("rollout_growth", {
        threadId: event.threadId,
        url: buildThreadDeepLink(event.threadId),
      }, "rollout first-growth");
      this.lastMidRunRefreshAt = this.now();
      return;
    }

    if (this.now() - this.lastMidRunRefreshAt < this.midRunRefreshThrottleMs) {
      return;
    }

    this.lastMidRunRefreshAt = this.now();
    this.queueRefresh("rollout_growth", {
      threadId: event.threadId,
      url: buildThreadDeepLink(event.threadId),
    }, "rollout mid-run");
  }

  log(message) {
    console.log(`${this.logPrefix} ${message}`);
  }

  handleRefreshFailure(error) {
    const message = extractErrorMessage(error);
    console.error(`${this.logPrefix} refresh failed: ${message}`);

    if (this.refreshBackend === "applescript" && isDesktopUnavailableError(message)) {
      this.disableRuntimeRefresh("desktop refresh unavailable on this Mac");
      return;
    }

    if (this.refreshBackend === "command") {
      this.consecutiveRefreshFailures += 1;
      if (this.consecutiveRefreshFailures >= this.customRefreshFailureThreshold) {
        this.disableRuntimeRefresh("custom refresh command kept failing");
      }
      return;
    }

    if (this.refreshBackend === "protocol") {
      this.disableRuntimeRefresh("desktop protocol refresh is unavailable on this host");
    }
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

  // Tells the debounce loop whether any phone/completion refresh is still waiting to run.
  hasPendingRefreshWork() {
    return this.pendingCompletionRefresh || this.pendingRefreshKinds.size > 0;
  }
}

function readBridgeConfig({ env = process.env, platform = process.platform } = {}) {
  const codexEndpoint = readFirstDefinedEnv(["ANDRODEX_CODEX_ENDPOINT"], "", env);
  const refreshCommand = readFirstDefinedEnv(["ANDRODEX_REFRESH_COMMAND"], "", env);
  const explicitRefreshEnabled = readOptionalBooleanEnv(["ANDRODEX_REFRESH_ENABLED"], env);
  const relayUrl = readFirstDefinedEnv(
    ["ANDRODEX_RELAY", "ANDRODEX_DEFAULT_RELAY_URL"],
    PUBLIC_DEFAULT_RELAY_URL,
    env
  );
  // Windows and macOS both need an explicit desktop refresh/remount path when
  // phone-authored activity should become visible in Codex.app.
  const defaultRefreshEnabled = platform === "win32" || platform === "darwin";
  return {
    relayUrl,
    refreshEnabled: explicitRefreshEnabled == null
      ? defaultRefreshEnabled
      : explicitRefreshEnabled,
    refreshDebounceMs: parseIntegerEnv(
      readFirstDefinedEnv(["ANDRODEX_REFRESH_DEBOUNCE_MS"], String(DEFAULT_DEBOUNCE_MS), env),
      DEFAULT_DEBOUNCE_MS
    ),
    codexEndpoint,
    refreshCommand,
    codexBundleId: readFirstDefinedEnv(
      ["ANDRODEX_CODEX_BUNDLE_ID"],
      DEFAULT_BUNDLE_ID,
      env
    ),
    codexAppPath: DEFAULT_APP_PATH,
    codexWindowsRemoteDebuggingPort: resolveWindowsRemoteDebuggingPort(
      readFirstDefinedEnv(
        ["ANDRODEX_CODEX_REMOTE_DEBUGGING_PORT"],
        String(DEFAULT_WINDOWS_REMOTE_DEBUGGING_PORT),
        env
      )
    ),
    pushServiceUrl: readFirstDefinedEnv(
      ["ANDRODEX_PUSH_SERVICE_URL"],
      "",
      env
    ),
    pushPreviewMaxChars: parseIntegerEnv(
      readFirstDefinedEnv(["ANDRODEX_PUSH_PREVIEW_MAX_CHARS"], "160", env),
      160
    ),
  };
}

function execFilePromise(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    execFile(command, args, options, (error, stdout, stderr) => {
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

function isConcreteThreadDeepLink(targetUrl) {
  return typeof targetUrl === "string"
    && targetUrl.startsWith("codex://threads/")
    && targetUrl !== NEW_THREAD_DEEP_LINK;
}

function extractThreadIdFromTargetUrl(targetUrl) {
  if (!isConcreteThreadDeepLink(targetUrl)) {
    return "";
  }

  return String(targetUrl).slice("codex://threads/".length).trim();
}

function waitForDelay(delayMs) {
  return new Promise((resolve) => {
    setTimeout(resolve, delayMs);
  });
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
