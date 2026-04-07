// FILE: workspace/runtime.js
// Purpose: Owns workspace selection, persisted workspace state, and active host-runtime lifecycle.
// Layer: CLI helper
// Exports: createWorkspaceRuntime
// Depends on: fs, path, ../runtime/adapter, ../daemon-state

const fs = require("fs");
const path = require("path");
const { createRuntimeAdapter } = require("../runtime/adapter");
const { readDaemonConfig, writeDaemonConfig } = require("../daemon-state");

const MAX_RECENT_WORKSPACES = 25;

function createWorkspaceRuntime({
  config,
  onBeforeTransportShutdown = null,
  onBeforeTransportStart = null,
  onTransportClose = null,
  onTransportError = null,
  onTransportMetadata = null,
  onTransportMessage = null,
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

  async function activateWorkspace({ cwd = "" } = {}) {
    const nextCwd = normalizeWorkspacePath(normalizeNonEmptyString(cwd) || process.cwd());
    if (!isExistingDirectory(nextCwd)) {
      throw new Error(`Workspace directory not found: ${nextCwd}`);
    }

    const activationId = ++activationSequence;
    const activation = activationQueue.then(() => performWorkspaceActivation({
      cwd: nextCwd,
      activationId,
    }));
    activationQueue = activation.then(
      () => undefined,
      () => undefined
    );
    return activation;
  }

  async function performWorkspaceActivation({ cwd, activationId }) {
    if (activationId !== activationSequence) {
      return getStatus();
    }

    if (currentCwd === cwd && activeRuntime) {
      return getStatus();
    }

    await shutdownTransport();
    currentCwd = cwd;
    rememberRecentWorkspace(cwd);
    onBeforeTransportStart?.({
      cwd,
      runtimeTarget: resolveConfiguredRuntimeTarget(),
    });

    const nextRuntime = createRuntimeAdapter({
      endpoint: resolveConfiguredRuntimeEndpoint(),
      env: process.env,
      cwd,
      loadReplayCursor,
      persistReplayCursor,
      targetKind: resolveConfiguredRuntimeTarget(),
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
    writeDaemonConfig({
      ...(readDaemonConfig() || {}),
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
    writeDaemonConfig({
      ...(readDaemonConfig() || {}),
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
    return normalizeNonEmptyString(config?.runtimeEndpoint)
      || normalizeNonEmptyString(config?.codexEndpoint)
      || "";
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
