const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");
const {
  buildLaunchAgentPlist,
  getMacOSBridgeServiceStatus,
  resetMacOSBridgePairing,
  resolveLaunchAgentPlistPath,
  runMacOSBridgeService,
  startMacOSBridgeService,
  stopMacOSBridgeService,
} = require("../src/macos-launch-agent");
const {
  readBridgeStatus,
  readPairingSession,
  writeBridgeStatus,
  writePairingSession,
} = require("../src/daemon-state");

test("buildLaunchAgentPlist points launchd at run-service with Androdex state paths", () => {
  const plist = buildLaunchAgentPlist({
    homeDir: "/Users/tester",
    pathEnv: "/usr/local/bin:/usr/bin",
    stateDir: "/Users/tester/.androdex",
    stdoutLogPath: "/Users/tester/.androdex/logs/bridge.stdout.log",
    stderrLogPath: "/Users/tester/.androdex/logs/bridge.stderr.log",
    nodePath: "/usr/local/bin/node",
    cliPath: "/tmp/androdex/bin/androdex.js",
    refreshEnabled: true,
  });

  assert.match(plist, /<string>io\.androdex\.bridge<\/string>/);
  assert.match(plist, /<string>run-service<\/string>/);
  assert.match(plist, /<key>ANDRODEX_DEVICE_STATE_DIR<\/key>/);
  assert.match(plist, /<key>ANDRODEX_REFRESH_ENABLED<\/key>/);
  assert.match(plist, /<string>true<\/string>/);
});

test("resolveLaunchAgentPlistPath writes into the user's LaunchAgents folder", () => {
  assert.equal(
    resolveLaunchAgentPlistPath({
      env: { HOME: "/Users/tester" },
      osImpl: { homedir: () => "/Users/fallback" },
    }),
    path.join("/Users/tester", "Library", "LaunchAgents", "io.androdex.bridge.plist")
  );
});

test("stopMacOSBridgeService clears stale pairing and status files", () => {
  withTempDaemonEnv(() => {
    writePairingSession({ sessionId: "session-1" });
    writeBridgeStatus({ state: "running", connectionStatus: "connected" });

    stopMacOSBridgeService({
      platform: "darwin",
      execFileSyncImpl() {
        const error = new Error("Could not find service");
        error.stderr = Buffer.from("Could not find service");
        throw error;
      },
    });

    assert.equal(readPairingSession(), null);
    assert.equal(readBridgeStatus(), null);
  });
});

test("resetMacOSBridgePairing stops the service before revoking persisted trust", () => {
  withTempDaemonEnv(() => {
    let stopCalls = 0;
    let resetCalls = 0;

    const result = resetMacOSBridgePairing({
      platform: "darwin",
      execFileSyncImpl() {
        stopCalls += 1;
        const error = new Error("Could not find service");
        error.stderr = Buffer.from("Could not find service");
        throw error;
      },
      resetBridgePairingImpl() {
        resetCalls += 1;
        return { hadState: true };
      },
    });

    assert.equal(stopCalls, 2);
    assert.equal(resetCalls, 1);
    assert.equal(result.hadState, true);
  });
});

test("runMacOSBridgeService records a clean error state instead of throwing when daemon config is missing", () => {
  withTempDaemonEnv(() => {
    writePairingSession({ sessionId: "stale-session" });

    assert.doesNotThrow(() => {
      runMacOSBridgeService({ env: process.env, platform: "darwin" });
    });

    assert.equal(readPairingSession(), null);
    const status = readBridgeStatus();
    assert.equal(status?.state, "error");
    assert.equal(status?.connectionStatus, "error");
    assert.equal(status?.pid, process.pid);
    assert.equal(status?.lastError, "No relay URL configured for the macOS bridge service.");
  });
});

test("getMacOSBridgeServiceStatus reports launchd and runtime metadata together", () => {
  withTempDaemonEnv(({ rootDir }) => {
    writePairingSession({
      sessionId: "session-2",
      expiresAt: Date.now() + 60_000,
    });
    writeBridgeStatus({ state: "running", connectionStatus: "connected", pid: 55 });
    fs.writeFileSync(
      path.join(rootDir, "daemon-config.json"),
      JSON.stringify({ refreshEnabled: true }, null, 2)
    );

    const plistPath = path.join(rootDir, "Library", "LaunchAgents", "io.androdex.bridge.plist");
    fs.mkdirSync(path.dirname(plistPath), { recursive: true });
    fs.writeFileSync(plistPath, "plist");

    const status = getMacOSBridgeServiceStatus({
      platform: "darwin",
      env: { HOME: rootDir, ANDRODEX_DEVICE_STATE_DIR: rootDir },
      loadBridgeDeviceStateImpl() {
        return { trustedPhones: { "phone-1": "public-key-1" } };
      },
      execFileSyncImpl(command) {
        if (command === "launchctl") {
          return "pid = 55";
        }
        if (command === "pgrep") {
          return "";
        }
        throw new Error(`unexpected command: ${command}`);
      },
    });

    assert.equal(status.launchdLoaded, true);
    assert.equal(status.launchdPid, 55);
    assert.equal(status.bridgeStatus?.connectionStatus, "connected");
    assert.equal(status.pairingSession?.pairingPayload?.sessionId, "session-2");
    assert.equal(status.pairingFreshness, "fresh");
    assert.equal(status.refreshEnabled, true);
  });
});

test("getMacOSBridgeServiceStatus surfaces expired pairing payloads and duplicate processes", () => {
  withTempDaemonEnv(({ rootDir }) => {
    writePairingSession({
      sessionId: "session-expired",
      expiresAt: Date.now() - 5_000,
    });
    writeBridgeStatus({
      state: "running",
      connectionStatus: "disconnected",
      pid: 55,
      lastError: "Relay heartbeat stalled; reconnect pending.",
    });
    fs.writeFileSync(
      path.join(rootDir, "daemon-config.json"),
      JSON.stringify({ refreshEnabled: false }, null, 2)
    );

    const plistPath = path.join(rootDir, "Library", "LaunchAgents", "io.androdex.bridge.plist");
    fs.mkdirSync(path.dirname(plistPath), { recursive: true });
    fs.writeFileSync(plistPath, "plist");

    const status = getMacOSBridgeServiceStatus({
      platform: "darwin",
      env: { HOME: rootDir, ANDRODEX_DEVICE_STATE_DIR: rootDir },
      loadBridgeDeviceStateImpl() {
        return { trustedPhones: { "phone-1": "public-key-1" } };
      },
      execFileSyncImpl(command) {
        if (command === "launchctl") {
          return "pid = 55";
        }
        if (command === "pgrep") {
          return "77 node androdex-bridge/bin/cli.js __daemon-run\n55 node androdex-bridge/bin/androdex.js run-service\n";
        }
        throw new Error(`unexpected command: ${command}`);
      },
    });

    assert.equal(status.pairingFreshness, "expired");
    assert.equal(status.refreshEnabled, false);
    assert.equal(status.duplicateBridgeProcesses.length, 1);
    assert.equal(status.duplicateBridgeProcesses[0].pid, 77);
  });
});

test("startMacOSBridgeService preserves enabled desktop refresh when the env flag is absent", async () => {
  await withTempDaemonEnv(async ({ rootDir }) => {
    fs.writeFileSync(
      path.join(rootDir, "daemon-config.json"),
      JSON.stringify({
        relayUrl: "wss://relay.example/relay",
        refreshEnabled: true,
      }, null, 2)
    );

    await startMacOSBridgeService({
      platform: "darwin",
      env: {
        HOME: rootDir,
        ANDRODEX_DEVICE_STATE_DIR: rootDir,
        PATH: "/usr/bin:/bin",
        ANDRODEX_RELAY: "wss://relay.example/relay",
      },
      fsImpl: fs,
      execFileSyncImpl() {},
      waitForPairing: false,
      nodePath: "/usr/local/bin/node",
      cliPath: "/tmp/androdex/bin/androdex.js",
    });

    const daemonConfig = JSON.parse(fs.readFileSync(path.join(rootDir, "daemon-config.json"), "utf8"));
    const plist = fs.readFileSync(path.join(rootDir, "Library", "LaunchAgents", "io.androdex.bridge.plist"), "utf8");

    assert.equal(daemonConfig.refreshEnabled, true);
    assert.match(plist, /<key>ANDRODEX_REFRESH_ENABLED<\/key>/);
    assert.match(plist, /<string>true<\/string>/);
  });
});

async function withTempDaemonEnv(run) {
  const previousDir = process.env.ANDRODEX_DEVICE_STATE_DIR;
  const previousHome = process.env.HOME;
  const rootDir = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-launch-agent-"));
  process.env.ANDRODEX_DEVICE_STATE_DIR = rootDir;
  process.env.HOME = rootDir;

  try {
    return await run({ rootDir });
  } finally {
    if (previousDir === undefined) {
      delete process.env.ANDRODEX_DEVICE_STATE_DIR;
    } else {
      process.env.ANDRODEX_DEVICE_STATE_DIR = previousDir;
    }
    if (previousHome === undefined) {
      delete process.env.HOME;
    } else {
      process.env.HOME = previousHome;
    }
    fs.rmSync(rootDir, { recursive: true, force: true });
  }
}
