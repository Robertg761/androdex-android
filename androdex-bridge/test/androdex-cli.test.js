const test = require("node:test");
const assert = require("node:assert/strict");
const { execFileSync } = require("child_process");
const path = require("path");
const { version } = require("../package.json");
const { main } = require("../bin/cli");

test("androdex --version prints the package version", () => {
  const cliPath = path.join(__dirname, "..", "bin", "androdex.js");
  const output = execFileSync(process.execPath, [cliPath, "--version"], {
    encoding: "utf8",
  }).trim();

  assert.equal(output, version);
});

test("androdex restart reuses the macOS service start flow", async () => {
  const calls = [];
  const messages = [];

  await main({
    argv: ["node", "androdex", "restart"],
    platform: "darwin",
    consoleImpl: {
      log(message) {
        messages.push(message);
      },
      error(message) {
        messages.push(message);
      },
    },
    exitImpl(code) {
      throw new Error(`unexpected exit ${code}`);
    },
    deps: {
      readBridgeConfig() {
        calls.push("read-config");
      },
      async startMacOSBridgeService(options) {
        calls.push(["start-service", options]);
      },
    },
  });

  assert.deepEqual(calls, [
    "read-config",
    ["start-service", { waitForPairing: false }],
  ]);
  assert.deepEqual(messages, [
    "[androdex] macOS bridge service restarted.",
  ]);
});

test("androdex up waits for pairing and binds the current cwd", async () => {
  const calls = [];

  await main({
    argv: ["node", "androdex", "up"],
    platform: "darwin",
    exitImpl(code) {
      throw new Error(`unexpected exit ${code}`);
    },
    deps: {
      readBridgeConfig() {
        calls.push("read-config");
      },
      async startMacOSBridgeService(options) {
        calls.push(["start-service", options]);
        return {
          pairingSession: {
            pairingPayload: { sessionId: "session-1" },
          },
        };
      },
      printMacOSBridgePairingQr(options) {
        calls.push(["print-qr", options]);
      },
    },
  });

  assert.equal(calls[0], "read-config");
  assert.deepEqual(calls[1], [
    "start-service",
    { waitForPairing: true, activeCwd: process.cwd() },
  ]);
  assert.deepEqual(calls[2], [
    "print-qr",
    {
      pairingSession: {
        pairingPayload: { sessionId: "session-1" },
      },
    },
  ]);
});

test("androdex run uses the saved foreground bridge config when available", async () => {
  const calls = [];

  await main({
    argv: ["node", "androdex", "run"],
    platform: "darwin",
    exitImpl(code) {
      throw new Error(`unexpected exit ${code}`);
    },
    deps: {
      readForegroundBridgeConfig() {
        calls.push("read-foreground-config");
        return {
          runtimeTarget: "t3-server",
          runtimeEndpoint: "ws://127.0.0.1:3783/ws",
        };
      },
      startBridge(options) {
        calls.push(["start-bridge", options]);
      },
    },
  });

  assert.deepEqual(calls, [
    "read-foreground-config",
    ["start-bridge", {
      config: {
        runtimeTarget: "t3-server",
        runtimeEndpoint: "ws://127.0.0.1:3783/ws",
      },
    }],
  ]);
});

test("androdex doctor runs the diagnostic helper", async () => {
  const calls = [];

  await main({
    argv: ["node", "androdex", "doctor"],
    platform: "darwin",
    exitImpl(code) {
      throw new Error(`unexpected exit ${code}`);
    },
    deps: {
      async runBridgeDoctor(options) {
        calls.push(options);
      },
    },
  });

  assert.equal(calls.length, 1);
  assert.ok(calls[0]);
  assert.equal(typeof calls[0].consoleImpl.log, "function");
});

test("macOS-only commands fail early on non-darwin platforms", async () => {
  const messages = [];

  await main({
    argv: ["node", "androdex", "status"],
    platform: "linux",
    consoleImpl: {
      log(message) {
        messages.push(message);
      },
      error(message) {
        messages.push(message);
      },
    },
    exitImpl(code) {
      throw new Error(`exit:${code}`);
    },
    deps: {},
  }).then(() => {
    throw new Error("expected status to exit");
  }).catch((error) => {
    assert.equal(error.message, "exit:1");
  });

  assert.deepEqual(messages, [
    "[androdex] `status` is only available on macOS right now.",
  ]);
});
