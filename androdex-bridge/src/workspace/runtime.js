// FILE: workspace/runtime.js
// Purpose: Owns workspace selection, persisted workspace state, and active host-runtime lifecycle.
// Layer: CLI helper
// Exports: createWorkspaceRuntime
// Depends on: fs, path, ../runtime/adapter, ../daemon-state

const fs = require("fs");
const path = require("path");
const { createRuntimeAdapter } = require("../runtime/adapter");
const { readDaemonConfig, writeDaemonConfig } = require("../daemon-state");
const { detectInstalledT3Runtime, resolveT3RuntimeEndpoint } = require("../runtime/t3-discovery");
const { buildRuntimeTargetOptions } = require("../runtime/runtime-target-options");
const { resolveRuntimeTargetConfig } = require("../runtime/target-config");

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
    getRuntimeTargetOptions,
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
    return buildStatus();
  }

  async function getRuntimeTargetOptions({
    runtimeConfig = config,
    probeTimeoutMs = 500,
  } = {}) {
    return buildRuntimeTargetOptions({
      currentRuntimeTarget: resolveConfiguredRuntimeTarget(runtimeConfig),
      installedT3Runtime: detectInstalledT3Runtime({ env: process.env }),
      probeTimeoutMs,
      runtimeAttachFailure: currentRuntimeMetadata?.runtimeAttachFailure,
      t3EndpointConfig: resolveConfiguredT3Endpoint(runtimeConfig),
    });
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

    return queueWorkspaceActivation({
      activationId: ++activationSequence,
      cwd: nextCwd,
      forceRestart,
      runtimeConfig: config,
      persistWorkspaceState: true,
    });
  }

  async function performWorkspaceActivation({
    cwd,
    activationId,
    forceRestart = false,
    runtimeConfig = config,
    persistWorkspaceState = true,
  }) {
    const runtimeTarget = resolveConfiguredRuntimeTarget(runtimeConfig);
    if (activationId !== activationSequence) {
      return buildStatus({
        runtimeConfig,
        workspaceActive: Boolean(activeRuntime),
      });
    }

    if (!forceRestart && runtimeConfig === config && currentCwd === cwd && activeRuntime) {
      return getStatus();
    }

    await shutdownTransport();
    currentCwd = cwd;
    rememberRecentWorkspace(cwd, { persist: persistWorkspaceState });
    onBeforeTransportStart?.({
      cwd,
      runtimeTarget,
    });

    const nextRuntime = createRuntimeAdapterImpl({
      endpoint: resolveConfiguredRuntimeEndpoint(runtimeConfig),
      endpointAuthToken: resolveConfiguredRuntimeEndpointAuthToken(runtimeConfig),
      env: process.env,
      cwd,
      loadReplayCursor,
      logEvent: onTransportLog,
      persistReplayCursor,
      resolveEndpointConfig: runtimeTarget === "t3-server"
        ? () => resolveConfiguredT3Endpoint(runtimeConfig)
        : null,
      targetKind: runtimeTarget,
    });
    pendingRuntime = nextRuntime;
    currentRuntimeMetadata = nextRuntime.getRuntimeMetadata?.() || null;
    publishTransportMetadata({
      runtimeConfig,
      workspaceActive: false,
    });

    nextRuntime.onError((error) => {
      if (!isCurrentRuntime(nextRuntime)) {
        return;
      }
      activeRuntime = null;
      pendingRuntime = null;
      currentRuntimeMetadata = nextRuntime.getRuntimeMetadata?.() || currentRuntimeMetadata;
      publishTransportMetadata({
        runtimeConfig,
        workspaceActive: false,
      });
      onTransportError?.({
        error,
        currentCwd,
        runtimeMetadata: getRuntimeMetadata(),
        runtimeTarget,
      });
    });

    nextRuntime.onMetadata?.((metadata) => {
      if (!isCurrentRuntime(nextRuntime)) {
        return;
      }
      currentRuntimeMetadata = metadata ? { ...metadata } : null;
      publishTransportMetadata({
        runtimeConfig,
        workspaceActive: Boolean(activeRuntime),
      });
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
      publishTransportMetadata({
        runtimeConfig,
        workspaceActive: false,
      });
      onTransportClose?.({
        currentCwd,
        runtimeMetadata: getRuntimeMetadata(),
        runtimeTarget,
      });
    });

    await nextRuntime.whenReady?.();
    if (activationId !== activationSequence || pendingRuntime !== nextRuntime) {
      nextRuntime.shutdown?.();
      return buildStatus({
        runtimeConfig,
        workspaceActive: Boolean(activeRuntime),
      });
    }

    activeRuntime = nextRuntime;
    pendingRuntime = null;
    currentRuntimeMetadata = nextRuntime.getRuntimeMetadata?.() || currentRuntimeMetadata;
    publishTransportMetadata({
      runtimeConfig,
      workspaceActive: true,
    });
    return buildStatus({
      runtimeConfig,
      workspaceActive: true,
    });
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
    const currentTarget = resolveConfiguredRuntimeTarget(config);
    const nextConfig = cloneConfigSnapshot(config);
    applyRuntimeConfigUpdate(nextConfig, nextRuntimeConfig);
    const nextResolvedTarget = resolveConfiguredRuntimeTarget(nextConfig);

    if (currentTarget !== nextResolvedTarget) {
      const targetOptions = await getRuntimeTargetOptions({ runtimeConfig: nextConfig });
      const selectedOption = targetOptions.find((option) => option.value === nextResolvedTarget);
      if (selectedOption && !selectedOption.enabled) {
        throw new Error(
          normalizeNonEmptyString(selectedOption.availabilityMessage)
            || `${selectedOption.title} is not ready yet.`
        );
      }
    }

    const hasActiveWorkspace = Boolean(currentCwd && isExistingDirectory(currentCwd));
    const previousConfig = cloneConfigSnapshot(config);
    if (hasActiveWorkspace) {
      try {
        const activationId = ++activationSequence;
        const status = await queueWorkspaceActivation({
          activationId,
          cwd: currentCwd,
          forceRestart: true,
          runtimeConfig: nextConfig,
          persistWorkspaceState: false,
        });
        replaceConfigSnapshot(config, nextConfig);
        writeCommittedConfig();
        publishTransportMetadata();
        return status;
      } catch (error) {
        replaceConfigSnapshot(config, previousConfig);
        try {
          const recoveryActivationId = ++activationSequence;
          await queueWorkspaceActivation({
            activationId: recoveryActivationId,
            cwd: currentCwd,
            forceRestart: true,
            runtimeConfig: previousConfig,
            persistWorkspaceState: false,
          });
        } catch (recoveryError) {
          onTransportError?.({
            error: recoveryError,
            currentCwd,
            runtimeMetadata: getRuntimeMetadata(),
            runtimeTarget: resolveConfiguredRuntimeTarget(previousConfig),
          });
        }
        publishTransportMetadata();
        throw new Error(buildRuntimeSwitchFailureMessage({
          targetKind: nextResolvedTarget,
          currentTarget,
          error,
        }));
      }
    }

    replaceConfigSnapshot(config, nextConfig);
    writeCommittedConfig();
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

  function rememberRecentWorkspace(cwd, { persist = true } = {}) {
    const normalized = normalizeWorkspacePath(cwd);
    recentWorkspaces = [
      normalized,
      ...recentWorkspaces.filter((candidate) => normalizeWorkspacePath(candidate) !== normalized),
    ].slice(0, MAX_RECENT_WORKSPACES);

    config.activeCwd = normalized;
    config.recentWorkspaces = [...recentWorkspaces];
    if (persist) {
      writeCommittedConfig({
        activeCwd: normalized,
        recentWorkspaces: [...recentWorkspaces],
      });
    }
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
    writeCommittedConfig({
      t3ReplayCursors: nextReplayCursors,
    });
  }

  function resolveConfiguredRuntimeTarget(runtimeConfig = config) {
    return normalizeNonEmptyString(runtimeConfig?.runtimeTarget)
      || normalizeNonEmptyString(runtimeConfig?.runtimeProvider)
      || "codex-native";
  }

  function resolveConfiguredRuntimeEndpoint(runtimeConfig = config) {
    if (resolveConfiguredRuntimeTarget(runtimeConfig) === "t3-server") {
      return resolveConfiguredT3Endpoint(runtimeConfig).endpoint;
    }

    return normalizeNonEmptyString(runtimeConfig?.runtimeEndpoint)
      || normalizeNonEmptyString(runtimeConfig?.codexEndpoint)
      || "";
  }

  function resolveConfiguredRuntimeEndpointAuthToken(runtimeConfig = config) {
    if (resolveConfiguredRuntimeTarget(runtimeConfig) === "t3-server") {
      return resolveConfiguredT3Endpoint(runtimeConfig).authToken;
    }

    return normalizeNonEmptyString(runtimeConfig?.runtimeEndpointAuthToken)
      || "";
  }

  function resolveConfiguredT3Endpoint(runtimeConfig = config) {
    const explicitEndpoint = normalizeNonEmptyString(runtimeConfig?.runtimeEndpoint);
    if (explicitEndpoint) {
      return {
        endpoint: explicitEndpoint,
        authToken: normalizeNonEmptyString(runtimeConfig?.runtimeEndpointAuthToken),
        source: normalizeNonEmptyString(runtimeConfig?.runtimeEndpointSource) || "explicit",
      };
    }

    const discoveredEndpoint = resolveT3RuntimeEndpoint({
      env: process.env,
    });
    return {
      endpoint: normalizeNonEmptyString(discoveredEndpoint?.endpoint),
      authToken: normalizeNonEmptyString(discoveredEndpoint?.authToken),
      source: normalizeNonEmptyString(discoveredEndpoint?.source),
    };
  }

  function isCurrentRuntime(runtime) {
    return activeRuntime === runtime || pendingRuntime === runtime;
  }

  function publishTransportMetadata({
    runtimeConfig = config,
    workspaceActive = Boolean(activeRuntime),
  } = {}) {
    onTransportMetadata?.({
      currentCwd,
      runtimeMetadata: getRuntimeMetadata(),
      runtimeTarget: resolveConfiguredRuntimeTarget(runtimeConfig),
      workspaceActive,
    });
  }

  function buildStatus({
    runtimeConfig = config,
    workspaceActive = Boolean(activeRuntime),
  } = {}) {
    return {
      currentCwd: currentCwd || null,
      runtimeMetadata: getRuntimeMetadata(),
      runtimeTarget: resolveConfiguredRuntimeTarget(runtimeConfig),
      workspaceActive,
    };
  }

  function queueWorkspaceActivation({
    activationId = ++activationSequence,
    cwd,
    forceRestart = false,
    runtimeConfig = config,
    persistWorkspaceState = true,
  }) {
    const activation = activationQueue.then(() => performWorkspaceActivation({
      activationId,
      cwd,
      forceRestart,
      runtimeConfig,
      persistWorkspaceState,
    }));
    activationQueue = activation.then(
      () => undefined,
      () => undefined,
    );
    return activation;
  }

  function writeCommittedConfig(overrides = {}) {
    const normalizedRuntimeEndpoint = normalizeNonEmptyString(config?.runtimeEndpoint);
    const normalizedRuntimeEndpointAuthToken = normalizeNonEmptyString(config?.runtimeEndpointAuthToken);
    const normalizedRuntimeEndpointSource = normalizeNonEmptyString(config?.runtimeEndpointSource);
    writeDaemonConfigImpl({
      ...(readDaemonConfigImpl() || {}),
      ...config,
      runtimeEndpoint: normalizedRuntimeEndpoint,
      runtimeEndpointAuthToken: normalizedRuntimeEndpointAuthToken,
      runtimeEndpointSource: normalizedRuntimeEndpointSource,
      ...overrides,
    });
  }
}

function applyRuntimeConfigUpdate(config, nextRuntimeConfig = {}) {
  const nextTarget = normalizeNonEmptyString(nextRuntimeConfig.runtimeTarget)
    || normalizeNonEmptyString(nextRuntimeConfig.runtimeProvider);
  config.runtimeTarget = nextTarget;
  config.runtimeProvider = normalizeNonEmptyString(nextRuntimeConfig.runtimeProvider)
    || (nextTarget === "t3-server" ? "t3code" : "codex");

  if (Object.prototype.hasOwnProperty.call(nextRuntimeConfig, "runtimeEndpoint")) {
    config.runtimeEndpoint = normalizeNonEmptyString(nextRuntimeConfig.runtimeEndpoint);
  }
  if (Object.prototype.hasOwnProperty.call(nextRuntimeConfig, "runtimeEndpointAuthToken")) {
    config.runtimeEndpointAuthToken = normalizeNonEmptyString(nextRuntimeConfig.runtimeEndpointAuthToken);
  }
  if (Object.prototype.hasOwnProperty.call(nextRuntimeConfig, "runtimeEndpointSource")) {
    config.runtimeEndpointSource = normalizeNonEmptyString(nextRuntimeConfig.runtimeEndpointSource);
  }

  if (config.runtimeTarget === "codex-native") {
    config.runtimeEndpoint = normalizeNonEmptyString(config.codexEndpoint);
    config.runtimeEndpointAuthToken = "";
    config.runtimeEndpointSource = "";
  }
}

function buildRuntimeSwitchFailureMessage({
  targetKind,
  currentTarget,
  error,
}) {
  const targetLabel = resolveRuntimeTargetLabel(targetKind);
  const currentLabel = resolveRuntimeTargetLabel(currentTarget);
  const detail = normalizeNonEmptyString(error?.message) || `Unable to switch to ${targetLabel}.`;
  if (currentLabel && currentLabel !== targetLabel) {
    return `${detail} The bridge stayed on ${currentLabel}.`;
  }
  return detail;
}

function cloneConfigSnapshot(value) {
  if (!value || typeof value !== "object") {
    return {};
  }
  try {
    return JSON.parse(JSON.stringify(value));
  } catch {
    return { ...value };
  }
}

function replaceConfigSnapshot(target, snapshot) {
  Object.keys(target).forEach((key) => {
    delete target[key];
  });
  Object.assign(target, cloneConfigSnapshot(snapshot));
}

function resolveRuntimeTargetLabel(kind) {
  try {
    return resolveRuntimeTargetConfig({ kind }).displayName;
  } catch {
    return normalizeNonEmptyString(kind) || null;
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
