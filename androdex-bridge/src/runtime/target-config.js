// FILE: runtime/target-config.js
// Purpose: Centralizes host runtime-target selection so new runtime adapters can land without threading launch details across the bridge.
// Layer: CLI helper
// Exports: runtime-target selection helpers and launch-plan builder
// Depends on: none

const DEFAULT_RUNTIME_TARGET = "codex-native";

const SUPPORTED_RUNTIME_TARGETS = Object.freeze({
  "codex-native": Object.freeze({
    kind: "codex-native",
    legacyProviderKind: "codex",
    displayName: "Codex Native",
    backendProviderKind: "codex",
    endpointEnvVars: ["ANDRODEX_CODEX_ENDPOINT"],
    desktopBundleIdEnvVars: ["ANDRODEX_CODEX_BUNDLE_ID"],
    defaultBundleId: "com.openai.codex",
    defaultAppPath: "/Applications/Codex.app",
    launchCommand: "codex",
    launchArgs: Object.freeze(["app-server"]),
    launchDescription: "`codex app-server`",
  }),
  "t3-server": Object.freeze({
    kind: "t3-server",
    legacyProviderKind: "t3code",
    displayName: "Androdex Server",
    backendProviderKind: null,
    endpointEnvVars: ["ANDRODEX_T3_ENDPOINT"],
    desktopBundleIdEnvVars: [],
    defaultBundleId: "",
    defaultAppPath: "",
    launchCommand: "",
    launchArgs: Object.freeze([]),
    launchDescription: "an explicit host-local Androdex Server endpoint",
  }),
});

const RUNTIME_TARGET_ALIASES = Object.freeze({
  codex: "codex-native",
  "codex-native": "codex-native",
  t3code: "t3-server",
  "t3-server": "t3-server",
});

function normalizeRuntimeTargetKind(value) {
  if (typeof value !== "string") {
    return "";
  }

  const normalized = value.trim().toLowerCase();
  return RUNTIME_TARGET_ALIASES[normalized] || "";
}

function readRuntimeTargetKind({ env = process.env } = {}) {
  const rawValue = env?.ANDRODEX_RUNTIME_TARGET || env?.ANDRODEX_RUNTIME_PROVIDER;
  if (typeof rawValue !== "string" || !rawValue.trim()) {
    return DEFAULT_RUNTIME_TARGET;
  }

  const normalized = normalizeRuntimeTargetKind(rawValue);
  if (SUPPORTED_RUNTIME_TARGETS[normalized]) {
    return normalized;
  }

  const supportedKinds = Object.keys(SUPPORTED_RUNTIME_TARGETS).sort().join(", ");
  throw new Error(
    `Unsupported runtime target "${rawValue}". Known targets: ${supportedKinds}. `
      + "Use ANDRODEX_RUNTIME_TARGET (preferred) or the legacy ANDRODEX_RUNTIME_PROVIDER."
  );
}

function resolveRuntimeTargetConfig({ kind = DEFAULT_RUNTIME_TARGET } = {}) {
  if (typeof kind !== "string" || !kind.trim()) {
    return SUPPORTED_RUNTIME_TARGETS[DEFAULT_RUNTIME_TARGET];
  }

  const normalizedKind = normalizeRuntimeTargetKind(kind);
  if (!normalizedKind) {
    const supportedKinds = Object.keys(SUPPORTED_RUNTIME_TARGETS).sort().join(", ");
    throw new Error(
      `Unsupported runtime target "${kind}". Supported targets today: ${supportedKinds}.`
    );
  }

  const supportedTarget = SUPPORTED_RUNTIME_TARGETS[normalizedKind];
  if (supportedTarget) {
    return supportedTarget;
  }

  const supportedKinds = Object.keys(SUPPORTED_RUNTIME_TARGETS).sort().join(", ");
  throw new Error(
    `Unsupported runtime target "${kind}". Supported targets today: ${supportedKinds}.`
  );
}

function createRuntimeLaunchPlan({
  kind = DEFAULT_RUNTIME_TARGET,
  env = process.env,
  cwd = "",
} = {}) {
  const runtimeTarget = resolveRuntimeTargetConfig({ kind });
  if (!normalizeRuntimeTargetKind(runtimeTarget.kind || "")
    || !runtimeTarget.launchCommand
    || !Array.isArray(runtimeTarget.launchArgs)
    || runtimeTarget.launchArgs.length === 0) {
    const endpointHint = Array.isArray(runtimeTarget.endpointEnvVars)
      && runtimeTarget.endpointEnvVars.length > 0
      ? runtimeTarget.endpointEnvVars.join(" or ")
      : "an explicit endpoint";
    throw new Error(
      `${runtimeTarget.displayName} does not support bridge-managed launch yet. `
        + `Configure ${endpointHint} to attach to an existing host-local instance.`
    );
  }
  return {
    command: runtimeTarget.launchCommand,
    args: [...runtimeTarget.launchArgs],
    options: {
      stdio: ["pipe", "pipe", "pipe"],
      env: { ...env },
      cwd: cwd || process.cwd(),
    },
    description: runtimeTarget.launchDescription,
  };
}

module.exports = {
  DEFAULT_RUNTIME_TARGET,
  createRuntimeLaunchPlan,
  normalizeRuntimeTargetKind,
  readRuntimeTargetKind,
  resolveRuntimeTargetConfig,
};
