const test = require("node:test");
const assert = require("node:assert/strict");

const { createT3EndpointTransport } = require("../src/runtime/t3-protocol");

const trackedTransports = new Set();

test.after(() => {
  for (const transport of trackedTransports) {
    transport.shutdown();
  }
  trackedTransports.clear();
});

function createTrackedTransport(options) {
  const transport = createT3EndpointTransport(options);
  trackedTransports.add(transport);
  return transport;
}

class FakeWebSocket {
  static OPEN = 1;
  static CONNECTING = 0;
  static CLOSED = 3;

  constructor(url) {
    this.url = url;
    this.readyState = FakeWebSocket.CONNECTING;
    this.handlers = new Map();
    this.sentMessages = [];
    queueMicrotask(() => {
      this.readyState = FakeWebSocket.OPEN;
      this.emit("open", { type: "open" });
    });
  }

  on(event, handler) {
    this.addEventListener(event, handler);
  }

  addEventListener(event, handler, options = {}) {
    const listeners = this.handlers.get(event) ?? [];
    listeners.push({
      handler,
      once: options?.once === true,
    });
    this.handlers.set(event, listeners);
  }

  removeEventListener(event, handler) {
    const listeners = this.handlers.get(event) ?? [];
    this.handlers.set(
      event,
      listeners.filter((entry) => entry.handler !== handler)
    );
  }

  send(message) {
    this.sentMessages.push(message);
  }

  close(code = 1000, reason = "") {
    this.readyState = FakeWebSocket.CLOSED;
    this.emit("close", { code, reason });
  }

  serverMessage(data) {
    this.emit("message", {
      data: typeof data === "string" ? data : JSON.stringify(data),
    });
  }

  emit(event, payload) {
    const listeners = [...(this.handlers.get(event) ?? [])];
    for (const entry of listeners) {
      entry.handler(payload);
      if (entry.once) {
        this.removeEventListener(event, entry.handler);
      }
    }
  }
}

test("T3 protocol transport sends the expected request envelope", async () => {
  let capturedSocket = null;
  class CapturingWebSocket extends FakeWebSocket {
    constructor(url) {
      super(url);
      capturedSocket = this;
    }
  }

  const transport = createTrackedTransport({
    endpoint: "ws://127.0.0.1:7777",
    WebSocketImpl: CapturingWebSocket,
  });

  await transport.whenReady();
  const responsePromise = transport.request("server.getConfig", {});
  await new Promise((resolve) => setTimeout(resolve, 0));

  assert.ok(capturedSocket);
  const sentRequest = JSON.parse(capturedSocket.sentMessages[0]);
  assert.equal(sentRequest._tag, "Request");
  assert.match(sentRequest.id, /^\d+$/);
  assert.equal(sentRequest.tag, "server.getConfig");
  assert.deepEqual(sentRequest.payload, {});
  assert.deepEqual(sentRequest.headers, []);

  capturedSocket.serverMessage({
    _tag: "Exit",
    requestId: sentRequest.id,
    exit: {
      _tag: "Success",
      value: {
        protocolVersion: "2026-04-01",
      },
    },
  });

  await assert.doesNotReject(responsePromise);
  assert.deepEqual(await responsePromise, {
    protocolVersion: "2026-04-01",
  });
});

test("T3 protocol transport rejects failure exits with a readable message", async () => {
  let capturedSocket = null;
  class CapturingWebSocket extends FakeWebSocket {
    constructor(url) {
      super(url);
      capturedSocket = this;
    }
  }

  const transport = createTrackedTransport({
    endpoint: "ws://127.0.0.1:7777",
    WebSocketImpl: CapturingWebSocket,
  });

  await transport.whenReady();
  const responsePromise = transport.request("orchestration.getSnapshot", {});
  await new Promise((resolve) => setTimeout(resolve, 0));

  const sentRequest = JSON.parse(capturedSocket.sentMessages[0]);
  capturedSocket.serverMessage({
    _tag: "Exit",
    requestId: sentRequest.id,
    exit: {
      _tag: "Failure",
      cause: {
        message: "Snapshot unavailable",
      },
    },
  });

  await assert.rejects(responsePromise, /snapshot unavailable/i);
});

test("T3 protocol transport delivers stream chunks and completes on exit", async () => {
  let capturedSocket = null;
  class CapturingWebSocket extends FakeWebSocket {
    constructor(url) {
      super(url);
      capturedSocket = this;
    }
  }

  const transport = createTrackedTransport({
    endpoint: "ws://127.0.0.1:7777",
    WebSocketImpl: CapturingWebSocket,
  });

  await transport.whenReady();
  const received = [];
  let ended = false;
  transport.subscribe("subscribeOrchestrationDomainEvents", {}, {
    onValue(value) {
      received.push(value);
    },
    onEnd() {
      ended = true;
    },
  });
  await new Promise((resolve) => setTimeout(resolve, 0));

  const sentRequest = JSON.parse(capturedSocket.sentMessages[0]);
  capturedSocket.serverMessage({
    _tag: "Chunk",
    requestId: sentRequest.id,
    values: [
      { sequence: 11, type: "thread.created" },
      { sequence: 12, type: "thread.updated" },
    ],
  });
  capturedSocket.serverMessage({
    _tag: "Exit",
    requestId: sentRequest.id,
    exit: {
      _tag: "Success",
      value: null,
    },
  });

  await new Promise((resolve) => setTimeout(resolve, 0));

  const ackRequest = JSON.parse(capturedSocket.sentMessages[1]);
  assert.deepEqual(received, [
    { sequence: 11, type: "thread.created" },
    { sequence: 12, type: "thread.updated" },
  ]);
  assert.equal(ended, true);
  assert.deepEqual(ackRequest, {
    _tag: "Ack",
    requestId: sentRequest.id,
  });
});

test("createT3EndpointTransport appends auth tokens to the live websocket URL without changing describe()", async () => {
  let capturedSocket = null;
  class CapturingWebSocket extends FakeWebSocket {
    constructor(url) {
      super(url);
      capturedSocket = this;
    }
  }

  const transport = createTrackedTransport({
    endpoint: "ws://127.0.0.1:57816",
    authToken: "secret-token",
    WebSocketImpl: CapturingWebSocket,
  });

  await transport.whenReady();

  assert.ok(capturedSocket);
  assert.equal(capturedSocket.url, "ws://127.0.0.1:57816/?token=secret-token");
  assert.equal(transport.describe(), "ws://127.0.0.1:57816");
});
