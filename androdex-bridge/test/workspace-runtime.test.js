const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { readBridgeConfig } = require("../src/codex-desktop-refresher");
const { createWorkspaceRuntime } = require("../src/workspace/runtime");

test("workspace runtime forwards trusted desktop runtime-session config into the T3 adapter without embedding the token in the endpoint", async () => {
  const originalHome = process.env.HOME;
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-workspace-runtime-"));
  const workspaceDir = path.join(tempRoot, "workspace");
  const recordedAdapterCalls = [];
  const daemonWrites = [];

  fs.mkdirSync(workspaceDir, { recursive: true });
  fs.mkdirSync(path.join(tempRoot, ".t3", "userdata"), { recursive: true });
  fs.writeFileSync(
    path.join(tempRoot, ".t3", "userdata", "runtime-session.json"),
    JSON.stringify({
      version: 1,
      source: "desktop",
      transport: "websocket",
      baseUrl: "ws://127.0.0.1:57816",
      authToken: "secret-token",
      backendPid: process.pid,
      stateDir: path.join(tempRoot, ".t3", "userdata"),
      appRunId: "run-123",
      updatedAt: "2026-04-08T00:00:00.000Z",
    }),
    "utf8"
  );

  try {
    process.env.HOME = tempRoot;
    const config = readBridgeConfig({
      env: {
        HOME: tempRoot,
        ANDRODEX_RUNTIME_TARGET: "t3-server",
      },
    });

    const runtime = createWorkspaceRuntime({
      config,
      createRuntimeAdapterImpl(options) {
        recordedAdapterCalls.push(options);
        return {
          getRuntimeMetadata() {
            return {
              runtimeTarget: options.targetKind,
              runtimeEndpoint: options.endpoint,
            };
          },
          onClose() {},
          onError() {},
          onMessage() {},
          onMetadata() {},
          send() {},
          shutdown() {},
          whenReady() {
            return Promise.resolve();
          },
        };
      },
      readDaemonConfigImpl() {
        return {};
      },
      writeDaemonConfigImpl(nextConfig) {
        daemonWrites.push(nextConfig);
      },
    });

    await runtime.activateWorkspace({ cwd: workspaceDir });
    await runtime.shutdown();
  } finally {
    if (originalHome === undefined) {
      delete process.env.HOME;
    } else {
      process.env.HOME = originalHome;
    }
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }

  assert.equal(recordedAdapterCalls.length, 1);
  assert.equal(recordedAdapterCalls[0].targetKind, "t3-server");
  assert.equal(recordedAdapterCalls[0].endpoint, "ws://127.0.0.1:57816/ws");
  assert.equal(recordedAdapterCalls[0].endpointAuthToken, "secret-token");
  assert.equal(recordedAdapterCalls[0].cwd, workspaceDir);
  assert.equal(recordedAdapterCalls[0].endpoint.includes("token="), false);
  assert.ok(daemonWrites.some((entry) => entry.activeCwd === workspaceDir));
});

test("workspace runtime can switch runtime targets and restart the active workspace in place", async () => {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-runtime-switch-"));
  const workspaceDir = path.join(tempRoot, "workspace");
  const recordedAdapterCalls = [];
  const daemonWrites = [];

  fs.mkdirSync(workspaceDir, { recursive: true });

  try {
    const runtime = createWorkspaceRuntime({
      config: {
        activeCwd: workspaceDir,
        recentWorkspaces: [workspaceDir],
        runtimeTarget: "codex-native",
        runtimeProvider: "codex",
      },
      createRuntimeAdapterImpl(options) {
        recordedAdapterCalls.push(options);
        return {
          getRuntimeMetadata() {
            return {
              runtimeTarget: options.targetKind,
            };
          },
          onClose() {},
          onError() {},
          onMessage() {},
          onMetadata() {},
          send() {},
          shutdown() {},
          whenReady() {
            return Promise.resolve();
          },
        };
      },
      readDaemonConfigImpl() {
        return {};
      },
      writeDaemonConfigImpl(nextConfig) {
        daemonWrites.push(nextConfig);
      },
    });

    await runtime.activateWorkspace({ cwd: workspaceDir });
    await runtime.updateRuntimeConfig({
      runtimeTarget: "t3-server",
      runtimeProvider: "t3code",
    });
    await runtime.shutdown();
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }

  assert.equal(recordedAdapterCalls.length, 2);
  assert.equal(recordedAdapterCalls[0].targetKind, "codex-native");
  assert.equal(recordedAdapterCalls[1].targetKind, "t3-server");
  assert.ok(daemonWrites.some((entry) => entry.runtimeTarget === "t3-server"));
  assert.ok(daemonWrites.some((entry) => entry.activeCwd === workspaceDir));
});

test("workspace runtime falls back to the trusted T3 desktop session when a runtime-target switch has no explicit endpoint", async () => {
  const originalHome = process.env.HOME;
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-runtime-switch-discovery-"));
  const workspaceDir = path.join(tempRoot, "workspace");
  const recordedAdapterCalls = [];

  fs.mkdirSync(workspaceDir, { recursive: true });
  fs.mkdirSync(path.join(tempRoot, ".t3", "userdata"), { recursive: true });
  fs.writeFileSync(
    path.join(tempRoot, ".t3", "userdata", "runtime-session.json"),
    JSON.stringify({
      version: 1,
      source: "desktop",
      transport: "websocket",
      baseUrl: "ws://127.0.0.1:3783/ws",
      authToken: "androdex-live-test-token",
      backendPid: process.pid,
    }),
    "utf8"
  );

  try {
    process.env.HOME = tempRoot;
    const runtime = createWorkspaceRuntime({
      config: {
        activeCwd: workspaceDir,
        recentWorkspaces: [workspaceDir],
        runtimeTarget: "codex-native",
        runtimeProvider: "codex",
      },
      createRuntimeAdapterImpl(options) {
        recordedAdapterCalls.push(options);
        return {
          getRuntimeMetadata() {
            return {
              runtimeTarget: options.targetKind,
              runtimeEndpoint: options.endpoint,
            };
          },
          onClose() {},
          onError() {},
          onMessage() {},
          onMetadata() {},
          send() {},
          shutdown() {},
          whenReady() {
            return Promise.resolve();
          },
        };
      },
      readDaemonConfigImpl() {
        return {};
      },
      writeDaemonConfigImpl() {},
    });

    await runtime.activateWorkspace({ cwd: workspaceDir });
    await runtime.updateRuntimeConfig({
      runtimeTarget: "t3-server",
      runtimeProvider: "t3code",
    });
    await runtime.shutdown();
  } finally {
    if (originalHome === undefined) {
      delete process.env.HOME;
    } else {
      process.env.HOME = originalHome;
    }
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }

  assert.equal(recordedAdapterCalls.length, 2);
  assert.equal(recordedAdapterCalls[1].targetKind, "t3-server");
  assert.equal(recordedAdapterCalls[1].endpoint, "ws://127.0.0.1:3783/ws");
  assert.equal(recordedAdapterCalls[1].endpointAuthToken, "androdex-live-test-token");
});
