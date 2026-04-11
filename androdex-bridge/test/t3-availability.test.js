const test = require("node:test");
const assert = require("node:assert/strict");

const { inspectT3Availability } = require("../src/runtime/t3-availability");

test("inspectT3Availability reports attach-ready loopback websocket endpoints", () => {
  const availability = inspectT3Availability({
    runtimeTarget: "t3-server",
    runtimeEndpoint: "ws://127.0.0.1:3773/ws",
  });

  assert.equal(availability.selected, true);
  assert.equal(availability.configured, true);
  assert.equal(availability.reasonCode, "attach-ready");
  assert.equal(availability.endpointHost, "127.0.0.1");
  assert.equal(availability.endpointPort, "3773");
  assert.equal(availability.endpointPath, "/ws");
  assert.equal(availability.loopbackOnly, true);
});

test("inspectT3Availability flags missing or invalid T3 endpoint configuration", () => {
  const missingEndpoint = inspectT3Availability({
    runtimeTarget: "t3-server",
  });
  const invalidEndpoint = inspectT3Availability({
    runtimeTarget: "t3-server",
    runtimeEndpoint: "http://192.168.1.5:3773/ws",
  });

  assert.equal(missingEndpoint.reasonCode, "missing-endpoint");
  assert.match(missingEndpoint.detail, /ANDRODEX_T3_ENDPOINT/);
  assert.equal(invalidEndpoint.reasonCode, "unsupported-protocol");
  assert.match(invalidEndpoint.summary, /ws:\/\/ or wss:\/\//);
});

test("inspectT3Availability explains when a live desktop session is visible but the trusted auth handoff is missing", () => {
  const availability = inspectT3Availability({
    desktopSession: {
      endpoint: "ws://127.0.0.1:60206",
      authEnabled: true,
      authToken: "",
      descriptorStatus: "missing",
    },
    runtimeTarget: "t3-server",
    runtimeEndpoint: "ws://127.0.0.1:3773/ws",
    runtimeAttachFailure: "connect ECONNREFUSED 127.0.0.1:3773",
  });

  assert.equal(availability.reasonCode, "desktop-session-missing-auth-handoff");
  assert.equal(availability.discoveredDesktopEndpoint, "ws://127.0.0.1:60206");
  assert.match(availability.summary, /trusted auth handoff is missing/i);
  assert.match(availability.detail, /runtime-session\.json/);
});

test("inspectT3Availability keeps T3 attach optional when another runtime target is selected", () => {
  const availability = inspectT3Availability({
    runtimeTarget: "codex-native",
  });

  assert.equal(availability.selected, false);
  assert.equal(availability.reasonCode, "runtime-not-selected");
  assert.match(availability.summary, /T3 is optional/);
});
