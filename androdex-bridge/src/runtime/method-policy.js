// FILE: runtime/method-policy.js
// Purpose: Centralizes runtime-target RPC gating so read-only adapters can be enforced consistently in the bridge and adapter layers.
// Layer: CLI helper
// Exports: runtime-target method policy helpers
// Depends on: ./target-config

const { normalizeRuntimeTargetKind } = require("./target-config");

const T3_SERVER_READ_ONLY_METHODS = new Set([
  "thread/list",
  "thread/read",
  "thread/resume",
  "turn/interrupt",
  "model/list",
  "collaborationmode/list",
]);

function normalizeRuntimeMethod(value) {
  return typeof value === "string" ? value.trim().toLowerCase() : "";
}

function isCodexNativeRuntimeTarget(targetKind) {
  return normalizeRuntimeTargetKind(targetKind) === "codex-native";
}

function isReadOnlyRuntimeTarget(targetKind) {
  return normalizeRuntimeTargetKind(targetKind) === "t3-server";
}

function isRuntimeTargetMethodAllowed({ targetKind, method }) {
  if (!isReadOnlyRuntimeTarget(targetKind)) {
    return true;
  }

  const normalizedMethod = normalizeRuntimeMethod(method);
  if (!normalizedMethod) {
    return true;
  }

  return T3_SERVER_READ_ONLY_METHODS.has(normalizedMethod);
}

function buildRuntimeTargetMethodRejectionMessage({ targetKind, method }) {
  if (!isReadOnlyRuntimeTarget(targetKind)) {
    return "";
  }

  const normalizedMethod = normalizeRuntimeMethod(method) || "unknown";
  return `The active T3 runtime does not support "${normalizedMethod}" yet. It will stay disabled until the corresponding T3 adapter slice lands.`;
}

module.exports = {
  buildRuntimeTargetMethodRejectionMessage,
  isCodexNativeRuntimeTarget,
  isReadOnlyRuntimeTarget,
  isRuntimeTargetMethodAllowed,
  normalizeRuntimeMethod,
};
