const test = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");
const WebSocket = require("ws");

const { createRelayServer } = require("../server");

test("desktop tunnel proxies HTTP requests through a stable route path", async (t) => {
  const { server, wss, desktopTunnelService } = createRelayServer();
  const sockets = [];
  t.after(() => {
    for (const socket of sockets) {
      try {
        socket.close();
      } catch {
        // Best effort only.
      }
    }
    desktopTunnelService.close();
    wss.close();
    server.close();
  });

  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();

  const hostSocket = new WebSocket(
    `ws://127.0.0.1:${port}/desktop-tunnel/connect?routeId=route-http&routeToken=token-http`,
  );
  sockets.push(hostSocket);
  const registeredPromise = waitForJsonMessage(
    hostSocket,
    (message) => message.type === "registered",
  );
  await waitForOpen(hostSocket);
  await registeredPromise;

  const hostRequestPromise = waitForJsonMessage(
    hostSocket,
    (message) => message.type === "http-request",
  );
  const responsePromise = requestText({
    method: "GET",
    port,
    path: "/desktop/route-http/.well-known/t3/environment",
  });
  const hostRequest = await hostRequestPromise;

  assert.equal(hostRequest.path, "/.well-known/t3/environment");

  hostSocket.send(
    JSON.stringify({
      type: "http-response",
      requestId: hostRequest.requestId,
      status: 200,
      headers: {
        "content-type": "application/json",
      },
      bodyBase64: Buffer.from(JSON.stringify({ ok: true, environmentId: "environment-1" })).toString(
        "base64",
      ),
    }),
  );

  const response = await responsePromise;
  assert.equal(response.statusCode, 200);
  assert.deepEqual(JSON.parse(response.body), {
    ok: true,
    environmentId: "environment-1",
  });
});

test("desktop tunnel proxies websocket frames through a stable route path", async (t) => {
  const { server, wss, desktopTunnelService } = createRelayServer();
  const sockets = [];
  t.after(() => {
    for (const socket of sockets) {
      try {
        socket.close();
      } catch {
        // Best effort only.
      }
    }
    desktopTunnelService.close();
    wss.close();
    server.close();
  });

  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();

  const hostSocket = new WebSocket(
    `ws://127.0.0.1:${port}/desktop-tunnel/connect?routeId=route-ws&routeToken=token-ws`,
  );
  sockets.push(hostSocket);
  const registeredPromise = waitForJsonMessage(
    hostSocket,
    (message) => message.type === "registered",
  );
  await waitForOpen(hostSocket);
  await registeredPromise;

  const publicSocket = new WebSocket(`ws://127.0.0.1:${port}/desktop/route-ws/ws?wsToken=abc123`);
  sockets.push(publicSocket);
  await waitForOpen(publicSocket);

  const wsOpen = await waitForJsonMessage(hostSocket, (message) => message.type === "ws-open");
  assert.equal(wsOpen.path, "/ws?wsToken=abc123");

  hostSocket.send(
    JSON.stringify({
      type: "ws-opened",
      sessionId: wsOpen.sessionId,
    }),
  );

  const hostFramePromise = waitForJsonMessage(
    hostSocket,
    (message) => message.type === "ws-frame" && message.sessionId === wsOpen.sessionId,
  );
  publicSocket.send("from-public");
  const hostFrame = await hostFramePromise;
  assert.equal(hostFrame.text, "from-public");

  const publicMessagePromise = waitForMessage(publicSocket);
  hostSocket.send(
    JSON.stringify({
      type: "ws-frame",
      sessionId: wsOpen.sessionId,
      text: "from-host",
    }),
  );
  assert.equal(await publicMessagePromise, "from-host");
});

function requestText({ method, port, path }) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        method,
        host: "127.0.0.1",
        port,
        path,
      },
      (res) => {
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () => {
          resolve({
            statusCode: res.statusCode || 0,
            body: Buffer.concat(chunks).toString("utf8"),
          });
        });
      },
    );
    req.on("error", reject);
    req.end();
  });
}

function waitForOpen(socket) {
  if (socket.readyState === WebSocket.OPEN) {
    return Promise.resolve();
  }
  return new Promise((resolve, reject) => {
    socket.once("open", resolve);
    socket.once("error", reject);
  });
}

function waitForMessage(socket) {
  return new Promise((resolve) => {
    socket.once("message", (payload, isBinary) => {
      resolve(isBinary ? payload.toString("base64") : payload.toString("utf8"));
    });
  });
}

function waitForJsonMessage(socket, predicate = () => true) {
  return new Promise((resolve) => {
    const onMessage = (payload) => {
      const parsed = JSON.parse(payload.toString("utf8"));
      if (!predicate(parsed)) {
        socket.once("message", onMessage);
        return;
      }
      resolve(parsed);
    };
    socket.once("message", onMessage);
  });
}
