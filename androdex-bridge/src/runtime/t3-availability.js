// FILE: runtime/t3-availability.js
// Purpose: Summarizes attach-first T3 runtime availability for bridge status and install guidance.
// Layer: runtime helper
// Exports: T3 endpoint inspection helpers
// Depends on: none

function inspectT3Availability({
  runtimeTarget = "",
  runtimeEndpoint = "",
  runtimeAttachFailure = "",
} = {}) {
  const normalizedTarget = normalizeNonEmptyString(runtimeTarget) || "codex-native";
  const attachFailure = normalizeNonEmptyString(runtimeAttachFailure);
  if (normalizedTarget !== "t3-server") {
    return {
      runtimeTarget: normalizedTarget,
      selected: false,
      configured: false,
      reachableHint: "optional",
      reasonCode: "runtime-not-selected",
      endpoint: "",
      endpointHost: "",
      endpointPort: "",
      endpointPath: "",
      endpointProtocol: "",
      loopbackOnly: true,
      summary: "T3 is optional. Set ANDRODEX_RUNTIME_TARGET=t3-server to attach to a host-local T3 runtime.",
      detail: "",
      runtimeAttachFailure: attachFailure,
    };
  }

  const endpoint = normalizeNonEmptyString(runtimeEndpoint);
  if (!endpoint) {
    return {
      runtimeTarget: normalizedTarget,
      selected: true,
      configured: false,
      reachableHint: "missing",
      reasonCode: "missing-endpoint",
      endpoint: "",
      endpointHost: "",
      endpointPort: "",
      endpointPath: "",
      endpointProtocol: "",
      loopbackOnly: true,
      summary: "T3 target selected, but no endpoint is configured.",
      detail: "Set ANDRODEX_T3_ENDPOINT to a loopback T3 websocket such as ws://127.0.0.1:3773/ws.",
      runtimeAttachFailure: attachFailure,
    };
  }

  let parsedEndpoint;
  try {
    parsedEndpoint = new URL(endpoint);
  } catch {
    return {
      runtimeTarget: normalizedTarget,
      selected: true,
      configured: true,
      reachableHint: "invalid",
      reasonCode: "invalid-endpoint",
      endpoint,
      endpointHost: "",
      endpointPort: "",
      endpointPath: "",
      endpointProtocol: "",
      loopbackOnly: false,
      summary: "T3 endpoint is configured, but it is not a valid websocket URL.",
      detail: "Use a loopback websocket such as ws://127.0.0.1:3773/ws.",
      runtimeAttachFailure: attachFailure,
    };
  }

  const protocol = normalizeNonEmptyString(parsedEndpoint.protocol).replace(/:$/, "");
  const host = normalizeNonEmptyString(parsedEndpoint.hostname);
  const port = normalizeNonEmptyString(parsedEndpoint.port) || defaultPortForProtocol(protocol);
  const pathname = normalizeNonEmptyString(parsedEndpoint.pathname) || "/";
  const loopbackOnly = isLoopbackHost(host);
  const secureProtocol = protocol === "ws" || protocol === "wss";
  const validProtocol = secureProtocol;
  const reachableHint = validProtocol && loopbackOnly ? "attach-ready" : "blocked";
  const reasonCode = !validProtocol
    ? "unsupported-protocol"
    : (loopbackOnly ? "attach-ready" : "non-loopback-endpoint");

  return {
    runtimeTarget: normalizedTarget,
    selected: true,
    configured: true,
    reachableHint,
    reasonCode,
    endpoint,
    endpointHost: host,
    endpointPort: port,
    endpointPath: pathname,
    endpointProtocol: protocol,
    loopbackOnly,
    summary: buildSummary({ validProtocol, loopbackOnly }),
    detail: buildDetail({ validProtocol, loopbackOnly, port, pathname }),
    runtimeAttachFailure: attachFailure,
  };
}

function buildSummary({ validProtocol, loopbackOnly }) {
  if (!validProtocol) {
    return "T3 endpoint must use ws:// or wss://.";
  }
  if (!loopbackOnly) {
    return "T3 endpoint is configured, but v1 only allows host-local loopback attach.";
  }
  return "T3 attach target looks valid for host-local companion mode.";
}

function buildDetail({ validProtocol, loopbackOnly, port, pathname }) {
  if (!validProtocol) {
    return "Switch ANDRODEX_T3_ENDPOINT to a websocket URL such as ws://127.0.0.1:3773/ws.";
  }
  if (!loopbackOnly) {
    return "Point ANDRODEX_T3_ENDPOINT at a loopback address like 127.0.0.1 or localhost instead of a LAN or public host.";
  }
  return `Expected host-local websocket route on port ${port || "3773"}${pathname || "/ws"}.`;
}

function isLoopbackHost(hostname) {
  const normalized = normalizeNonEmptyString(hostname).toLowerCase();
  return normalized === "localhost"
    || normalized === "127.0.0.1"
    || normalized === "::1"
    || normalized === "[::1]";
}

function defaultPortForProtocol(protocol) {
  if (protocol === "wss") {
    return "443";
  }
  if (protocol === "ws") {
    return "80";
  }
  return "";
}

function normalizeNonEmptyString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

module.exports = {
  inspectT3Availability,
};
