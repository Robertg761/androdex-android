// FILE: doctor.js
// Purpose: Provides a lightweight host-side diagnostic for runtime-target configuration and T3 companion readiness.
// Layer: CLI helper
// Exports: doctor report helpers used by the CLI
// Depends on: net, child_process, ./codex-desktop-refresher, ./macos-launch-agent, ./runtime/t3-availability

const net = require("net");
const { execFileSync } = require("child_process");
const { readBridgeConfig } = require("./codex-desktop-refresher");
const { getMacOSBridgeServiceStatus } = require("./macos-launch-agent");
const { inspectT3Availability } = require("./runtime/t3-availability");

async function getBridgeDoctorReport({
  env = process.env,
  getMacOSBridgeServiceStatusImpl = getMacOSBridgeServiceStatus,
  inspectT3AvailabilityImpl = inspectT3Availability,
  probeTcpEndpointImpl = probeTcpEndpoint,
  detectCommandImpl = detectCommand,
  timeoutMs = 1_000,
} = {}) {
  const bridgeConfig = readBridgeConfig({ env });
  const serviceStatus = getMacOSBridgeServiceStatusImpl({ env });
  const runtimeTarget = bridgeConfig.runtimeTarget;
  const runtimeEndpoint = bridgeConfig.runtimeEndpoint;
  const t3Availability = inspectT3AvailabilityImpl({
    runtimeTarget,
    runtimeEndpoint,
    runtimeAttachFailure: serviceStatus?.bridgeStatus?.runtimeAttachFailure,
  });

  let endpointProbe = null;
  let bunAvailable = null;
  const recommendations = [];

  if (runtimeTarget === "t3-server") {
    if (t3Availability.reasonCode === "attach-ready") {
      endpointProbe = await probeTcpEndpointImpl({
        host: t3Availability.endpointHost,
        port: t3Availability.endpointPort,
        timeoutMs,
      });
      bunAvailable = detectCommandImpl("bun");
      if (endpointProbe.reachable) {
        recommendations.push("T3 looks reachable. Restart or run `androdex up` with the same T3 environment to switch the bridge onto that runtime.");
      } else {
        recommendations.push("No T3 listener answered on the configured loopback endpoint. Start T3 locally, then rerun `androdex doctor`.");
        if (!bunAvailable.available) {
          recommendations.push("If you are trying to run T3 from source, install Bun first or use a packaged T3 runtime.");
        }
      }
    } else if (t3Availability.reasonCode === "missing-endpoint") {
      recommendations.push("Set ANDRODEX_T3_ENDPOINT to a host-local websocket such as ws://127.0.0.1:3773/ws.");
    } else if (t3Availability.reasonCode === "non-loopback-endpoint") {
      recommendations.push("Point ANDRODEX_T3_ENDPOINT at localhost, 127.0.0.1, or ::1 instead of a LAN or public host.");
    } else if (t3Availability.reasonCode === "unsupported-protocol" || t3Availability.reasonCode === "invalid-endpoint") {
      recommendations.push("Use a websocket endpoint such as ws://127.0.0.1:3773/ws for T3 companion attach.");
    }
  } else {
    recommendations.push("Codex-native is still the default. Set ANDRODEX_RUNTIME_TARGET=t3-server when you want Androdex to attach to a host-local T3 runtime.");
  }

  return {
    runtimeTarget,
    runtimeEndpoint,
    serviceRuntimeTarget: serviceStatus?.runtimeConfig?.runtimeTarget || serviceStatus?.bridgeStatus?.runtimeTarget || "unknown",
    serviceRuntimeEndpoint: serviceStatus?.runtimeConfig?.runtimeEndpoint || "",
    t3Availability,
    endpointProbe,
    tools: {
      bun: bunAvailable,
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
    consoleImpl.log(`[androdex] Configured runtime endpoint: ${report.runtimeEndpoint}`);
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
    if (report.tools.bun) {
      consoleImpl.log(`[androdex] Bun: ${report.tools.bun.available ? "found" : "not found"}`);
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

function detectCommand(command, {
  execFileSyncImpl = execFileSync,
} = {}) {
  try {
    const output = execFileSyncImpl("which", [command], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    });
    return {
      available: true,
      path: normalizeNonEmptyString(output),
    };
  } catch {
    return {
      available: false,
      path: "",
    };
  }
}

function normalizeNonEmptyString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

module.exports = {
  detectCommand,
  getBridgeDoctorReport,
  probeTcpEndpoint,
  runBridgeDoctor,
};
