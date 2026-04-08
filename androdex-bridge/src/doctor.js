// FILE: doctor.js
// Purpose: Provides a lightweight host-side diagnostic for runtime-target configuration and T3 companion readiness.
// Layer: CLI helper
// Exports: doctor report helpers used by the CLI
// Depends on: net, child_process, ./codex-desktop-refresher, ./macos-launch-agent, ./runtime/t3-availability

const net = require("net");
const { readBridgeConfig } = require("./codex-desktop-refresher");
const { getMacOSBridgeServiceStatus } = require("./macos-launch-agent");
const { inspectT3Availability } = require("./runtime/t3-availability");
const { detectInstalledT3Runtime } = require("./runtime/t3-discovery");

async function getBridgeDoctorReport({
  env = process.env,
  getMacOSBridgeServiceStatusImpl = getMacOSBridgeServiceStatus,
  inspectT3AvailabilityImpl = inspectT3Availability,
  probeTcpEndpointImpl = probeTcpEndpoint,
  detectInstalledT3RuntimeImpl = detectInstalledT3Runtime,
  timeoutMs = 1_000,
} = {}) {
  const bridgeConfig = readBridgeConfig({ env });
  const serviceStatus = getMacOSBridgeServiceStatusImpl({ env });
  const runtimeTarget = bridgeConfig.runtimeTarget;
  const runtimeEndpoint = bridgeConfig.runtimeEndpoint;
  const runtimeEndpointSource = bridgeConfig.runtimeEndpointSource || "explicit";
  const t3Availability = inspectT3AvailabilityImpl({
    runtimeTarget,
    runtimeEndpoint,
    runtimeAttachFailure: serviceStatus?.bridgeStatus?.runtimeAttachFailure,
  });

  let endpointProbe = null;
  let desktopSessionProbe = null;
  let installedRuntime = null;
  const recommendations = [];

  if (runtimeTarget === "t3-server") {
    installedRuntime = detectInstalledT3RuntimeImpl();
    if (t3Availability.reasonCode === "attach-ready") {
      endpointProbe = await probeTcpEndpointImpl({
        host: t3Availability.endpointHost,
        port: t3Availability.endpointPort,
        timeoutMs,
      });
      if (endpointProbe.reachable) {
        recommendations.push("T3 looks reachable. Restart or run `androdex up` with the same T3 environment to switch the bridge onto that runtime.");
      } else {
        recommendations.push("No T3 listener answered on the configured loopback endpoint. Start T3 locally, then rerun `androdex doctor`.");
        if (installedRuntime.desktopAppInstalled) {
          recommendations.push(`Open ${installedRuntime.desktopAppPath} and wait for T3 to finish starting, then rerun \`androdex doctor\`.`);
        } else if (!installedRuntime.cliInstalled) {
          recommendations.push("Install the T3 desktop app or T3 CLI first so Androdex has a host-local runtime to attach to.");
        }
      }
    } else if (t3Availability.reasonCode === "missing-endpoint") {
      recommendations.push("Set ANDRODEX_T3_ENDPOINT to a host-local websocket such as ws://127.0.0.1:3773/ws.");
    } else if (t3Availability.reasonCode === "non-loopback-endpoint") {
      recommendations.push("Point ANDRODEX_T3_ENDPOINT at localhost, 127.0.0.1, or ::1 instead of a LAN or public host.");
    } else if (t3Availability.reasonCode === "unsupported-protocol" || t3Availability.reasonCode === "invalid-endpoint") {
      recommendations.push("Use a websocket endpoint such as ws://127.0.0.1:3773/ws for T3 companion attach.");
    }

    const desktopSessionEndpoint = normalizeNonEmptyString(installedRuntime?.desktopSession?.endpoint);
    const desktopDescriptorStatus = normalizeNonEmptyString(installedRuntime?.desktopSession?.descriptorStatus);
    const desktopDescriptorDetail = normalizeNonEmptyString(installedRuntime?.desktopSession?.descriptorDetail);
    if (desktopDescriptorStatus && desktopDescriptorStatus !== "trusted" && desktopDescriptorStatus !== "missing") {
      recommendations.push(
        `The local T3 desktop runtime descriptor looks ${desktopDescriptorStatus.replace(/-/g, " ")}. Restart T3 Code to refresh ${installedRuntime?.desktopSession?.runtimeSessionPath || "the descriptor"}${desktopDescriptorDetail ? ` (${desktopDescriptorDetail})` : ""}.`
      );
    }
    if (desktopSessionEndpoint) {
      const desktopSessionUrl = tryParseUrl(desktopSessionEndpoint);
      if (desktopSessionUrl) {
        desktopSessionProbe = await probeTcpEndpointImpl({
          host: desktopSessionUrl.hostname,
          port: desktopSessionUrl.port || "80",
          timeoutMs,
        });
      }
      if (desktopSessionEndpoint !== runtimeEndpoint) {
        recommendations.push(`T3 desktop is currently exposing a different loopback websocket (${desktopSessionEndpoint}).`);
      }
      if (installedRuntime?.desktopSession?.source !== "runtime-session-file"
        && installedRuntime?.desktopSession?.authEnabled !== false) {
        recommendations.push("The installed T3 desktop app uses auth-protected dynamic loopback sessions, so seamless attach still needs a desktop-to-Androdex auth handoff or local runtime descriptor.");
      }
    }
  } else {
    recommendations.push("Codex-native is still the default. Set ANDRODEX_RUNTIME_TARGET=t3-server when you want Androdex to attach to a host-local T3 runtime.");
  }

  return {
    runtimeTarget,
    runtimeEndpoint,
    runtimeEndpointSource,
    serviceRuntimeTarget: serviceStatus?.runtimeConfig?.runtimeTarget || serviceStatus?.bridgeStatus?.runtimeTarget || "unknown",
    serviceRuntimeEndpoint: serviceStatus?.runtimeConfig?.runtimeEndpoint || "",
    t3Availability,
    endpointProbe,
    desktopSessionProbe,
    tools: {
      t3Runtime: installedRuntime,
    },
    recommendations,
  };
}

async function runBridgeDoctor({
  env = process.env,
  consoleImpl = console,
  ...rest
} = {}) {
  const report = await getBridgeDoctorReport({
    env,
    ...rest,
  });

  consoleImpl.log(`[androdex] Doctor runtime target: ${report.runtimeTarget}`);
  consoleImpl.log(`[androdex] Service runtime target: ${report.serviceRuntimeTarget}`);
  if (report.runtimeEndpoint) {
    const label = report.runtimeEndpointSource === "default-loopback"
      ? "Discovered runtime endpoint"
      : "Configured runtime endpoint";
    consoleImpl.log(`[androdex] ${label}: ${report.runtimeEndpoint}`);
  }

  if (report.runtimeTarget === "t3-server") {
    consoleImpl.log(`[androdex] T3 config: ${report.t3Availability.summary}`);
    if (report.t3Availability.detail) {
      consoleImpl.log(`[androdex] T3 detail: ${report.t3Availability.detail}`);
    }
    if (report.endpointProbe) {
      consoleImpl.log(
        `[androdex] T3 probe: ${report.endpointProbe.reachable ? "reachable" : `unreachable (${report.endpointProbe.reasonCode})`}`
      );
    }
    if (report.tools.t3Runtime) {
      const runtimeBits = [];
      if (report.tools.t3Runtime.desktopAppInstalled) {
        runtimeBits.push(`desktop app at ${report.tools.t3Runtime.desktopAppPath}`);
      }
      if (report.tools.t3Runtime.cliInstalled) {
        runtimeBits.push(`CLI at ${report.tools.t3Runtime.cliPath}`);
      }
      consoleImpl.log(
        `[androdex] T3 install: ${runtimeBits.length > 0 ? runtimeBits.join(", ") : "not detected"}`
      );
      const desktopSessionEndpoint = normalizeNonEmptyString(report.tools.t3Runtime.desktopSession?.endpoint);
      if (desktopSessionEndpoint) {
        const authSuffix = report.tools.t3Runtime.desktopSession?.authEnabled === true
          ? " (auth enabled)"
          : (report.tools.t3Runtime.desktopSession?.authEnabled === false ? " (auth disabled)" : "");
        consoleImpl.log(`[androdex] T3 desktop session: ${desktopSessionEndpoint}${authSuffix}`);
        if (report.desktopSessionProbe) {
          consoleImpl.log(
            `[androdex] T3 desktop session probe: ${report.desktopSessionProbe.reachable ? "reachable" : `unreachable (${report.desktopSessionProbe.reasonCode})`}`
          );
        }
      }
      const descriptorStatus = normalizeNonEmptyString(report.tools.t3Runtime.desktopSession?.descriptorStatus);
      if (descriptorStatus && descriptorStatus !== "missing") {
        const descriptorLine = descriptorStatus === "trusted"
          ? `trusted descriptor at ${report.tools.t3Runtime.desktopSession.runtimeSessionPath}`
          : `${descriptorStatus.replace(/-/g, " ")} descriptor at ${report.tools.t3Runtime.desktopSession.runtimeSessionPath}`;
        consoleImpl.log(`[androdex] T3 desktop descriptor: ${descriptorLine}`);
      }
    }
  } else {
    consoleImpl.log(`[androdex] T3 config: ${report.t3Availability.summary}`);
  }

  for (const recommendation of report.recommendations) {
    consoleImpl.log(`[androdex] Recommendation: ${recommendation}`);
  }

  return report;
}

function probeTcpEndpoint({
  host,
  port,
  timeoutMs = 1_000,
  netImpl = net,
} = {}) {
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
    socket.once("connect", () => {
      finish({
        reachable: true,
        reasonCode: "reachable",
      });
    });
    socket.once("timeout", () => {
      finish({
        reachable: false,
        reasonCode: "timeout",
      });
    });
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

function tryParseUrl(rawValue) {
  try {
    return new URL(rawValue);
  } catch {
    return null;
  }
}

module.exports = {
  getBridgeDoctorReport,
  probeTcpEndpoint,
  runBridgeDoctor,
};
