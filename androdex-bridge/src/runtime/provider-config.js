// FILE: runtime/provider-config.js
// Purpose: Backwards-compatible shim for the older provider-named runtime config surface.
// Layer: CLI helper
// Exports: legacy provider-selection helpers backed by runtime-target config
// Depends on: ./target-config

const {
  DEFAULT_RUNTIME_TARGET,
  createRuntimeLaunchPlan,
  normalizeRuntimeTargetKind,
  readRuntimeTargetKind,
  resolveRuntimeTargetConfig,
} = require("./target-config");

const DEFAULT_RUNTIME_PROVIDER = "codex";

function toLegacyProviderKind(runtimeTarget) {
  return runtimeTarget?.legacyProviderKind || runtimeTarget?.kind || DEFAULT_RUNTIME_PROVIDER;
}

function normalizeRuntimeProviderKind(value) {
  const normalizedTargetKind = normalizeRuntimeTargetKind(value);
  if (!normalizedTargetKind) {
    return "";
  }

  if (normalizedTargetKind === DEFAULT_RUNTIME_TARGET) {
    return DEFAULT_RUNTIME_PROVIDER;
  }

  return toLegacyProviderKind(resolveRuntimeProviderConfig({
    kind: normalizedTargetKind,
  }));
}

function readRuntimeProviderKind(options = {}) {
  return toLegacyProviderKind(resolveRuntimeTargetConfig({
    kind: readRuntimeTargetKind(options),
  }));
}

function resolveRuntimeProviderConfig({ kind = DEFAULT_RUNTIME_PROVIDER } = {}) {
  const runtimeTarget = resolveRuntimeTargetConfig({ kind });
  return {
    ...runtimeTarget,
    kind: toLegacyProviderKind(runtimeTarget),
  };
}

module.exports = {
  DEFAULT_RUNTIME_PROVIDER,
  createRuntimeLaunchPlan,
  normalizeRuntimeProviderKind,
  readRuntimeProviderKind,
  resolveRuntimeProviderConfig,
};
