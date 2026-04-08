const test = require("node:test");
const assert = require("node:assert/strict");

const { getBridgeDoctorReport, runBridgeDoctor } = require("../src/doctor");

test("getBridgeDoctorReport diagnoses missing T3 endpoint configuration", async () => {
  const report = await getBridgeDoctorReport({
    env: {
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
  });

  assert.equal(report.runtimeTarget, "t3-server");
  assert.equal(report.t3Availability.reasonCode, "missing-endpoint");
  assert.match(report.recommendations[0], /ANDRODEX_T3_ENDPOINT/);
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
    detectCommandImpl() {
      return {
        available: false,
        path: "",
      };
    },
  });

  assert.equal(report.endpointProbe.reachable, true);
  assert.equal(report.tools.bun.available, false);
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
    detectCommandImpl() {
      return {
        available: false,
        path: "",
      };
    },
  });

  assert.ok(messages.some((message) => message.includes("Doctor runtime target: t3-server")));
  assert.ok(messages.some((message) => message.includes("T3 probe: unreachable (ECONNREFUSED)")));
  assert.ok(messages.some((message) => message.includes("Bun: not found")));
  assert.ok(messages.some((message) => message.includes("Start T3 locally")));
});
