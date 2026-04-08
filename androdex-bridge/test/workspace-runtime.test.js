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
  assert.equal(recordedAdapterCalls[0].endpoint, "ws://127.0.0.1:57816");
  assert.equal(recordedAdapterCalls[0].endpointAuthToken, "secret-token");
  assert.equal(recordedAdapterCalls[0].cwd, workspaceDir);
  assert.equal(recordedAdapterCalls[0].endpoint.includes("token="), false);
  assert.ok(daemonWrites.some((entry) => entry.activeCwd === workspaceDir));
});
