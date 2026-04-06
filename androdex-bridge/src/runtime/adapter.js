// FILE: runtime/adapter.js
// Purpose: Provides the first runtime-target adapter seam so the bridge can switch host runtimes without pushing target-specific lifecycle into workspace orchestration.
// Layer: CLI helper
// Exports: createRuntimeAdapter
// Depends on: ../codex/transport, ./target-config

const { createCodexTransport } = require("../codex/transport");
const {
  DEFAULT_RUNTIME_TARGET,
  resolveRuntimeTargetConfig,
} = require("./target-config");

function createRuntimeAdapter({
  targetKind = DEFAULT_RUNTIME_TARGET,
  endpoint = "",
  env = process.env,
  cwd = "",
  WebSocketImpl,
} = {}) {
  const runtimeTarget = resolveRuntimeTargetConfig({ kind: targetKind });
  if (runtimeTarget.kind === "codex-native") {
    return createCodexNativeRuntimeAdapter({
      runtimeTarget,
      endpoint,
      env,
      cwd,
      WebSocketImpl,
    });
  }

  throw new Error(`Unsupported runtime target adapter: ${runtimeTarget.kind}`);
}

function createCodexNativeRuntimeAdapter({
  runtimeTarget,
  endpoint = "",
  env = process.env,
  cwd = "",
  WebSocketImpl,
} = {}) {
  const transport = createCodexTransport({
    endpoint,
    env,
    cwd,
    runtimeTarget: runtimeTarget.kind,
    WebSocketImpl,
  });

  return {
    backendProviderKind: runtimeTarget.backendProviderKind,
    describe: transport.describe,
    kind: runtimeTarget.kind,
    mode: transport.mode,
    onClose: transport.onClose,
    onError: transport.onError,
    onMessage: transport.onMessage,
    send: transport.send,
    shutdown: transport.shutdown,
  };
}

module.exports = {
  createRuntimeAdapter,
};
