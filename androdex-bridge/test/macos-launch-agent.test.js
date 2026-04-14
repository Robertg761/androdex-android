const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");
const {
  buildLaunchAgentPlist,
  getMacOSBridgeServiceStatus,
  printMacOSBridgeServiceStatus,
  readForegroundBridgeConfig,
  resetMacOSBridgePairing,
  resolveLaunchAgentPlistPath,
  runMacOSBridgeService,
  startMacOSBridgeService,
  stopMacOSBridgeService,
  waitForPairingReadiness,
} = require("../src/macos-launch-agent");
const {
  readBridgeStatus,
  readPairingSession,
  writeDaemonConfig,
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
    writeBridgeStatus({
      state: "running",
      connectionStatus: "connected",
      pid: 55,
      runtimeTarget: "t3-server",
    });
    fs.writeFileSync(
      path.join(rootDir, "daemon-config.json"),
      JSON.stringify({
        refreshEnabled: true,
        runtimeTarget: "t3-server",
        runtimeEndpoint: "ws://127.0.0.1:3773/ws",
      }, null, 2)
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
      detectInstalledT3RuntimeImpl() {
        return {
          desktopAppInstalled: true,
          desktopAppPath: "/Applications/T3 Code (Alpha).app",
          cliInstalled: true,
          cliPath: "/opt/homebrew/bin/t3",
          desktopSession: {
            runtimeSessionPath: path.join(rootDir, ".t3", "userdata", "runtime-session.json"),
            endpoint: "ws://127.0.0.1:57816/ws",
            authEnabled: true,
            authToken: "secret-token",
            source: "runtime-session-file",
            descriptorStatus: "trusted",
            descriptorDetail: "",
          },
        };
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
    assert.equal(status.runtimeConfig.runtimeTarget, "t3-server");
    assert.equal(status.runtimeConfig.runtimeEndpoint, "ws://127.0.0.1:3773/ws");
    assert.equal(status.t3Availability.reasonCode, "attach-ready");
    assert.equal(status.t3Availability.endpointHost, "127.0.0.1");
    assert.equal(status.t3Runtime.desktopAppInstalled, true);
    assert.equal(status.t3Runtime.desktopSession.endpoint, "ws://127.0.0.1:57816/ws");
    assert.equal(status.t3Runtime.desktopSession.descriptorStatus, "trusted");
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

test("getMacOSBridgeServiceStatus falls back to the default loopback T3 endpoint when no explicit endpoint is stored", () => {
  withTempDaemonEnv(({ rootDir }) => {
    writeBridgeStatus({
      state: "error",
      connectionStatus: "error",
      pid: 99,
      runtimeTarget: "t3-server",
      runtimeAttachFailure: "missing T3 websocket endpoint",
    });
    fs.writeFileSync(
      path.join(rootDir, "daemon-config.json"),
      JSON.stringify({
        refreshEnabled: false,
        runtimeTarget: "t3-server",
      }, null, 2)
    );

    const status = getMacOSBridgeServiceStatus({
      platform: "darwin",
      env: { HOME: rootDir, ANDRODEX_DEVICE_STATE_DIR: rootDir },
      loadBridgeDeviceStateImpl() {
        return { trustedPhones: {} };
      },
      detectInstalledT3RuntimeImpl() {
        return {
          desktopAppInstalled: false,
          desktopAppPath: "",
          cliInstalled: false,
          cliPath: "",
          desktopSession: {
            runtimeSessionPath: path.join(rootDir, ".t3", "userdata", "runtime-session.json"),
            endpoint: "",
            authEnabled: null,
            authToken: "",
            source: "desktop-log",
            descriptorStatus: "missing",
            descriptorDetail: "",
          },
        };
      },
      execFileSyncImpl(command) {
        if (command === "launchctl") {
          return "pid = 99";
        }
        if (command === "pgrep") {
          return "";
        }
        throw new Error(`unexpected command: ${command}`);
      },
    });

    assert.equal(status.runtimeConfig.runtimeTarget, "t3-server");
    assert.equal(status.runtimeConfig.runtimeEndpoint, "ws://127.0.0.1:3773/ws");
    assert.equal(status.t3Availability.reasonCode, "attach-ready");
    assert.equal(status.t3Availability.runtimeAttachFailure, "missing T3 websocket endpoint");
  });
});

test("printMacOSBridgeServiceStatus includes installed T3 desktop session details when T3 is selected", () => {
  withTempDaemonEnv(({ rootDir }) => {
    writeBridgeStatus({
      state: "running",
      connectionStatus: "connected",
      pid: 55,
      runtimeTarget: "t3-server",
    });
    fs.writeFileSync(
      path.join(rootDir, "daemon-config.json"),
      JSON.stringify({
        refreshEnabled: true,
        runtimeTarget: "t3-server",
        runtimeEndpoint: "ws://127.0.0.1:3773/ws",
      }, null, 2)
    );

    const plistPath = path.join(rootDir, "Library", "LaunchAgents", "io.androdex.bridge.plist");
    fs.mkdirSync(path.dirname(plistPath), { recursive: true });
    fs.writeFileSync(plistPath, "plist");

    const messages = [];
    const originalConsoleLog = console.log;
    console.log = (message) => {
      messages.push(message);
    };

    try {
      printMacOSBridgeServiceStatus({
        platform: "darwin",
        env: { HOME: rootDir, ANDRODEX_DEVICE_STATE_DIR: rootDir },
        loadBridgeDeviceStateImpl() {
          return { trustedPhones: {} };
        },
        detectInstalledT3RuntimeImpl() {
          return {
            desktopAppInstalled: true,
            desktopAppPath: "/Applications/T3 Code (Alpha).app",
            cliInstalled: false,
            cliPath: "",
            desktopSession: {
              runtimeSessionPath: path.join(rootDir, ".t3", "userdata", "runtime-session.json"),
              endpoint: "ws://127.0.0.1:57816/ws",
              authEnabled: true,
              authToken: "secret-token",
              source: "runtime-session-file",
              descriptorStatus: "trusted",
              descriptorDetail: "",
            },
          };
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
    } finally {
      console.log = originalConsoleLog;
    }

    assert.ok(messages.some((message) => message.includes("Androdex Server install: desktop app at /Applications/T3 Code (Alpha).app")));
    assert.ok(messages.some((message) => message.includes("Androdex Server desktop session: ws://127.0.0.1:57816/ws (auth enabled)")));
    assert.ok(messages.some((message) => message.includes("Androdex Server desktop descriptor: trusted descriptor")));
  });
});

test("getMacOSBridgeServiceStatus prefers a coherent persisted runtime config over shell env overrides", () => {
  withTempDaemonEnv(({ rootDir }) => {
    writeBridgeStatus({
      state: "running",
      connectionStatus: "connected",
      pid: 77,
      runtimeTarget: "codex-native",
    });
    fs.writeFileSync(
      path.join(rootDir, "daemon-config.json"),
      JSON.stringify({
        refreshEnabled: false,
        runtimeTarget: "codex-native",
      }, null, 2)
    );

    const status = getMacOSBridgeServiceStatus({
      platform: "darwin",
      env: {
        HOME: rootDir,
        ANDRODEX_DEVICE_STATE_DIR: rootDir,
        ANDRODEX_RUNTIME_TARGET: "t3-server",
        ANDRODEX_T3_ENDPOINT: "ws://127.0.0.1:3773/ws",
      },
      loadBridgeDeviceStateImpl() {
        return { trustedPhones: {} };
      },
      execFileSyncImpl(command) {
        if (command === "launchctl") {
          return "pid = 77";
        }
        if (command === "pgrep") {
          return "";
        }
        throw new Error(`unexpected command: ${command}`);
      },
    });

    assert.equal(status.runtimeConfig.runtimeTarget, "codex-native");
    assert.equal(status.runtimeConfig.runtimeEndpoint, "");
    assert.equal(status.t3Availability.selected, false);
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

test("waitForPairingReadiness waits for the relay to connect before returning a fresh QR", async () => {
  await withTempDaemonEnv(async () => {
    const startedAt = Date.now();

    setTimeout(() => {
      writePairingSession({ sessionId: "session-ready" });
    }, 5);
    setTimeout(() => {
      writeBridgeStatus({ state: "running", connectionStatus: "connecting" });
    }, 10);
    setTimeout(() => {
      writeBridgeStatus({ state: "running", connectionStatus: "connected" });
    }, 30);

    const ready = await waitForPairingReadiness({
      env: process.env,
      fsImpl: fs,
      startedAt,
      timeoutMs: 500,
      intervalMs: 5,
    });

    assert.equal(ready.pairingSession?.pairingPayload?.sessionId, "session-ready");
    assert.equal(ready.bridgeStatus?.connectionStatus, "connected");
  });
});

test("waitForPairingReadiness times out when the relay never reaches connected", async () => {
  await withTempDaemonEnv(async () => {
    const startedAt = Date.now();

    writePairingSession({ sessionId: "session-stuck" });
    writeBridgeStatus({
      state: "running",
      connectionStatus: "disconnected",
      lastError: "Host relay registration failed.",
    });

    await assert.rejects(
      waitForPairingReadiness({
        env: process.env,
        fsImpl: fs,
        startedAt,
        timeoutMs: 50,
        intervalMs: 5,
      }),
      /Timed out waiting for the macOS bridge service to publish a ready pairing QR\..*Last relay note: Host relay registration failed\./
    );
  });
});

test("readForegroundBridgeConfig prefers daemon T3 endpoint state for foreground runs", async () => {
  await withTempDaemonEnv(async () => {
    writeDaemonConfig({
      relayUrl: "wss://relay.example/relay",
      runtimeTarget: "t3-server",
      runtimeProvider: "t3code",
      runtimeEndpoint: "ws://127.0.0.1:3783/ws",
      runtimeEndpointAuthToken: "test-token",
      activeCwd: "/tmp/workspace",
      recentWorkspaces: ["/tmp/workspace"],
    });

    const config = readForegroundBridgeConfig({
      env: {
        ...process.env,
        ANDRODEX_RUNTIME_TARGET: "codex-native",
      },
      fsImpl: fs,
    });

    assert.equal(config.runtimeTarget, "t3-server");
    assert.equal(config.runtimeProvider, "t3code");
    assert.equal(config.runtimeEndpoint, "ws://127.0.0.1:3783/ws");
    assert.equal(config.runtimeEndpointAuthToken, "test-token");
    assert.equal(config.activeCwd, "/tmp/workspace");
    assert.deepEqual(config.recentWorkspaces, ["/tmp/workspace"]);
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
