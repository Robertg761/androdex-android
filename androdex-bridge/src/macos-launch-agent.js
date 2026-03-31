// FILE: macos-launch-agent.js
// Purpose: Owns macOS-only launchd install/start/stop/status helpers for the background Androdex bridge.
// Layer: CLI helper
// Exports: start/stop/status helpers plus the launchd service runner used by `androdex up`.
// Depends on: child_process, fs, os, path, ./bridge, ./daemon-state, ./codex-desktop-refresher, ./qr, ./secure-device-state

const { execFileSync } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { startBridge } = require("./bridge");
const { readBridgeConfig } = require("./codex-desktop-refresher");
const { printQR } = require("./qr");
const {
  hasTrustedPhones,
  loadOrCreateBridgeDeviceState,
  resetBridgeDeviceState,
} = require("./secure-device-state");
const {
  clearBridgeStatus,
  clearPairingSession,
  ensureAndrodexLogsDir,
  ensureAndrodexStateDir,
  readBridgeStatus,
  readDaemonConfig,
  readPairingSession,
  resolveAndrodexStateDir,
  resolveBridgeStderrLogPath,
  resolveBridgeStdoutLogPath,
  writeBridgeStatus,
  writeDaemonConfig,
  writePairingSession,
} = require("./daemon-state");

const SERVICE_LABEL = "io.androdex.bridge";
const DEFAULT_PAIRING_WAIT_TIMEOUT_MS = 10_000;
const DEFAULT_PAIRING_WAIT_INTERVAL_MS = 200;
const PAIRING_FRESHNESS_WINDOW_MS = 5 * 60 * 1000;

function runMacOSBridgeService({ env = process.env } = {}) {
  assertDarwinPlatform();
  const config = readDaemonConfig({ env });
  if (!config?.relayUrl) {
    const message = "No relay URL configured for the macOS bridge service.";
    clearPairingSession({ env });
    writeBridgeStatus({
      state: "error",
      connectionStatus: "error",
      pid: process.pid,
      lastError: message,
    }, { env });
    console.error(`[androdex] ${message}`);
    return;
  }

  startBridge({
    config,
    printPairingQr: false,
    onPairingPayload(pairingPayload) {
      writePairingSession(pairingPayload, { env });
    },
    onBridgeStatus(status) {
      writeBridgeStatus(status, { env });
    },
  });
}

async function startMacOSBridgeService({
  env = process.env,
  platform = process.platform,
  fsImpl = fs,
  execFileSyncImpl = execFileSync,
  osImpl = os,
  nodePath = process.execPath,
  cliPath = path.resolve(__dirname, "..", "bin", "androdex.js"),
  waitForPairing = false,
  pairingTimeoutMs = DEFAULT_PAIRING_WAIT_TIMEOUT_MS,
  pairingPollIntervalMs = DEFAULT_PAIRING_WAIT_INTERVAL_MS,
  activeCwd = "",
} = {}) {
  assertDarwinPlatform(platform);
  const config = readBridgeConfig({ env });
  assertRelayConfigured(config);
  const startedAt = Date.now();
  const nextConfig = buildNextDaemonConfig(readDaemonConfig({ env, fsImpl }), config, activeCwd);

  writeDaemonConfig(nextConfig, { env, fsImpl });
  clearPairingSession({ env, fsImpl });
  clearBridgeStatus({ env, fsImpl });
  ensureAndrodexStateDir({ env, fsImpl, osImpl });
  ensureAndrodexLogsDir({ env, fsImpl, osImpl });

  const plistPath = writeLaunchAgentPlist({
    env,
    fsImpl,
    osImpl,
    nodePath,
    cliPath,
  });
  restartLaunchAgent({
    env,
    execFileSyncImpl,
    plistPath,
  });

  if (!waitForPairing) {
    return {
      plistPath,
      pairingSession: null,
    };
  }

  const pairingSession = await waitForFreshPairingSession({
    env,
    fsImpl,
    startedAt,
    timeoutMs: pairingTimeoutMs,
    intervalMs: pairingPollIntervalMs,
  });
  return {
    plistPath,
    pairingSession,
  };
}

function stopMacOSBridgeService({
  env = process.env,
  platform = process.platform,
  execFileSyncImpl = execFileSync,
  fsImpl = fs,
} = {}) {
  assertDarwinPlatform(platform);
  bootoutLaunchAgent({
    env,
    execFileSyncImpl,
    ignoreMissing: true,
  });
  clearPairingSession({ env, fsImpl });
  clearBridgeStatus({ env, fsImpl });
}

function resetMacOSBridgePairing({
  env = process.env,
  platform = process.platform,
  execFileSyncImpl = execFileSync,
  fsImpl = fs,
  resetBridgePairingImpl = resetBridgeDeviceState,
} = {}) {
  assertDarwinPlatform(platform);
  stopMacOSBridgeService({
    env,
    platform,
    execFileSyncImpl,
    fsImpl,
  });
  return resetBridgePairingImpl();
}

function getMacOSBridgeServiceStatus({
  env = process.env,
  platform = process.platform,
  execFileSyncImpl = execFileSync,
  fsImpl = fs,
  loadBridgeDeviceStateImpl = loadOrCreateBridgeDeviceState,
} = {}) {
  assertDarwinPlatform(platform);
  const launchd = readLaunchAgentState({ env, execFileSyncImpl });
  const pairingSession = readPairingSession({ env, fsImpl });
  const pairingFreshness = classifyPairingPayloadFreshness(pairingSession);
  const duplicateBridgeProcesses = listDuplicateBridgeProcesses({ execFileSyncImpl, launchdPid: launchd.pid });
  const deviceState = runCatchingLoadBridgeDeviceState(loadBridgeDeviceStateImpl);
  return {
    label: SERVICE_LABEL,
    platform: "darwin",
    installed: fsImpl.existsSync(resolveLaunchAgentPlistPath({ env })),
    launchdLoaded: launchd.loaded,
    launchdPid: launchd.pid,
    bridgeStatus: readBridgeStatus({ env, fsImpl }),
    pairingSession,
    pairingFreshness,
    hasTrustedPhone: hasTrustedPhones(deviceState),
    duplicateBridgeProcesses,
    stdoutLogPath: resolveBridgeStdoutLogPath({ env }),
    stderrLogPath: resolveBridgeStderrLogPath({ env }),
  };
}

function printMacOSBridgeServiceStatus(options = {}) {
  const status = getMacOSBridgeServiceStatus(options);
  const bridgeState = status.bridgeStatus?.state || "unknown";
  const connectionStatus = status.bridgeStatus?.connectionStatus || "unknown";
  const pairingCreatedAt = status.pairingSession?.createdAt || "none";
  console.log(`[androdex] Service label: ${status.label}`);
  console.log(`[androdex] Installed: ${status.installed ? "yes" : "no"}`);
  console.log(`[androdex] Launchd loaded: ${status.launchdLoaded ? "yes" : "no"}`);
  console.log(`[androdex] PID: ${status.launchdPid || status.bridgeStatus?.pid || "unknown"}`);
  console.log(`[androdex] Bridge state: ${bridgeState}`);
  console.log(`[androdex] Connection: ${connectionStatus}`);
  console.log(`[androdex] Trusted phone: ${status.hasTrustedPhone ? "yes" : "no"}`);
  console.log(`[androdex] Pairing payload: ${pairingCreatedAt} (${status.pairingFreshness})`);
  if (status.duplicateBridgeProcesses.length > 0) {
    console.log(`[androdex] Duplicate bridge processes: ${status.duplicateBridgeProcesses.length}`);
  }
  if (status.bridgeStatus?.lastError) {
    console.log(`[androdex] Relay note: ${status.bridgeStatus.lastError}`);
  }
  console.log(`[androdex] Stdout log: ${status.stdoutLogPath}`);
  console.log(`[androdex] Stderr log: ${status.stderrLogPath}`);
}

function printMacOSBridgePairingQr({ pairingSession = null, env = process.env, fsImpl = fs } = {}) {
  const nextPairingSession = pairingSession || readPairingSession({ env, fsImpl });
  const pairingPayload = nextPairingSession?.pairingPayload;
  if (!pairingPayload) {
    throw new Error("The macOS bridge service did not publish a pairing payload yet.");
  }

  printQR(pairingPayload);
}

function writeLaunchAgentPlist({
  env = process.env,
  fsImpl = fs,
  osImpl = os,
  nodePath = process.execPath,
  cliPath = path.resolve(__dirname, "..", "bin", "androdex.js"),
} = {}) {
  const plistPath = resolveLaunchAgentPlistPath({ env, osImpl });
  const stateDir = resolveAndrodexStateDir({ env, osImpl });
  const stdoutLogPath = resolveBridgeStdoutLogPath({ env, osImpl });
  const stderrLogPath = resolveBridgeStderrLogPath({ env, osImpl });
  const homeDir = env.HOME || osImpl.homedir();
  const serialized = buildLaunchAgentPlist({
    homeDir,
    pathEnv: env.PATH || "",
    stateDir,
    stdoutLogPath,
    stderrLogPath,
    nodePath,
    cliPath,
  });

  fsImpl.mkdirSync(path.dirname(plistPath), { recursive: true });
  fsImpl.writeFileSync(plistPath, serialized, "utf8");
  return plistPath;
}

function buildLaunchAgentPlist({
  homeDir,
  pathEnv,
  stateDir,
  stdoutLogPath,
  stderrLogPath,
  nodePath,
  cliPath,
}) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${escapeXml(SERVICE_LABEL)}</string>
  <key>ProgramArguments</key>
  <array>
    <string>${escapeXml(nodePath)}</string>
    <string>${escapeXml(cliPath)}</string>
    <string>run-service</string>
  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <dict>
    <key>SuccessfulExit</key>
    <false/>
  </dict>
  <key>WorkingDirectory</key>
  <string>${escapeXml(homeDir)}</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>HOME</key>
    <string>${escapeXml(homeDir)}</string>
    <key>PATH</key>
    <string>${escapeXml(pathEnv)}</string>
    <key>ANDRODEX_DEVICE_STATE_DIR</key>
    <string>${escapeXml(stateDir)}</string>
  </dict>
  <key>StandardOutPath</key>
  <string>${escapeXml(stdoutLogPath)}</string>
  <key>StandardErrorPath</key>
  <string>${escapeXml(stderrLogPath)}</string>
</dict>
</plist>
`;
}

async function waitForFreshPairingSession({
  env = process.env,
  fsImpl = fs,
  startedAt = Date.now(),
  timeoutMs = DEFAULT_PAIRING_WAIT_TIMEOUT_MS,
  intervalMs = DEFAULT_PAIRING_WAIT_INTERVAL_MS,
} = {}) {
  const deadline = Date.now() + timeoutMs;

  while (Date.now() <= deadline) {
    const pairingSession = readPairingSession({ env, fsImpl });
    const createdAt = Date.parse(pairingSession?.createdAt || "");
    if (pairingSession?.pairingPayload && Number.isFinite(createdAt) && createdAt >= startedAt) {
      return pairingSession;
    }
    await sleep(intervalMs);
  }

  throw new Error(
    `Timed out waiting for the macOS bridge service to publish a pairing QR. `
    + `Check ${resolveBridgeStderrLogPath({ env })}.`
  );
}

function restartLaunchAgent({
  env = process.env,
  execFileSyncImpl = execFileSync,
  plistPath,
} = {}) {
  bootoutLaunchAgent({
    env,
    execFileSyncImpl,
    ignoreMissing: true,
  });
  execFileSyncImpl("launchctl", [
    "bootstrap",
    launchAgentDomain(env),
    plistPath,
  ], { stdio: ["ignore", "ignore", "pipe"] });
}

function bootoutLaunchAgent({
  env = process.env,
  execFileSyncImpl = execFileSync,
  ignoreMissing = false,
} = {}) {
  const bootoutTargets = [
    [launchAgentDomain(env), resolveLaunchAgentPlistPath({ env })],
    [launchAgentLabelDomain(env)],
  ];
  let lastError = null;

  for (const targetArgs of bootoutTargets) {
    try {
      execFileSyncImpl("launchctl", [
        "bootout",
        ...targetArgs,
      ], { stdio: ["ignore", "ignore", "pipe"] });
      return;
    } catch (error) {
      lastError = error;
    }
  }

  if (ignoreMissing && isMissingLaunchAgentError(lastError)) {
    return;
  }
  throw lastError;
}

function readLaunchAgentState({
  env = process.env,
  execFileSyncImpl = execFileSync,
} = {}) {
  try {
    const output = execFileSyncImpl("launchctl", [
      "print",
      launchAgentLabelDomain(env),
    ], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
    return {
      loaded: true,
      pid: parseLaunchdPid(output),
      raw: output,
    };
  } catch (error) {
    if (isMissingLaunchAgentError(error)) {
      return {
        loaded: false,
        pid: null,
        raw: "",
      };
    }
    throw error;
  }
}

function resolveLaunchAgentPlistPath({ env = process.env, osImpl = os } = {}) {
  const homeDir = env.HOME || osImpl.homedir();
  return path.join(homeDir, "Library", "LaunchAgents", `${SERVICE_LABEL}.plist`);
}

function buildNextDaemonConfig(existingConfig, bridgeConfig, activeCwd) {
  const normalizedActiveCwd = normalizeNonEmptyString(activeCwd);
  const recentWorkspaces = Array.isArray(existingConfig?.recentWorkspaces)
    ? existingConfig.recentWorkspaces.filter((candidate) => typeof candidate === "string" && candidate.trim())
    : [];

  const nextRecentWorkspaces = normalizedActiveCwd
    ? [normalizedActiveCwd, ...recentWorkspaces.filter((candidate) => candidate !== normalizedActiveCwd)].slice(0, 25)
    : recentWorkspaces.slice(0, 25);

  return {
    ...existingConfig,
    ...bridgeConfig,
    activeCwd: normalizedActiveCwd || normalizeNonEmptyString(existingConfig?.activeCwd),
    recentWorkspaces: nextRecentWorkspaces,
  };
}

function assertDarwinPlatform(platform = process.platform) {
  if (platform !== "darwin") {
    throw new Error("macOS bridge service management is only available on macOS.");
  }
}

function assertRelayConfigured(config) {
  if (typeof config?.relayUrl === "string" && config.relayUrl.trim()) {
    return;
  }
  throw new Error("No relay URL configured. Set ANDRODEX_RELAY before enabling the macOS bridge service.");
}

function launchAgentDomain(env) {
  return `gui/${resolveUid(env)}`;
}

function launchAgentLabelDomain(env) {
  return `${launchAgentDomain(env)}/${SERVICE_LABEL}`;
}

function resolveUid(env) {
  if (typeof process.getuid === "function") {
    return process.getuid();
  }

  const uid = Number.parseInt(env.UID || "", 10);
  if (Number.isFinite(uid)) {
    return uid;
  }

  throw new Error("Could not determine the current macOS user id for launchctl.");
}

function parseLaunchdPid(output) {
  const match = typeof output === "string" ? output.match(/\bpid = (\d+)/) : null;
  return match ? Number.parseInt(match[1], 10) : null;
}

function classifyPairingPayloadFreshness(pairingSession, now = Date.now()) {
  const expiresAt = Number(pairingSession?.pairingPayload?.expiresAt);
  if (!Number.isFinite(expiresAt) || expiresAt <= 0) {
    const createdAt = Date.parse(pairingSession?.createdAt || "");
    if (!Number.isFinite(createdAt)) {
      return "missing";
    }
    return (now - createdAt) < PAIRING_FRESHNESS_WINDOW_MS ? "fresh" : "expired";
  }
  return expiresAt > now ? "fresh" : "expired";
}

function listDuplicateBridgeProcesses({
  execFileSyncImpl = execFileSync,
  launchdPid = null,
} = {}) {
  try {
    const output = execFileSyncImpl("pgrep", [
      "-af",
      "androdex-bridge/bin/(androdex\\.js run-service|cli\\.js __daemon-run)",
    ], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    });
    return String(output)
      .split("\n")
      .map((line) => line.trim())
      .filter(Boolean)
      .map((line) => {
        const match = line.match(/^(\d+)\s+(.*)$/);
        if (!match) {
          return null;
        }
        return {
          pid: Number.parseInt(match[1], 10),
          command: match[2],
        };
      })
      .filter(Boolean)
      .filter((entry) => Number.isFinite(entry.pid) && entry.pid !== launchdPid);
  } catch {
    return [];
  }
}

function runCatchingLoadBridgeDeviceState(loadBridgeDeviceStateImpl) {
  try {
    return loadBridgeDeviceStateImpl();
  } catch {
    return null;
  }
}

function isMissingLaunchAgentError(error) {
  const combined = [
    error?.message,
    error?.stderr?.toString?.("utf8"),
    error?.stdout?.toString?.("utf8"),
  ].filter(Boolean).join("\n").toLowerCase();
  return combined.includes("could not find service")
    || combined.includes("service could not be found")
    || combined.includes("no such process");
}

function escapeXml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&apos;");
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function normalizeNonEmptyString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

module.exports = {
  buildLaunchAgentPlist,
  getMacOSBridgeServiceStatus,
  printMacOSBridgePairingQr,
  printMacOSBridgeServiceStatus,
  resetMacOSBridgePairing,
  resolveLaunchAgentPlistPath,
  runMacOSBridgeService,
  startMacOSBridgeService,
  stopMacOSBridgeService,
};
