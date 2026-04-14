// FILE: runtime/runtime-target-options.js
// Purpose: Computes runtime target availability and host-local reachability for runtime switching surfaces.
// Layer: runtime helper
// Exports: runtime target option builder and TCP probe helpers
// Depends on: net, ./t3-availability

const net = require("net");
const { inspectT3Availability } = require("./t3-availability");

async function buildRuntimeTargetOptions({
  currentRuntimeTarget = "codex-native",
  installedT3Runtime = null,
  probeTimeoutMs = 500,
  probeTcpEndpointImpl = probeTcpEndpoint,
  runtimeAttachFailure = "",
  t3EndpointConfig = null,
} = {}) {
  const resolvedCurrentTarget = normalizeNonEmptyString(currentRuntimeTarget) || "codex-native";
  const endpointConfig = t3EndpointConfig && typeof t3EndpointConfig === "object"
    ? t3EndpointConfig
    : {};
  const t3Availability = inspectT3Availability({
    desktopSession: installedT3Runtime?.desktopSession,
    runtimeTarget: "t3-server",
    runtimeEndpoint: shouldTreatT3EndpointAsConfigured(endpointConfig)
      ? normalizeNonEmptyString(endpointConfig.endpoint)
      : "",
    runtimeAttachFailure,
  });

  let t3Enabled = false;
  let t3AvailabilityMessage = normalizeNonEmptyString(t3Availability.detail)
    || normalizeNonEmptyString(t3Availability.summary);

  if (t3Availability.reasonCode === "attach-ready") {
    const endpointProbe = await probeTcpEndpointImpl({
      host: t3Availability.endpointHost,
      port: t3Availability.endpointPort,
      timeoutMs: probeTimeoutMs,
    });
    t3Enabled = endpointProbe.reachable;
    if (!t3Enabled) {
      const targetHost = t3Availability.endpointHost || "127.0.0.1";
      const targetPort = t3Availability.endpointPort || "3773";
      t3AvailabilityMessage = `No Androdex Server listener answered on ${targetHost}:${targetPort}. Start the local server and try again.`;
    }
  }

  return [
    {
      value: "codex-native",
      title: "Codex",
      subtitle: "Use the normal host-local Codex runtime.",
      selected: resolvedCurrentTarget === "codex-native",
      enabled: true,
      availabilityMessage: null,
    },
    {
      value: "t3-server",
      title: "Androdex Server",
      subtitle: "Attach to the host-local Androdex Server runtime when it is available.",
      selected: resolvedCurrentTarget === "t3-server",
      enabled: t3Enabled,
      availabilityMessage: t3Enabled ? null : (t3AvailabilityMessage || "Androdex Server is not ready yet."),
    },
  ];
}

function shouldTreatT3EndpointAsConfigured(endpointConfig) {
  if (!endpointConfig || typeof endpointConfig !== "object") {
    return false;
  }
  if (normalizeNonEmptyString(endpointConfig.source) === "default-loopback") {
    return false;
  }
  return normalizeNonEmptyString(endpointConfig.endpoint).length > 0;
}

function probeTcpEndpoint({
  host,
  port,
  timeoutMs = 500,
  netImpl = net,
} = {}) {
  if (!normalizeNonEmptyString(host) || !normalizeNonEmptyString(port)) {
    return Promise.resolve({
      reachable: false,
      reasonCode: "missing-endpoint",
    });
  }

  return new Promise((resolve) => {
    const socket = netImpl.createConnection({
      host,
      port: Number.parseInt(port, 10),
    });

    const finish = (result) => {
      socket.removeAllListeners();
      socket.destroy();
      resolve(result);
    };

    socket.setTimeout(timeoutMs);
    socket.once("connect", () => finish({ reachable: true, reasonCode: "reachable" }));
    socket.once("timeout", () => finish({ reachable: false, reasonCode: "timeout" }));
    socket.once("error", (error) => {
      finish({
        reachable: false,
        reasonCode: normalizeNonEmptyString(error?.code) || "connect-failed",
      });
    });
  });
}

function normalizeNonEmptyString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

module.exports = {
  buildRuntimeTargetOptions,
  probeTcpEndpoint,
  shouldTreatT3EndpointAsConfigured,
};
