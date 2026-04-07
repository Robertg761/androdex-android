const test = require("node:test");
const assert = require("node:assert/strict");

const {
  createT3AttachRequirements,
  ensureLoopbackEndpoint,
  extractT3RuntimeMetadata,
  validateT3AttachConfig,
} = require("../src/runtime/t3-suitability");

test("ensureLoopbackEndpoint accepts loopback T3 endpoints", () => {
  const parsed = ensureLoopbackEndpoint("ws://127.0.0.1:7777");
  assert.equal(parsed.hostname, "127.0.0.1");
});

test("ensureLoopbackEndpoint rejects non-local T3 endpoints", () => {
  assert.throws(
    () => ensureLoopbackEndpoint("ws://example.com:7777"),
    /non-local T3 endpoint/i
  );
});

test("extractT3RuntimeMetadata reads state-root, auth mode, protocol, and declared methods", () => {
  const metadata = extractT3RuntimeMetadata({
    protocolVersion: "2026-04-01",
    authMode: "bootstrap-token",
    baseDir: "/tmp/t3-state",
    capabilities: {
      rpcMethods: [
        "server.getConfig",
        "orchestration.getSnapshot",
        "orchestration.replayEvents",
      ],
      subscriptions: [
        "subscribeOrchestrationDomainEvents",
      ],
    },
  });

  assert.equal(metadata.runtimeProtocolVersion, "2026-04-01");
  assert.equal(metadata.runtimeAuthMode, "bootstrap-token");
  assert.equal(metadata.runtimeStateRoot, "/tmp/t3-state");
  assert.deepEqual(metadata.declaredRpcMethods, [
    "server.getConfig",
    "orchestration.getSnapshot",
    "orchestration.replayEvents",
  ]);
  assert.deepEqual(metadata.declaredSubscriptions, [
    "subscribeOrchestrationDomainEvents",
  ]);
});

test("validateT3AttachConfig enforces explicit attach suitability requirements", () => {
  const requirements = createT3AttachRequirements({
    endpoint: "ws://127.0.0.1:7777",
    env: {
      ANDRODEX_T3_AUTH_MODE: "bootstrap-token",
      ANDRODEX_T3_BASE_DIR: "/tmp/t3-state",
      ANDRODEX_T3_PROTOCOL_VERSION: "2026-04-01",
    },
  });

  const metadata = validateT3AttachConfig({
    endpoint: "ws://127.0.0.1:7777",
    requirements,
    config: {
      protocolVersion: "2026-04-01",
      authMode: "bootstrap-token",
      baseDir: "/tmp/t3-state",
      rpcMethods: [
        "server.getConfig",
        "orchestration.getSnapshot",
        "orchestration.replayEvents",
      ],
      subscriptions: [
        "subscribeOrchestrationDomainEvents",
      ],
    },
  });

  assert.equal(metadata.runtimeStateRoot, "/tmp/t3-state");
});

test("validateT3AttachConfig rejects config that does not advertise required orchestration methods", () => {
  assert.throws(
    () => validateT3AttachConfig({
      endpoint: "ws://127.0.0.1:7777",
      config: {
        protocolVersion: "2026-04-01",
        authMode: "bootstrap-token",
        baseDir: "/tmp/t3-state",
        rpcMethods: [
          "server.getConfig",
        ],
        subscriptions: [],
      },
    }),
    /missing required RPC methods|required subscriptions/i
  );
});
