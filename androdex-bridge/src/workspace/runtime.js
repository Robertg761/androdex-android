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
  onTransportMessage = null,
} = {}) {
  let activeRuntime = null;
  let currentCwd = normalizeWorkspacePath(config?.activeCwd || "");
  let recentWorkspaces = normalizeRecentWorkspaces(config?.recentWorkspaces);
  let activationQueue = Promise.resolve();
  let activationSequence = 0;

  return {
    activateWorkspace,
    getCurrentCwd,
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

  function getRuntimeTarget() {
    return resolveConfiguredRuntimeTarget();
  }

  function getStatus() {
    return {
      currentCwd: currentCwd || null,
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
      endpoint: config?.codexEndpoint,
      env: process.env,
      cwd,
      targetKind: resolveConfiguredRuntimeTarget(),
    });
    activeRuntime = nextRuntime;

    nextRuntime.onError((error) => {
      if (activeRuntime !== nextRuntime) {
        return;
      }
      activeRuntime = null;
      onTransportError?.({
        error,
        currentCwd,
        runtimeTarget: resolveConfiguredRuntimeTarget(),
      });
    });

    nextRuntime.onMessage((message) => {
      if (activeRuntime !== nextRuntime) {
        return;
      }
      onTransportMessage?.(message);
    });

    nextRuntime.onClose(() => {
      if (activeRuntime !== nextRuntime) {
        return;
      }
      activeRuntime = null;
      onTransportClose?.({
        currentCwd,
        runtimeTarget: resolveConfiguredRuntimeTarget(),
      });
    });

    return getStatus();
  }

  async function shutdown() {
    activationSequence += 1;
    await shutdownTransport();
  }

  async function shutdownTransport() {
    const runtime = activeRuntime;
    activeRuntime = null;
    await onBeforeTransportShutdown?.({
      currentCwd,
      runtimeTarget: resolveConfiguredRuntimeTarget(),
    });
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

  function resolveConfiguredRuntimeTarget() {
    return normalizeNonEmptyString(config?.runtimeTarget)
      || normalizeNonEmptyString(config?.runtimeProvider)
      || "codex-native";
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
