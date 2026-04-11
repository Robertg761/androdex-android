const test = require("node:test");
const assert = require("node:assert/strict");

const { getBridgeDoctorReport, runBridgeDoctor } = require("../src/doctor");

test("getBridgeDoctorReport diagnoses missing T3 endpoint configuration", async () => {
  const report = await getBridgeDoctorReport({
    env: {
      HOME: "/tmp/androdex-no-t3-doctor",
      ANDRODEX_RUNTIME_TARGET: "t3-server",
    },
    getMacOSBridgeServiceStatusImpl() {
      return {
        runtimeConfig: {
          runtimeTarget: "t3-server",
          runtimeEndpoint: "",
        },
        bridgeStatus: {
          runtimeTarget: "t3-server",
        },
      };
    },
    detectInstalledT3RuntimeImpl() {
      return {
        desktopAppInstalled: false,
        desktopAppPath: "",
        cliInstalled: false,
        cliPath: "",
        desktopSession: {
          endpoint: "",
          authEnabled: null,
        },
      };
    },
    probeTcpEndpointImpl: async () => ({
      reachable: false,
      reasonCode: "ECONNREFUSED",
    }),
  });

  assert.equal(report.runtimeTarget, "t3-server");
  assert.equal(report.runtimeEndpointSource, "default-loopback");
  assert.equal(report.t3Availability.reasonCode, "attach-ready");
  assert.equal(report.endpointProbe.reachable, false);
  assert.match(report.recommendations[0], /No T3 listener answered/);
});

test("getBridgeDoctorReport probes attach-ready T3 endpoints and suggests restarting onto T3", async () => {
  const report = await getBridgeDoctorReport({
    env: {
      ANDRODEX_RUNTIME_TARGET: "t3-server",
      ANDRODEX_T3_ENDPOINT: "ws://127.0.0.1:3773/ws",
    },
    getMacOSBridgeServiceStatusImpl() {
      return {
        runtimeConfig: {
          runtimeTarget: "codex-native",
          runtimeEndpoint: "",
        },
        bridgeStatus: {
          runtimeTarget: "codex-native",
        },
      };
    },
    probeTcpEndpointImpl: async () => ({
      reachable: true,
      reasonCode: "reachable",
    }),
    detectInstalledT3RuntimeImpl() {
      return {
        desktopAppInstalled: true,
        desktopAppPath: "/Applications/T3 Code (Alpha).app",
        cliInstalled: false,
        cliPath: "",
        desktopSession: {
          endpoint: "",
          authEnabled: null,
        },
      };
    },
  });

  assert.equal(report.endpointProbe.reachable, true);
  assert.equal(report.tools.t3Runtime.desktopAppInstalled, true);
  assert.match(report.recommendations[0], /Restart or run `androdex up`/);
});

test("runBridgeDoctor prints actionable diagnostics for T3 companion mode", async () => {
  const messages = [];

  await runBridgeDoctor({
    env: {
      ANDRODEX_RUNTIME_TARGET: "t3-server",
      ANDRODEX_T3_ENDPOINT: "ws://127.0.0.1:3773/ws",
    },
    consoleImpl: {
      log(message) {
        messages.push(message);
      },
    },
    getMacOSBridgeServiceStatusImpl() {
      return {
        runtimeConfig: {
          runtimeTarget: "t3-server",
          runtimeEndpoint: "ws://127.0.0.1:3773/ws",
        },
        bridgeStatus: {
          runtimeTarget: "t3-server",
        },
      };
    },
    probeTcpEndpointImpl: async () => ({
      reachable: false,
      reasonCode: "ECONNREFUSED",
    }),
    detectInstalledT3RuntimeImpl() {
      return {
        desktopAppInstalled: true,
        desktopAppPath: "/Applications/T3 Code (Alpha).app",
        cliInstalled: false,
        cliPath: "",
        desktopSession: {
          endpoint: "",
          authEnabled: null,
        },
      };
    },
  });

  assert.ok(messages.some((message) => message.includes("Doctor runtime target: t3-server")));
  assert.ok(messages.some((message) => message.includes("T3 probe: unreachable (ECONNREFUSED)")));
  assert.ok(messages.some((message) => message.includes("Configured runtime endpoint: ws://127.0.0.1:3773/ws")));
  assert.ok(messages.some((message) => message.includes("T3 install: desktop app at /Applications/T3 Code (Alpha).app")));
  assert.ok(messages.some((message) => message.includes("Open /Applications/T3 Code (Alpha).app")));
});

test("getBridgeDoctorReport surfaces installed desktop-session endpoints and auth-handoff guidance", async () => {
  const report = await getBridgeDoctorReport({
    env: {
      ANDRODEX_RUNTIME_TARGET: "t3-server",
    },
    getMacOSBridgeServiceStatusImpl() {
      return {
        runtimeConfig: {
          runtimeTarget: "codex-native",
          runtimeEndpoint: "",
        },
        bridgeStatus: {
          runtimeTarget: "codex-native",
        },
      };
    },
    probeTcpEndpointImpl: async () => ({
      reachable: false,
      reasonCode: "ECONNREFUSED",
    }),
    detectInstalledT3RuntimeImpl() {
      return {
        desktopAppInstalled: true,
        desktopAppPath: "/Applications/T3 Code (Alpha).app",
        cliInstalled: false,
        cliPath: "",
        desktopSession: {
          endpoint: "ws://127.0.0.1:57816/ws",
          authEnabled: true,
        },
      };
    },
  });

  assert.equal(report.tools.t3Runtime.desktopSession.endpoint, "ws://127.0.0.1:57816/ws");
  assert.equal(report.desktopSessionProbe.reachable, false);
  assert.ok(report.recommendations.some((message) => message.includes("ws://127.0.0.1:57816/ws")));
  assert.ok(report.recommendations.some((message) => message.includes("auth handoff")));
});

test("getBridgeDoctorReport does not warn about missing auth handoff when a runtime-session descriptor is present", async () => {
  const report = await getBridgeDoctorReport({
    env: {
      ANDRODEX_RUNTIME_TARGET: "t3-server",
    },
    getMacOSBridgeServiceStatusImpl() {
      return {
        runtimeConfig: {
          runtimeTarget: "codex-native",
          runtimeEndpoint: "",
        },
        bridgeStatus: {
          runtimeTarget: "codex-native",
        },
      };
    },
    probeTcpEndpointImpl: async () => ({
      reachable: true,
      reasonCode: "reachable",
    }),
    detectInstalledT3RuntimeImpl() {
      return {
        desktopAppInstalled: true,
        desktopAppPath: "/Applications/T3 Code (Alpha).app",
        cliInstalled: false,
        cliPath: "",
        desktopSession: {
          runtimeSessionPath: "/tmp/home/.t3/userdata/runtime-session.json",
          endpoint: "ws://127.0.0.1:57816/ws",
          authEnabled: true,
          authToken: "secret-token",
          source: "runtime-session-file",
          descriptorStatus: "trusted",
          descriptorDetail: "",
        },
      };
    },
  });

  assert.equal(report.desktopSessionProbe.reachable, true);
  assert.equal(report.recommendations.some((message) => message.includes("auth handoff")), false);
});

test("getBridgeDoctorReport warns when the local runtime-session descriptor looks stale", async () => {
  const report = await getBridgeDoctorReport({
    env: {
      ANDRODEX_RUNTIME_TARGET: "t3-server",
    },
    getMacOSBridgeServiceStatusImpl() {
      return {
        runtimeConfig: {
          runtimeTarget: "codex-native",
          runtimeEndpoint: "",
        },
        bridgeStatus: {
          runtimeTarget: "codex-native",
        },
      };
    },
    probeTcpEndpointImpl: async () => ({
      reachable: true,
      reasonCode: "reachable",
    }),
    detectInstalledT3RuntimeImpl() {
      return {
        desktopAppInstalled: true,
        desktopAppPath: "/Applications/T3 Code (Alpha).app",
        cliInstalled: false,
        cliPath: "",
        desktopSession: {
          runtimeSessionPath: "/tmp/home/.t3/userdata/runtime-session.json",
          endpoint: "ws://127.0.0.1:57816/ws",
          authEnabled: true,
          authToken: "",
          source: "desktop-log",
          descriptorStatus: "stale-backend-pid",
          descriptorDetail: "Runtime session descriptor references a backend process (9999) that is no longer running.",
        },
      };
    },
  });

  assert.ok(report.recommendations.some((message) => message.includes("stale backend pid")));
  assert.ok(report.recommendations.some((message) => message.includes("runtime-session.json")));
});
