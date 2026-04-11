// FILE: runtime/t3-availability.js
// Purpose: Summarizes attach-first T3 runtime availability for bridge status and install guidance.
// Layer: runtime helper
// Exports: T3 endpoint inspection helpers
// Depends on: none

function inspectT3Availability({
  desktopSession = null,
  runtimeTarget = "",
  runtimeEndpoint = "",
  runtimeAttachFailure = "",
} = {}) {
  const normalizedTarget = normalizeNonEmptyString(runtimeTarget) || "codex-native";
  const attachFailure = normalizeNonEmptyString(runtimeAttachFailure);
  const discoveredDesktopEndpoint = normalizeNonEmptyString(desktopSession?.endpoint);
  const discoveredDesktopAuthToken = normalizeNonEmptyString(desktopSession?.authToken);
  const discoveredDesktopDescriptorStatus = normalizeNonEmptyString(desktopSession?.descriptorStatus);
  const desktopSessionRequiresTrustedHandoff = Boolean(
    discoveredDesktopEndpoint
    && desktopSession?.authEnabled === true
    && !discoveredDesktopAuthToken
  );
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
      discoveredDesktopEndpoint,
      runtimeAttachFailure: attachFailure,
    };
  }

  const endpoint = normalizeNonEmptyString(runtimeEndpoint);
  if (desktopSessionRequiresTrustedHandoff
    && endpoint
    && endpoint !== discoveredDesktopEndpoint
    && isConnectionRefusedFailure(attachFailure)) {
    return {
      runtimeTarget: normalizedTarget,
      selected: true,
      configured: true,
      reachableHint: "desktop-session-untrusted",
      reasonCode: "desktop-session-missing-auth-handoff",
      endpoint,
      endpointHost: "",
      endpointPort: "",
      endpointPath: "",
      endpointProtocol: "",
      loopbackOnly: true,
      summary: "T3 desktop session detected, but its trusted auth handoff is missing.",
      detail: `A local T3 desktop session is advertising ${discoveredDesktopEndpoint}, but Androdex cannot attach securely until ~/.t3/userdata/runtime-session.json contains a trusted auth token.`,
      discoveredDesktopEndpoint,
      runtimeAttachFailure: attachFailure,
    };
  }
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
      detail: desktopSessionRequiresTrustedHandoff && discoveredDesktopDescriptorStatus === "missing"
        ? `A local T3 desktop session is visible at ${discoveredDesktopEndpoint}, but its trusted ~/.t3/userdata/runtime-session.json handoff is missing.`
        : "Set ANDRODEX_T3_ENDPOINT to a loopback T3 websocket such as ws://127.0.0.1:3773/ws.",
      discoveredDesktopEndpoint,
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
      discoveredDesktopEndpoint,
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
    discoveredDesktopEndpoint,
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
  return `Expected a host-local websocket listener on port ${port || "3773"} at path ${pathname || "/ws"}.`;
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

function isConnectionRefusedFailure(value) {
  const normalized = normalizeNonEmptyString(value).toLowerCase();
  return normalized.includes("econnrefused") || normalized.includes("connection refused");
}

module.exports = {
  inspectT3Availability,
};
