const test = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");

const { createRelayServer } = require("../server");

test("relay server exposes push routes and health stats when push hosting is enabled", async (t) => {
  const requests = [];
  const pushSessionService = {
    getStats() {
      return {
        enabled: true,
        registeredSessions: 1,
        deliveredDedupeKeys: 0,
        webhookConfigured: true,
      };
    },
    async registerDevice(body) {
      requests.push({ type: "register", body });
      return { ok: true };
    },
    async notifyCompletion(body) {
      requests.push({ type: "notify", body });
      return { ok: true };
    },
  };

  const { server, wss } = createRelayServer({
    enablePushService: true,
    pushSessionService,
  });
  t.after(() => {
    wss.close();
    server.close();
  });

  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();

  const registerResponse = await requestJson({
    method: "POST",
    port,
    path: "/v1/push/session/register-device",
    body: {
      sessionId: "session-1",
      deviceToken: "aabbcc",
      alertsEnabled: true,
    },
  });
  const notifyResponse = await requestJson({
    method: "POST",
    port,
    path: "/v1/push/session/notify-completion",
    body: {
      sessionId: "session-1",
      threadId: "thread-1",
      dedupeKey: "done-1",
    },
  });
  const healthResponse = await requestJson({
    method: "GET",
    port,
    path: "/healthz",
  });

  assert.equal(registerResponse.statusCode, 200);
  assert.equal(notifyResponse.statusCode, 200);
  assert.equal(healthResponse.statusCode, 200);
  assert.equal(requests.length, 2);
  assert.equal(requests[0].type, "register");
  assert.equal(requests[1].type, "notify");
  assert.equal(healthResponse.body.push.webhookConfigured, true);
  assert.equal(healthResponse.body.push.enabled, true);
});

function requestJson({ method, port, path, body }) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        method,
        port,
        host: "127.0.0.1",
        path,
        headers: body ? { "content-type": "application/json" } : undefined,
      },
      (res) => {
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () => {
          const raw = Buffer.concat(chunks).toString("utf8");
          resolve({
            statusCode: res.statusCode,
            body: raw ? JSON.parse(raw) : null,
          });
        });
      }
    );
    req.on("error", reject);
    if (body) {
      req.end(JSON.stringify(body));
    } else {
      req.end();
    }
  });
}
