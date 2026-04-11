// FILE: workspace/runtime.js
// Purpose: Owns workspace selection, persisted workspace state, and active host-runtime lifecycle.
// Layer: CLI helper
// Exports: createWorkspaceRuntime
// Depends on: fs, path, ../runtime/adapter, ../daemon-state

const fs = require("fs");
const path = require("path");
const { createRuntimeAdapter } = require("../runtime/adapter");
const { readDaemonConfig, writeDaemonConfig } = require("../daemon-state");
const { resolveT3RuntimeEndpoint } = require("../runtime/t3-discovery");

const MAX_RECENT_WORKSPACES = 25;

function createWorkspaceRuntime({
  config,
  createRuntimeAdapterImpl = createRuntimeAdapter,
  readDaemonConfigImpl = readDaemonConfig,
  onBeforeTransportShutdown = null,
  onBeforeTransportStart = null,
  onTransportClose = null,
  onTransportError = null,
  onTransportLog = null,
  onTransportMetadata = null,
  onTransportMessage = null,
  writeDaemonConfigImpl = writeDaemonConfig,
} = {}) {
  let activeRuntime = null;
  let pendingRuntime = null;
  let currentRuntimeMetadata = null;
  let currentCwd = normalizeWorkspacePath(config?.activeCwd || "");
  let recentWorkspaces = normalizeRecentWorkspaces(config?.recentWorkspaces);
  let activationQueue = Promise.resolve();
  let activationSequence = 0;

  return {
    activateWorkspace,
    getCurrentCwd,
    getRuntimeMetadata,
    getRuntimeTarget,
    getStatus,
    getWorkspaceState,
    hasActiveWorkspace,
    restoreActiveWorkspace,
    sendToRuntime,
    sendToCodex,
    shutdown,
    updateRuntimeConfig,
  };

  function getCurrentCwd() {
    return currentCwd || "";
  }

  function hasActiveWorkspace() {
    return Boolean(activeRuntime);
  }

  function getRuntimeMetadata() {
    return currentRuntimeMetadata ? { ...currentRuntimeMetadata } : null;
  }

  function getRuntimeTarget() {
    return resolveConfiguredRuntimeTarget();
  }

  function getStatus() {
    return {
      currentCwd: currentCwd || null,
      runtimeMetadata: getRuntimeMetadata(),
      runtimeTarget: resolveConfiguredRuntimeTarget(),
      workspaceActive: Boolean(activeRuntime),
    };
  }

  function getWorkspaceState() {
    return {
      activeCwd: currentCwd || "",
      recentWorkspaces: [...recentWorkspaces],
    };
  }

  function sendToRuntime(message) {
    if (!activeRuntime) {
      throw new Error("No active host runtime for the selected workspace.");
    }

    return activeRuntime.send(message);
  }

  function sendToCodex(message) {
    return sendToRuntime(message);
  }

  async function restoreActiveWorkspace() {
    if (!currentCwd || !isExistingDirectory(currentCwd)) {
      currentCwd = "";
      return getStatus();
    }

    return activateWorkspace({ cwd: currentCwd });
  }

  async function activateWorkspace({ cwd = "", forceRestart = false } = {}) {
    const nextCwd = normalizeWorkspacePath(normalizeNonEmptyString(cwd) || process.cwd());
    if (!isExistingDirectory(nextCwd)) {
      throw new Error(`Workspace directory not found: ${nextCwd}`);
    }

    const activationId = ++activationSequence;
    const activation = activationQueue.then(() => performWorkspaceActivation({
      cwd: nextCwd,
      forceRestart,
      activationId,
    }));
    activationQueue = activation.then(
      () => undefined,
      () => undefined
    );
    return activation;
  }

  async function performWorkspaceActivation({ cwd, activationId, forceRestart = false }) {
    if (activationId !== activationSequence) {
      return getStatus();
    }

    if (!forceRestart && currentCwd === cwd && activeRuntime) {
      return getStatus();
    }

    await shutdownTransport();
    currentCwd = cwd;
    rememberRecentWorkspace(cwd);
    onBeforeTransportStart?.({
      cwd,
      runtimeTarget: resolveConfiguredRuntimeTarget(),
    });

    const runtimeTarget = resolveConfiguredRuntimeTarget();
    const nextRuntime = createRuntimeAdapterImpl({
      endpoint: resolveConfiguredRuntimeEndpoint(),
      endpointAuthToken: resolveConfiguredRuntimeEndpointAuthToken(),
      env: process.env,
      cwd,
      loadReplayCursor,
      logEvent: onTransportLog,
      persistReplayCursor,
      resolveEndpointConfig: runtimeTarget === "t3-server" ? resolveConfiguredT3Endpoint : null,
      targetKind: runtimeTarget,
    });
    pendingRuntime = nextRuntime;
    currentRuntimeMetadata = nextRuntime.getRuntimeMetadata?.() || null;
    publishTransportMetadata();

    nextRuntime.onError((error) => {
      if (!isCurrentRuntime(nextRuntime)) {
        return;
      }
      activeRuntime = null;
      pendingRuntime = null;
      currentRuntimeMetadata = nextRuntime.getRuntimeMetadata?.() || currentRuntimeMetadata;
      publishTransportMetadata();
      onTransportError?.({
        error,
        currentCwd,
        runtimeMetadata: getRuntimeMetadata(),
        runtimeTarget: resolveConfiguredRuntimeTarget(),
      });
    });

    nextRuntime.onMetadata?.((metadata) => {
      if (!isCurrentRuntime(nextRuntime)) {
        return;
      }
      currentRuntimeMetadata = metadata ? { ...metadata } : null;
      publishTransportMetadata();
    });

    nextRuntime.onMessage((message) => {
      if (!isCurrentRuntime(nextRuntime)) {
        return;
      }
      onTransportMessage?.(message);
    });

    nextRuntime.onClose(() => {
      if (!isCurrentRuntime(nextRuntime)) {
        return;
      }
      activeRuntime = null;
      pendingRuntime = null;
      currentRuntimeMetadata = nextRuntime.getRuntimeMetadata?.() || currentRuntimeMetadata;
      publishTransportMetadata();
      onTransportClose?.({
        currentCwd,
        runtimeMetadata: getRuntimeMetadata(),
        runtimeTarget: resolveConfiguredRuntimeTarget(),
      });
    });

    await nextRuntime.whenReady?.();
    if (activationId !== activationSequence || pendingRuntime !== nextRuntime) {
      nextRuntime.shutdown?.();
      return getStatus();
    }

    activeRuntime = nextRuntime;
    pendingRuntime = null;
    currentRuntimeMetadata = nextRuntime.getRuntimeMetadata?.() || currentRuntimeMetadata;
    publishTransportMetadata();
    return getStatus();
  }

  async function shutdown() {
    activationSequence += 1;
    await shutdownTransport();
  }

  async function updateRuntimeConfig(nextRuntimeConfig = {}) {
    const nextTarget = normalizeNonEmptyString(nextRuntimeConfig.runtimeTarget)
      || normalizeNonEmptyString(nextRuntimeConfig.runtimeProvider);
    if (!nextTarget) {
      throw new Error("Runtime target is required.");
    }

    config.runtimeTarget = nextTarget;
    config.runtimeProvider = normalizeNonEmptyString(nextRuntimeConfig.runtimeProvider)
      || (nextTarget === "t3-server" ? "t3code" : "codex");
    if (Object.prototype.hasOwnProperty.call(nextRuntimeConfig, "runtimeEndpoint")) {
      config.runtimeEndpoint = normalizeNonEmptyString(nextRuntimeConfig.runtimeEndpoint);
    }
    if (Object.prototype.hasOwnProperty.call(nextRuntimeConfig, "runtimeEndpointAuthToken")) {
      config.runtimeEndpointAuthToken = normalizeNonEmptyString(nextRuntimeConfig.runtimeEndpointAuthToken);
    }
    writeDaemonConfigImpl({
      ...(readDaemonConfigImpl() || {}),
      ...config,
    });

    if (currentCwd && isExistingDirectory(currentCwd)) {
      return activateWorkspace({
        cwd: currentCwd,
        forceRestart: true,
      });
    }

    await shutdownTransport();
    publishTransportMetadata();
    return getStatus();
  }

  async function shutdownTransport() {
    const runtime = activeRuntime || pendingRuntime;
    activeRuntime = null;
    pendingRuntime = null;
    currentRuntimeMetadata = null;
    await onBeforeTransportShutdown?.({
      currentCwd,
      runtimeMetadata: null,
      runtimeTarget: resolveConfiguredRuntimeTarget(),
    });
    publishTransportMetadata();
    if (!runtime) {
      return;
    }

    try {
      runtime.shutdown();
    } catch {
      // Ignore shutdown failures for stale transports.
    }
  }

  function rememberRecentWorkspace(cwd) {
    const normalized = normalizeWorkspacePath(cwd);
    recentWorkspaces = [
      normalized,
      ...recentWorkspaces.filter((candidate) => normalizeWorkspacePath(candidate) !== normalized),
    ].slice(0, MAX_RECENT_WORKSPACES);

    config.activeCwd = normalized;
    config.recentWorkspaces = [...recentWorkspaces];
    writeDaemonConfigImpl({
      ...(readDaemonConfigImpl() || {}),
      ...config,
      activeCwd: normalized,
      recentWorkspaces: [...recentWorkspaces],
    });
  }

  function loadReplayCursor({ runtimeTarget, runtimeStateRoot }) {
    const scopeKey = buildT3ReplayScopeKey({ runtimeTarget, runtimeStateRoot });
    if (!scopeKey) {
      return 0;
    }
    const persistedSequence = Number(config?.t3ReplayCursors?.[scopeKey]?.lastSequence);
    return Number.isFinite(persistedSequence) && persistedSequence >= 0
      ? Math.trunc(persistedSequence)
      : 0;
  }

  function persistReplayCursor({ runtimeTarget, runtimeStateRoot, sequence }) {
    const scopeKey = buildT3ReplayScopeKey({ runtimeTarget, runtimeStateRoot });
    const normalizedSequence = Number(sequence);
    if (!scopeKey || !Number.isFinite(normalizedSequence) || normalizedSequence < 0) {
      return;
    }

    const nextSequence = Math.trunc(normalizedSequence);
    const currentReplayCursors = {
      ...(config.t3ReplayCursors || {}),
    };
    if (Number(currentReplayCursors[scopeKey]?.lastSequence) === nextSequence) {
      return;
    }

    const nextReplayCursors = {
      ...currentReplayCursors,
      [scopeKey]: {
        lastSequence: nextSequence,
        updatedAt: new Date().toISOString(),
      },
    };
    config.t3ReplayCursors = nextReplayCursors;
    writeDaemonConfigImpl({
      ...(readDaemonConfigImpl() || {}),
      ...config,
      t3ReplayCursors: nextReplayCursors,
    });
  }

  function resolveConfiguredRuntimeTarget() {
    return normalizeNonEmptyString(config?.runtimeTarget)
      || normalizeNonEmptyString(config?.runtimeProvider)
      || "codex-native";
  }

  function resolveConfiguredRuntimeEndpoint() {
    if (resolveConfiguredRuntimeTarget() === "t3-server") {
      return resolveConfiguredT3Endpoint().endpoint;
    }

    return normalizeNonEmptyString(config?.runtimeEndpoint)
      || normalizeNonEmptyString(config?.codexEndpoint)
      || "";
  }

  function resolveConfiguredRuntimeEndpointAuthToken() {
    if (resolveConfiguredRuntimeTarget() === "t3-server") {
      return resolveConfiguredT3Endpoint().authToken;
    }

    return normalizeNonEmptyString(config?.runtimeEndpointAuthToken)
      || "";
  }

  function resolveConfiguredT3Endpoint() {
    const explicitEndpoint = normalizeNonEmptyString(config?.runtimeEndpoint);
    if (explicitEndpoint) {
      return {
        endpoint: explicitEndpoint,
        authToken: normalizeNonEmptyString(config?.runtimeEndpointAuthToken),
      };
    }

    const discoveredEndpoint = resolveT3RuntimeEndpoint({
      env: process.env,
    });
    return {
      endpoint: normalizeNonEmptyString(discoveredEndpoint?.endpoint),
      authToken: normalizeNonEmptyString(discoveredEndpoint?.authToken),
    };
  }

  function isCurrentRuntime(runtime) {
    return activeRuntime === runtime || pendingRuntime === runtime;
  }

  function publishTransportMetadata() {
    onTransportMetadata?.({
      currentCwd,
      runtimeMetadata: getRuntimeMetadata(),
      runtimeTarget: resolveConfiguredRuntimeTarget(),
      workspaceActive: Boolean(activeRuntime),
    });
  }
}

function normalizeRecentWorkspaces(value) {
  if (!Array.isArray(value)) {
    return [];
  }

  const normalized = [];
  const seen = new Set();
  for (const candidate of value) {
    const trimmed = normalizeWorkspacePath(candidate);
    if (!trimmed || seen.has(trimmed)) {
      continue;
    }
    seen.add(trimmed);
    normalized.push(trimmed);
    if (normalized.length >= MAX_RECENT_WORKSPACES) {
      break;
    }
  }
  return normalized;
}

function normalizeNonEmptyString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

function normalizeWorkspacePath(value) {
  const trimmed = normalizeNonEmptyString(value);
  return trimmed ? path.normalize(trimmed) : "";
}

function buildT3ReplayScopeKey({ runtimeTarget, runtimeStateRoot }) {
  const normalizedTarget = normalizeNonEmptyString(runtimeTarget);
  const normalizedStateRoot = normalizeWorkspacePath(runtimeStateRoot);
  if (!normalizedTarget || !normalizedStateRoot) {
    return "";
  }
  return `${normalizedTarget}::${normalizedStateRoot}`;
}

function isExistingDirectory(targetPath) {
  try {
    return fs.statSync(targetPath).isDirectory();
  } catch {
    return false;
  }
}

module.exports = {
  createWorkspaceRuntime,
};
