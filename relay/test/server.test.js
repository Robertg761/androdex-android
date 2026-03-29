const test = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");
const { generateKeyPairSync, sign } = require("node:crypto");
const WebSocket = require("ws");

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

test("relay server resolves the current trusted host session for the paired mobile client", async (t) => {
  const { publicKey: macPublicKey } = generateKeyPairSync("ed25519");
  const { privateKey: phonePrivateKey, publicKey: phonePublicKey } = generateKeyPairSync("ed25519");
  const macPublicJwk = macPublicKey.export({ format: "jwk" });
  const phonePublicJwk = phonePublicKey.export({ format: "jwk" });
  const macIdentityPublicKey = base64UrlToBase64(macPublicJwk.x);
  const phoneIdentityPublicKey = base64UrlToBase64(phonePublicJwk.x);
  const macDeviceId = "mac-resolve";
  const phoneDeviceId = "phone-resolve";
  const sessionId = "session-resolve";

  const { server, wss } = createRelayServer();
  const sockets = [];
  t.after(() => {
    for (const socket of sockets) {
      try {
        socket.close();
      } catch {
        // Best effort only.
      }
    }
    wss.close();
    server.close();
  });

  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();

  const macSocket = new WebSocket(`ws://127.0.0.1:${port}/relay/${sessionId}`, {
    headers: {
      "x-role": "mac",
      "x-notification-secret": "secret-resolve",
      "x-mac-device-id": macDeviceId,
      "x-mac-identity-public-key": macIdentityPublicKey,
      "x-machine-name": "resolve-host",
      "x-trusted-phone-device-id": phoneDeviceId,
      "x-trusted-phone-public-key": phoneIdentityPublicKey,
    },
  });
  sockets.push(macSocket);
  await waitForOpen(macSocket);

  const nonce = "resolve-nonce";
  const timestamp = Date.now();
  const transcriptBytes = buildTrustedResolveTranscript({
    macDeviceId,
    phoneDeviceId,
    phoneIdentityPublicKey,
    nonce,
    timestamp,
  });
  const signature = sign(
    null,
    transcriptBytes,
    phonePrivateKey
  ).toString("base64");

  const resolveResponse = await requestJson({
    method: "POST",
    port,
    path: "/v1/trusted/session/resolve",
    body: {
      macDeviceId,
      phoneDeviceId,
      phoneIdentityPublicKey,
      nonce,
      timestamp,
      signature,
    },
  });

  assert.equal(resolveResponse.statusCode, 200);
  assert.equal(resolveResponse.body.ok, true);
  assert.equal(resolveResponse.body.sessionId, sessionId);
  assert.equal(resolveResponse.body.macDeviceId, macDeviceId);
  assert.equal(resolveResponse.body.macIdentityPublicKey, macIdentityPublicKey);
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

function waitForOpen(socket) {
  return new Promise((resolve, reject) => {
    socket.once("open", resolve);
    socket.once("error", reject);
  });
}

function buildTrustedResolveTranscript({
  macDeviceId,
  phoneDeviceId,
  phoneIdentityPublicKey,
  nonce,
  timestamp,
}) {
  return Buffer.concat([
    encodeLengthPrefixedUTF8("androdex-trusted-session-resolve-v1"),
    encodeLengthPrefixedUTF8(macDeviceId),
    encodeLengthPrefixedUTF8(phoneDeviceId),
    encodeLengthPrefixedBuffer(Buffer.from(phoneIdentityPublicKey, "base64")),
    encodeLengthPrefixedUTF8(nonce),
    encodeLengthPrefixedUTF8(String(timestamp)),
  ]);
}

function encodeLengthPrefixedUTF8(value) {
  return encodeLengthPrefixedBuffer(Buffer.from(String(value), "utf8"));
}

function encodeLengthPrefixedBuffer(value) {
  const length = Buffer.allocUnsafe(4);
  length.writeUInt32BE(value.length, 0);
  return Buffer.concat([length, value]);
}

function base64UrlToBase64(value) {
  return `${value}${"=".repeat((4 - (value.length % 4 || 4)) % 4)}`
    .replace(/-/g, "+")
    .replace(/_/g, "/");
}
