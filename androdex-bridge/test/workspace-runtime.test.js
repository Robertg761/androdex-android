const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const net = require("node:net");
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
  const probeServer = net.createServer();

  fs.mkdirSync(workspaceDir, { recursive: true });

  await new Promise((resolve, reject) => {
    probeServer.once("error", reject);
    probeServer.listen(0, "127.0.0.1", resolve);
  });
  const address = probeServer.address();
  const t3Endpoint = `ws://127.0.0.1:${address.port}/ws`;

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
      runtimeEndpoint: t3Endpoint,
      runtimeEndpointSource: "explicit",
    });
    await runtime.shutdown();
  } finally {
    await new Promise((resolve) => probeServer.close(resolve));
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
  const probeServer = net.createServer();

  fs.mkdirSync(workspaceDir, { recursive: true });
  fs.mkdirSync(path.join(tempRoot, ".t3", "userdata"), { recursive: true });

  await new Promise((resolve, reject) => {
    probeServer.once("error", reject);
    probeServer.listen(0, "127.0.0.1", resolve);
  });
  const address = probeServer.address();
  const t3Endpoint = `ws://127.0.0.1:${address.port}/ws`;

  fs.writeFileSync(
    path.join(tempRoot, ".t3", "userdata", "runtime-session.json"),
    JSON.stringify({
      version: 1,
      source: "desktop",
      transport: "websocket",
      baseUrl: t3Endpoint,
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
    await new Promise((resolve) => probeServer.close(resolve));
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }

  assert.equal(recordedAdapterCalls.length, 2);
  assert.equal(recordedAdapterCalls[1].targetKind, "t3-server");
  assert.equal(recordedAdapterCalls[1].endpoint, t3Endpoint);
  assert.equal(recordedAdapterCalls[1].endpointAuthToken, "androdex-live-test-token");
});

test("workspace runtime clears stale T3 endpoint metadata when switching back to Codex", async () => {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-runtime-switch-cleanup-"));
  const workspaceDir = path.join(tempRoot, "workspace");
  const daemonWrites = [];
  const probeServer = net.createServer();

  fs.mkdirSync(workspaceDir, { recursive: true });

  await new Promise((resolve, reject) => {
    probeServer.once("error", reject);
    probeServer.listen(0, "127.0.0.1", resolve);
  });
  const address = probeServer.address();
  const t3Endpoint = `ws://127.0.0.1:${address.port}/ws`;

  try {
    const runtime = createWorkspaceRuntime({
      config: {
        activeCwd: workspaceDir,
        recentWorkspaces: [workspaceDir],
        runtimeTarget: "t3-server",
        runtimeProvider: "t3code",
        runtimeEndpoint: t3Endpoint,
        runtimeEndpointAuthToken: "desktop-token",
        runtimeEndpointSource: "explicit",
      },
      createRuntimeAdapterImpl(options) {
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
        return {
          runtimeTarget: "t3-server",
          runtimeProvider: "t3code",
          runtimeEndpoint: t3Endpoint,
          runtimeEndpointAuthToken: "desktop-token",
          runtimeEndpointSource: "explicit",
        };
      },
      writeDaemonConfigImpl(nextConfig) {
        daemonWrites.push(nextConfig);
      },
    });

    await runtime.activateWorkspace({ cwd: workspaceDir });
    await runtime.updateRuntimeConfig({
      runtimeTarget: "codex-native",
      runtimeProvider: "codex",
    });
    await runtime.shutdown();
  } finally {
    await new Promise((resolve) => probeServer.close(resolve));
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }

  const codexWrite = daemonWrites.findLast((entry) => entry.runtimeTarget === "codex-native");
  assert.ok(codexWrite);
  assert.equal(codexWrite.runtimeEndpoint, "");
  assert.equal(codexWrite.runtimeEndpointAuthToken, "");
  assert.equal(codexWrite.runtimeEndpointSource, "");
});

test("workspace runtime rejects unavailable T3 switches before persisting config or restarting the active workspace", async () => {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-runtime-switch-unavailable-"));
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
    await assert.rejects(
      runtime.updateRuntimeConfig({
        runtimeTarget: "t3-server",
        runtimeProvider: "t3code",
      }),
      /T3/i,
    );

    assert.equal(runtime.getRuntimeTarget(), "codex-native");
    assert.equal(runtime.hasActiveWorkspace(), true);
    assert.equal(recordedAdapterCalls.length, 1);
    assert.equal(daemonWrites.some((entry) => entry.runtimeTarget === "t3-server"), false);

    await runtime.shutdown();
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
});

test("workspace runtime restores the previous runtime when a validated switch still fails during activation", async () => {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-runtime-switch-rollback-"));
  const workspaceDir = path.join(tempRoot, "workspace");
  const recordedAdapterCalls = [];
  const daemonWrites = [];
  const probeServer = net.createServer();

  fs.mkdirSync(workspaceDir, { recursive: true });

  await new Promise((resolve, reject) => {
    probeServer.once("error", reject);
    probeServer.listen(0, "127.0.0.1", resolve);
  });
  const address = probeServer.address();
  const t3Endpoint = `ws://127.0.0.1:${address.port}/ws`;

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
            if (options.targetKind === "t3-server") {
              return Promise.reject(new Error("T3 runtime handshake failed."));
            }
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
    await assert.rejects(
      runtime.updateRuntimeConfig({
        runtimeTarget: "t3-server",
        runtimeProvider: "t3code",
        runtimeEndpoint: t3Endpoint,
        runtimeEndpointSource: "explicit",
      }),
      /bridge stayed on Codex Native/i,
    );

    assert.equal(runtime.getRuntimeTarget(), "codex-native");
    assert.equal(runtime.hasActiveWorkspace(), true);
    assert.equal(recordedAdapterCalls.length, 3);
    assert.equal(recordedAdapterCalls[1].targetKind, "t3-server");
    assert.equal(recordedAdapterCalls[2].targetKind, "codex-native");
    assert.equal(daemonWrites.some((entry) => entry.runtimeTarget === "t3-server"), false);

    await runtime.shutdown();
  } finally {
    await new Promise((resolve) => probeServer.close(resolve));
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
});
