const test = require("node:test");
const assert = require("node:assert/strict");

const {
  buildAppServerRestartExpression,
  buildNavigateToRouteExpression,
  resolveTrustedTargetHostId,
  resolveWindowsRemoteDebuggingPort,
  selectTrustedCodexRendererTarget,
} = require("../src/codex-desktop-windows-devtools");

test("selectTrustedCodexRendererTarget prefers the trusted index page and keeps its host id", () => {
  const target = selectTrustedCodexRendererTarget([
    {
      id: "worker-1",
      type: "worker",
      url: "",
      webSocketDebuggerUrl: "ws://127.0.0.1:9333/devtools/page/worker-1",
    },
    {
      id: "page-1",
      type: "page",
      url: "app://-/index.html?hostId=local",
      webSocketDebuggerUrl: "ws://127.0.0.1:9333/devtools/page/page-1",
    },
  ]);

  assert.equal(target?.id, "page-1");
  assert.equal(resolveTrustedTargetHostId(target), "local");
});

test("resolveTrustedTargetHostId falls back to local when the target url has no host id", () => {
  assert.equal(resolveTrustedTargetHostId({ url: "app://-/index.html" }), "local");
  assert.equal(resolveTrustedTargetHostId({ url: "" }), "local");
});

test("buildAppServerRestartExpression includes the trusted host id literal", () => {
  assert.equal(
    buildAppServerRestartExpression("local"),
    'window.electronBridge.sendMessageFromView({ type: "codex-app-server-restart", hostId: "local" })'
  );
});

test("buildNavigateToRouteExpression dispatches a trusted in-app route navigation", () => {
  assert.equal(
    buildNavigateToRouteExpression("/settings"),
    'window.postMessage({ type: "navigate-to-route", path: "/settings" }, window.location.origin)'
  );
});

test("resolveWindowsRemoteDebuggingPort keeps valid ports and normalizes invalid values", () => {
  assert.equal(resolveWindowsRemoteDebuggingPort(9444), 9444);
  assert.equal(resolveWindowsRemoteDebuggingPort("0"), 9333);
  assert.equal(resolveWindowsRemoteDebuggingPort("not-a-port"), 9333);
});
