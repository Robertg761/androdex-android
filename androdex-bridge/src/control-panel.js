// FILE: control-panel.js
// Purpose: Exposes structured control-plane helpers for desktop UIs that manage the local Androdex bridge.
// Layer: CLI helper
// Exports: snapshot, service control, runtime config, descriptor, and log helpers

const fs = require("fs");
const os = require("os");
const path = require("path");
const {
  readDaemonConfig,
  resolveAndrodexStateDir,
  resolveBridgeLogsDir,
  resolveBridgeStdoutLogPath,
  resolveBridgeStderrLogPath,
  writeDaemonConfig,
} = require("./daemon-state");
const {
  getMacOSBridgeServiceStatus,
  resetMacOSBridgePairing,
  startMacOSBridgeService,
  stopMacOSBridgeService,
} = require("./macos-launch-agent");
const { getBridgeDoctorReport } = require("./doctor");
const { detectInstalledT3Runtime, resolveT3RuntimeEndpoint } = require("./runtime/t3-discovery");
const { buildRuntimeTargetOptions } = require("./runtime/runtime-target-options");
const { resolveRuntimeTargetConfig } = require("./runtime/target-config");

const DEFAULT_LOG_LINE_COUNT = 160;
const RUNTIME_SESSION_FILE_NAME = "runtime-session.json";

async function getBridgeDesktopSnapshot({
  env = process.env,
  includeDoctor = false,
  includeLogs = true,
  logLineCount = DEFAULT_LOG_LINE_COUNT,
} = {}) {
  const daemonConfig = readDaemonConfig({ env }) || {};
  const controlEnv = buildControlEnvFromDaemonConfig({ env, daemonConfig });
  const serviceStatus = getMacOSBridgeServiceStatus({ env: controlEnv });
  const installedT3Runtime = detectInstalledT3Runtime({ env: controlEnv });
  const runtimeSessionPath = resolveT3RuntimeSessionPath({ env });
  const runtimeTargetOptions = await buildRuntimeTargetOptions({
    currentRuntimeTarget: normalizeNonEmptyString(daemonConfig.runtimeTarget)
      || normalizeNonEmptyString(serviceStatus?.runtimeConfig?.runtimeTarget)
      || normalizeNonEmptyString(serviceStatus?.bridgeStatus?.runtimeTarget)
      || "codex-native",
    installedT3Runtime,
    runtimeAttachFailure: serviceStatus?.bridgeStatus?.runtimeAttachFailure,
    t3EndpointConfig: resolveDesktopT3EndpointConfig({
      env,
      nextConfig: daemonConfig,
    }),
  });

  return {
    daemonConfig,
    doctorReport: includeDoctor ? await getBridgeDoctorReport({ env: controlEnv }) : null,
    installedT3Runtime,
    logs: includeLogs
      ? {
          stdout: readBridgeLogTail({
            env,
            kind: "stdout",
            lineCount: logLineCount,
          }),
          stderr: readBridgeLogTail({
            env,
            kind: "stderr",
            lineCount: logLineCount,
          }),
        }
      : null,
    paths: {
      logsDir: resolveBridgeLogsDir({ env }),
      runtimeSessionDir: path.dirname(runtimeSessionPath),
      runtimeSessionPath,
      stateDir: resolveAndrodexStateDir({ env }),
      stdoutLogPath: resolveBridgeStdoutLogPath({ env }),
      stderrLogPath: resolveBridgeStderrLogPath({ env }),
    },
    runtimeSessionDescriptor: readRuntimeSessionDescriptor({ filePath: runtimeSessionPath }),
    runtimeTargetOptions,
    serviceStatus,
  };
}

async function updateBridgeRuntimeConfig({
  targetKind,
  endpoint = "",
  endpointAuthToken = "",
  refreshEnabled = null,
  env = process.env,
  detectInstalledT3RuntimeImpl = detectInstalledT3Runtime,
  readDaemonConfigImpl = readDaemonConfig,
  resolveT3RuntimeEndpointImpl = resolveT3RuntimeEndpoint,
  writeDaemonConfigImpl = writeDaemonConfig,
} = {}) {
  const currentConfig = readDaemonConfigImpl({ env }) || {};
  const nextConfig = buildNextRuntimeConfig({
    currentConfig,
    endpoint,
    endpointAuthToken,
    refreshEnabled,
    targetKind,
  });

  const runtimeTargetOptions = await buildRuntimeTargetOptions({
    currentRuntimeTarget: nextConfig.runtimeTarget,
    installedT3Runtime: detectInstalledT3RuntimeImpl({ env }),
    t3EndpointConfig: resolveDesktopT3EndpointConfig({
      env,
      nextConfig,
      resolveT3RuntimeEndpointImpl,
    }),
  });
  const selectedOption = runtimeTargetOptions.find((option) => option.value === nextConfig.runtimeTarget);
  if (selectedOption && !selectedOption.enabled) {
    throw new Error(
      normalizeNonEmptyString(selectedOption.availabilityMessage)
        || `${selectedOption.title} is not ready yet.`
    );
  }

  writeDaemonConfigImpl(nextConfig, { env });
  return nextConfig;
}

function buildNextRuntimeConfig({
  currentConfig = {},
  endpoint = "",
  endpointAuthToken = "",
  refreshEnabled = null,
  targetKind,
} = {}) {
  const runtimeTarget = resolveRuntimeTargetConfig({ kind: targetKind }).kind;
  const nextConfig = {
    ...currentConfig,
    runtimeProvider: runtimeTarget === "t3-server" ? "t3code" : "codex",
    runtimeTarget,
  };

  if (runtimeTarget === "t3-server") {
    nextConfig.runtimeEndpoint = normalizeNonEmptyString(endpoint);
    if (normalizeNonEmptyString(endpointAuthToken)) {
      nextConfig.runtimeEndpointAuthToken = normalizeNonEmptyString(endpointAuthToken);
    } else {
      delete nextConfig.runtimeEndpointAuthToken;
    }
    nextConfig.runtimeEndpointSource = normalizeNonEmptyString(endpoint)
      ? "explicit"
      : "";
  } else {
    nextConfig.runtimeEndpoint = "";
    nextConfig.runtimeEndpointAuthToken = "";
    nextConfig.runtimeEndpointSource = "";
  }

  if (typeof refreshEnabled === "boolean") {
    nextConfig.refreshEnabled = refreshEnabled;
    nextConfig.refreshEnabledExplicit = true;
  }

  return nextConfig;
}

function resolveDesktopT3EndpointConfig({
  env = process.env,
  nextConfig = {},
  resolveT3RuntimeEndpointImpl = resolveT3RuntimeEndpoint,
} = {}) {
  const explicitEndpoint = normalizeNonEmptyString(nextConfig.runtimeEndpoint);
  if (explicitEndpoint) {
    return {
      endpoint: explicitEndpoint,
      authToken: normalizeNonEmptyString(nextConfig.runtimeEndpointAuthToken),
      source: normalizeNonEmptyString(nextConfig.runtimeEndpointSource) || "explicit",
    };
  }
  return resolveT3RuntimeEndpointImpl({ env });
}

async function startBridgeServiceFromConfig({
  env = process.env,
  waitForPairing = false,
  activeCwd = "",
} = {}) {
  const daemonConfig = readDaemonConfig({ env }) || {};
  const controlEnv = buildControlEnvFromDaemonConfig({ env, daemonConfig });
  return startMacOSBridgeService({
    activeCwd: normalizeNonEmptyString(activeCwd) || normalizeNonEmptyString(daemonConfig.activeCwd),
    env: controlEnv,
    waitForPairing,
  });
}

async function restartBridgeServiceFromConfig({
  env = process.env,
  waitForPairing = false,
  activeCwd = "",
} = {}) {
  return startBridgeServiceFromConfig({
    activeCwd,
    env,
    waitForPairing,
  });
}

function stopBridgeService({
  env = process.env,
} = {}) {
  stopMacOSBridgeService({ env });
}

function resetBridgePairing({
  env = process.env,
} = {}) {
  resetMacOSBridgePairing({ env });
}

function writeDesktopT3RuntimeSessionDescriptor({
  authToken,
  backendPid = null,
  baseUrl,
  env = process.env,
} = {}) {
  const descriptor = {
    authToken: normalizeNonEmptyString(authToken),
    backendPid: Number.isInteger(backendPid) && backendPid > 0 ? backendPid : null,
    baseUrl: normalizeNonEmptyString(baseUrl),
    source: "desktop",
    transport: "websocket",
    version: 1,
  };

  if (!descriptor.baseUrl) {
    throw new Error("T3 runtime session descriptor requires a websocket baseUrl.");
  }
  if (!descriptor.authToken) {
    throw new Error("T3 runtime session descriptor requires an auth token.");
  }

  const filePath = resolveT3RuntimeSessionPath({ env });
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, JSON.stringify(descriptor, null, 2), { mode: 0o600 });
  try {
    fs.chmodSync(filePath, 0o600);
  } catch {
    // Best effort.
  }
  return {
    descriptor,
    filePath,
  };
}

function removeDesktopT3RuntimeSessionDescriptor({
  env = process.env,
} = {}) {
  const filePath = resolveT3RuntimeSessionPath({ env });
  fs.rmSync(filePath, { force: true });
  return filePath;
}

function readBridgeLogTail({
  env = process.env,
  kind = "stdout",
  lineCount = DEFAULT_LOG_LINE_COUNT,
} = {}) {
  const filePath = kind === "stderr"
    ? resolveBridgeStderrLogPath({ env })
    : resolveBridgeStdoutLogPath({ env });
  return {
    filePath,
    text: tailUtf8File({
      filePath,
      lineCount,
    }),
  };
}

function resolveT3RuntimeSessionPath({
  env = process.env,
} = {}) {
  return path.join(resolveHomeDir({ env }), ".t3", "userdata", RUNTIME_SESSION_FILE_NAME);
}

function readRuntimeSessionDescriptor({
  filePath,
} = {}) {
  try {
    const parsed = JSON.parse(fs.readFileSync(filePath, "utf8"));
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}

function buildControlEnvFromDaemonConfig({
  daemonConfig,
  env = process.env,
} = {}) {
  const nextEnv = {
    ...env,
  };
  setOptionalEnvValue(nextEnv, "ANDRODEX_RELAY", daemonConfig?.relayUrl);
  setOptionalEnvValue(nextEnv, "ANDRODEX_PUSH_SERVICE_URL", daemonConfig?.pushServiceUrl);
  setOptionalEnvValue(nextEnv, "ANDRODEX_PUSH_PREVIEW_MAX_CHARS", daemonConfig?.pushPreviewMaxChars);
  setOptionalEnvValue(nextEnv, "ANDRODEX_REFRESH_COMMAND", daemonConfig?.refreshCommand);
  setOptionalEnvValue(nextEnv, "ANDRODEX_REFRESH_DEBOUNCE_MS", daemonConfig?.refreshDebounceMs);
  setBooleanEnvValue(
    nextEnv,
    "ANDRODEX_REFRESH_ENABLED",
    daemonConfig?.refreshEnabledExplicit ? daemonConfig?.refreshEnabled : null
  );
  setBooleanEnvValue(nextEnv, "ANDRODEX_REFRESH_ROUTE_TO_THREAD", daemonConfig?.routeThreadTargets);
  const runtimeTarget = normalizeNonEmptyString(daemonConfig?.runtimeTarget)
    || normalizeNonEmptyString(daemonConfig?.runtimeProvider)
    || "codex-native";
  const runtimeTargetConfig = resolveRuntimeTargetConfig({ kind: runtimeTarget });
  clearKnownRuntimeEnv(nextEnv);
  nextEnv.ANDRODEX_RUNTIME_TARGET = runtimeTarget;
  setFirstKnownEnvValue(nextEnv, runtimeTargetConfig.endpointEnvVars, daemonConfig?.runtimeEndpoint);
  setFirstKnownEnvValue(nextEnv, runtimeTargetConfig.desktopBundleIdEnvVars, daemonConfig?.codexBundleId);
  if (runtimeTarget === "t3-server") {
    setOptionalEnvValue(nextEnv, "ANDRODEX_T3_AUTH_TOKEN", daemonConfig?.runtimeEndpointAuthToken);
  } else {
    delete nextEnv.ANDRODEX_T3_AUTH_TOKEN;
  }
  return nextEnv;
}

function setOptionalEnvValue(env, key, value) {
  const normalizedValue = normalizeNonEmptyString(value);
  if (normalizedValue) {
    env[key] = normalizedValue;
    return;
  }
  if (typeof value === "number" && Number.isFinite(value)) {
    env[key] = String(value);
    return;
  }
  delete env[key];
}

function setBooleanEnvValue(env, key, value) {
  if (typeof value !== "boolean") {
    delete env[key];
    return;
  }
  env[key] = value ? "1" : "0";
}

function setFirstKnownEnvValue(env, keys, value) {
  if (!Array.isArray(keys) || keys.length === 0) {
    return;
  }
  const [firstKey] = keys;
  setOptionalEnvValue(env, firstKey, value);
}

function clearKnownRuntimeEnv(env) {
  delete env.ANDRODEX_CODEX_ENDPOINT;
  delete env.ANDRODEX_CODEX_BUNDLE_ID;
  delete env.ANDRODEX_T3_ENDPOINT;
}

function tailUtf8File({
  filePath,
  lineCount = DEFAULT_LOG_LINE_COUNT,
} = {}) {
  try {
    const content = fs.readFileSync(filePath, "utf8");
    return content
      .split(/\r?\n/)
      .slice(-Math.max(1, Math.trunc(lineCount)))
      .join("\n")
      .trim();
  } catch {
    return "";
  }
}

function normalizeNonEmptyString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

function resolveHomeDir({
  env = process.env,
} = {}) {
  return normalizeNonEmptyString(env.HOME) || os.homedir();
}

module.exports = {
  getBridgeDesktopSnapshot,
  readBridgeLogTail,
  removeDesktopT3RuntimeSessionDescriptor,
  restartBridgeServiceFromConfig,
  resetBridgePairing,
  resolveT3RuntimeSessionPath,
  startBridgeServiceFromConfig,
  stopBridgeService,
  updateBridgeRuntimeConfig,
  writeDesktopT3RuntimeSessionDescriptor,
};
