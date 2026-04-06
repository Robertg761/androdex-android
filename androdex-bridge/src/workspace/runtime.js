// FILE: workspace/runtime.js
// Purpose: Owns workspace selection, persisted workspace state, and local Codex transport lifecycle.
// Layer: CLI helper
// Exports: createWorkspaceRuntime
// Depends on: fs, path, ../codex/transport, ../daemon-state

const fs = require("fs");
const path = require("path");
const { createCodexTransport } = require("../codex/transport");
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
  let codex = null;
  let currentCwd = normalizeWorkspacePath(config?.activeCwd || "");
  let recentWorkspaces = normalizeRecentWorkspaces(config?.recentWorkspaces);
  let activationQueue = Promise.resolve();
  let activationSequence = 0;

  return {
    activateWorkspace,
    getCurrentCwd,
    getStatus,
    getWorkspaceState,
    hasActiveWorkspace,
    restoreActiveWorkspace,
    sendToCodex,
    shutdown,
  };

  function getCurrentCwd() {
    return currentCwd || "";
  }

  function hasActiveWorkspace() {
    return Boolean(codex);
  }

  function getStatus() {
    return {
      currentCwd: currentCwd || null,
      workspaceActive: Boolean(codex),
    };
  }

  function getWorkspaceState() {
    return {
      activeCwd: currentCwd || "",
      recentWorkspaces: [...recentWorkspaces],
    };
  }

  function sendToCodex(message) {
    if (!codex) {
      throw new Error("No active Codex workspace on the host.");
    }

    return codex.send(message);
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

    if (currentCwd === cwd && codex) {
      return getStatus();
    }

    await shutdownTransport();
    currentCwd = cwd;
    rememberRecentWorkspace(cwd);
    onBeforeTransportStart?.({ cwd });

    const nextCodex = createCodexTransport({
      endpoint: config?.codexEndpoint,
      env: process.env,
      cwd,
    });
    codex = nextCodex;

    nextCodex.onError((error) => {
      if (codex !== nextCodex) {
        return;
      }
      codex = null;
      onTransportError?.({ error, currentCwd });
    });

    nextCodex.onMessage((message) => {
      if (codex !== nextCodex) {
        return;
      }
      onTransportMessage?.(message);
    });

    nextCodex.onClose(() => {
      if (codex !== nextCodex) {
        return;
      }
      codex = null;
      onTransportClose?.({ currentCwd });
    });

    return getStatus();
  }

  async function shutdown() {
    activationSequence += 1;
    await shutdownTransport();
  }

  async function shutdownTransport() {
    const activeCodex = codex;
    codex = null;
    await onBeforeTransportShutdown?.({ currentCwd });
    if (!activeCodex) {
      return;
    }

    try {
      activeCodex.shutdown();
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
