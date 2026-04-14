const test = require("node:test");
const assert = require("node:assert/strict");
const net = require("node:net");

const { updateBridgeRuntimeConfig } = require("../src/control-panel");

test("updateBridgeRuntimeConfig rejects an unavailable T3 target before persisting config", async () => {
  const daemonWrites = [];

  await assert.rejects(
    updateBridgeRuntimeConfig({
      targetKind: "t3-server",
      detectInstalledT3RuntimeImpl() {
        return {
          desktopSession: null,
        };
      },
      readDaemonConfigImpl() {
        return {};
      },
      resolveT3RuntimeEndpointImpl() {
        return {
          endpoint: "ws://127.0.0.1:3773/ws",
          authToken: "",
          source: "default-loopback",
        };
      },
      writeDaemonConfigImpl(nextConfig) {
        daemonWrites.push(nextConfig);
      },
    }),
    /T3/i,
  );

  assert.equal(daemonWrites.length, 0);
});

test("updateBridgeRuntimeConfig persists a validated explicit T3 target", async () => {
  const daemonWrites = [];
  const probeServer = net.createServer();

  await new Promise((resolve, reject) => {
    probeServer.once("error", reject);
    probeServer.listen(0, "127.0.0.1", resolve);
  });
  const address = probeServer.address();
  const endpoint = `ws://127.0.0.1:${address.port}/ws`;

  try {
    const nextConfig = await updateBridgeRuntimeConfig({
      endpoint,
      endpointAuthToken: "desktop-token",
      targetKind: "t3-server",
      detectInstalledT3RuntimeImpl() {
        return {
          desktopSession: null,
        };
      },
      readDaemonConfigImpl() {
        return {
          runtimeTarget: "codex-native",
          runtimeProvider: "codex",
        };
      },
      writeDaemonConfigImpl(nextConfigToWrite) {
        daemonWrites.push(nextConfigToWrite);
      },
    });

    assert.equal(nextConfig.runtimeTarget, "t3-server");
    assert.equal(nextConfig.runtimeEndpoint, endpoint);
    assert.equal(nextConfig.runtimeEndpointAuthToken, "desktop-token");
    assert.equal(daemonWrites.length, 1);
    assert.equal(daemonWrites[0].runtimeTarget, "t3-server");
    assert.equal(daemonWrites[0].runtimeEndpoint, endpoint);
  } finally {
    await new Promise((resolve) => probeServer.close(resolve));
  }
});

test("updateBridgeRuntimeConfig clears stale T3 endpoint metadata when switching back to Codex", async () => {
  const daemonWrites = [];

  const nextConfig = await updateBridgeRuntimeConfig({
    targetKind: "codex-native",
    readDaemonConfigImpl() {
      return {
        runtimeTarget: "t3-server",
        runtimeProvider: "t3code",
        runtimeEndpoint: "ws://127.0.0.1:3773/ws",
        runtimeEndpointAuthToken: "stale-token",
        runtimeEndpointSource: "explicit",
      };
    },
    writeDaemonConfigImpl(nextConfigToWrite) {
      daemonWrites.push(nextConfigToWrite);
    },
  });

  assert.equal(nextConfig.runtimeTarget, "codex-native");
  assert.equal(nextConfig.runtimeEndpoint, "");
  assert.equal(nextConfig.runtimeEndpointAuthToken, "");
  assert.equal(nextConfig.runtimeEndpointSource, "");
  assert.equal(daemonWrites.length, 1);
  assert.equal(daemonWrites[0].runtimeEndpoint, "");
  assert.equal(daemonWrites[0].runtimeEndpointAuthToken, "");
  assert.equal(daemonWrites[0].runtimeEndpointSource, "");
});
