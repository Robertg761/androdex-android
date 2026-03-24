const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("path");
const { EventEmitter } = require("events");

const hostRuntimePath = path.resolve(__dirname, "../src/host-runtime.js");

class FakeWebSocket extends EventEmitter {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSED = 3;
  static instances = [];

  constructor(url, options) {
    super();
    this.url = url;
    this.options = options;
    this.readyState = FakeWebSocket.CONNECTING;
    this.sent = [];
    FakeWebSocket.instances.push(this);
  }

  send(message) {
    this.sent.push(message);
  }

  open() {
    this.readyState = FakeWebSocket.OPEN;
    this.emit("open");
  }

  close(code = 1000) {
    this.readyState = FakeWebSocket.CLOSED;
    this.emit("close", code);
  }

  terminate() {
    this.readyState = FakeWebSocket.CLOSED;
    this.emit("close", 1006);
  }
}

function loadHostRuntime() {
  FakeWebSocket.instances = [];
  delete require.cache[hostRuntimePath];

  const stubbedModules = new Map([
    [path.resolve(__dirname, "../src/codex-desktop-refresher.js"), {
      CodexDesktopRefresher: class {
        handleTransportReset() {}
        handleInbound() {}
        handleOutbound() {}
      },
      readBridgeConfig({ env }) {
        return {
          relayUrl: env.ANDRODEX_RELAY,
          refreshEnabled: false,
          refreshDebounceMs: 0,
          refreshCommand: "",
          codexBundleId: "",
          codexAppPath: "",
          codexEndpoint: "",
        };
      },
    }],
    [path.resolve(__dirname, "../src/codex-transport.js"), {
      createCodexTransport() {
        return {
          onError() {},
          onMessage() {},
          onClose() {},
          shutdown() {},
          send() {},
          describe() { return "codex"; },
        };
      },
    }],
    [path.resolve(__dirname, "../src/session-state.js"), {
      rememberActiveThread() {},
    }],
    [path.resolve(__dirname, "../src/git-handler.js"), {
      handleGitRequest() {
        return false;
      },
    }],
    [path.resolve(__dirname, "../src/workspace-handler.js"), {
      handleWorkspaceRequest() {
        return false;
      },
    }],
    [path.resolve(__dirname, "../src/secure-device-state.js"), {
      loadOrCreateBridgeDeviceState() {
        return {
          hostId: "host-123",
          macDeviceId: "mac-123",
          trustedPhones: {},
        };
      },
    }],
    [path.resolve(__dirname, "../src/secure-transport.js"), {
      createBridgeSecureTransport() {
        return {
          bindLiveSendWireMessage() {},
          createPairingPayload() {
            return { hostId: "host-123", relay: "wss://relay.example/relay" };
          },
          getCurrentDeviceState() {
            return {
              macDeviceId: "mac-123",
              trustedPhones: {},
            };
          },
          handleIncomingWireMessage() {
            return false;
          },
          queueOutboundApplicationMessage() {},
        };
      },
    }],
    [path.resolve(__dirname, "../src/daemon-store.js"), {
      readDaemonRuntimeState() {
        return {
          lastActiveCwd: "",
          recentWorkspaces: [],
        };
      },
      writeDaemonRuntimeState() {},
    }],
  ]);

  for (const [modulePath, exports] of stubbedModules.entries()) {
    require.cache[modulePath] = {
      id: modulePath,
      filename: modulePath,
      loaded: true,
      exports,
    };
  }

  return require(hostRuntimePath).HostRuntime;
}

function createTimerHarness() {
  const timers = [];
  return {
    timers,
    setTimeoutFn(fn, delayMs) {
      const timer = {
        fn,
        delayMs,
        cleared: false,
      };
      timers.push(timer);
      return timer;
    },
    clearTimeoutFn(timer) {
      if (timer) {
        timer.cleared = true;
      }
    },
    nextTimer(delayMs) {
      return timers.find((timer) => timer.delayMs === delayMs && !timer.cleared);
    },
  };
}

test("ignores stale relay socket events after a newer socket becomes current", () => {
  const HostRuntime = loadHostRuntime();
  const timerHarness = createTimerHarness();
  const runtime = new HostRuntime({
    env: { ANDRODEX_RELAY: "wss://relay.example/relay" },
    WebSocketImpl: FakeWebSocket,
    setTimeoutFn: timerHarness.setTimeoutFn,
    clearTimeoutFn: timerHarness.clearTimeoutFn,
  });

  runtime.connectRelay();
  const staleSocket = FakeWebSocket.instances[0];
  runtime.socket = null;
  runtime.connectRelay();
  const freshSocket = FakeWebSocket.instances[1];
  freshSocket.open();

  staleSocket.emit("error", new Error("stale failure"));

  assert.equal(runtime.socket, freshSocket);
  assert.equal(runtime.relayStatus, "connected");
  assert.equal(timerHarness.nextTimer(1_000), undefined);
});

test("times out stuck relay connects and schedules a retry", () => {
  const HostRuntime = loadHostRuntime();
  const timerHarness = createTimerHarness();
  const runtime = new HostRuntime({
    env: { ANDRODEX_RELAY: "wss://relay.example/relay" },
    WebSocketImpl: FakeWebSocket,
    setTimeoutFn: timerHarness.setTimeoutFn,
    clearTimeoutFn: timerHarness.clearTimeoutFn,
    relayConnectTimeoutMs: 25,
  });

  runtime.connectRelay();
  const firstSocket = FakeWebSocket.instances[0];
  const connectTimeout = timerHarness.nextTimer(25);
  assert.ok(connectTimeout, "expected a connect timeout to be armed");

  connectTimeout.fn();

  assert.equal(firstSocket.readyState, FakeWebSocket.CLOSED);
  const reconnectTimer = timerHarness.nextTimer(1_000);
  assert.ok(reconnectTimer, "expected a reconnect attempt after the timeout");
  reconnectTimer.fn();

  assert.equal(FakeWebSocket.instances.length, 2);
  assert.equal(runtime.relayStatus, "connecting");
});

test("treats mac replacement close as recoverable and retries the relay", () => {
  const HostRuntime = loadHostRuntime();
  const timerHarness = createTimerHarness();
  const runtime = new HostRuntime({
    env: { ANDRODEX_RELAY: "wss://relay.example/relay" },
    WebSocketImpl: FakeWebSocket,
    setTimeoutFn: timerHarness.setTimeoutFn,
    clearTimeoutFn: timerHarness.clearTimeoutFn,
  });

  runtime.connectRelay();
  const socket = FakeWebSocket.instances[0];
  socket.open();

  socket.close(4001);

  const reconnectTimer = timerHarness.nextTimer(1_000);
  assert.ok(reconnectTimer, "expected a reconnect attempt after a replacement close");
  reconnectTimer.fn();

  assert.equal(FakeWebSocket.instances.length, 2);
  assert.equal(runtime.relayStatus, "connecting");
});
