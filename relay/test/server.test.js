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
  assert.equal(resolveResponse.body.sessionId, stableRelayHostId(macDeviceId));
  assert.equal(resolveResponse.body.macDeviceId, macDeviceId);
  assert.equal(resolveResponse.body.macIdentityPublicKey, macIdentityPublicKey);
});

test("relay server routes stable host ids across mac restarts", async (t) => {
  const { publicKey: macPublicKey } = generateKeyPairSync("ed25519");
  const { privateKey: phonePrivateKey, publicKey: phonePublicKey } = generateKeyPairSync("ed25519");
  const macPublicJwk = macPublicKey.export({ format: "jwk" });
  const phonePublicJwk = phonePublicKey.export({ format: "jwk" });
  const macIdentityPublicKey = base64UrlToBase64(macPublicJwk.x);
  const phoneIdentityPublicKey = base64UrlToBase64(phonePublicJwk.x);
  const macDeviceId = "mac-stable";
  const phoneDeviceId = "phone-stable";
  const stableHostId = stableRelayHostId(macDeviceId);

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

  const firstMacSocket = new WebSocket(`ws://127.0.0.1:${port}/relay/session-first`, {
    headers: {
      "x-role": "mac",
      "x-notification-secret": "secret-stable-first",
      "x-mac-device-id": macDeviceId,
      "x-mac-identity-public-key": macIdentityPublicKey,
      "x-machine-name": "stable-host",
      "x-trusted-phone-device-id": phoneDeviceId,
      "x-trusted-phone-public-key": phoneIdentityPublicKey,
    },
  });
  sockets.push(firstMacSocket);
  await waitForOpen(firstMacSocket);

  const firstMobileSocket = new WebSocket(`ws://127.0.0.1:${port}/relay/${stableHostId}`, {
    headers: {
      "x-role": "mobile",
    },
  });
  sockets.push(firstMobileSocket);
  await waitForOpen(firstMobileSocket);

  const firstRelayMessage = waitForMessage(firstMacSocket);
  firstMobileSocket.send("hello-first");
  assert.equal(await firstRelayMessage, "hello-first");

  firstMobileSocket.close();
  await waitForClose(firstMobileSocket);
  firstMacSocket.close();
  await waitForClose(firstMacSocket);

  const secondMacSocket = new WebSocket(`ws://127.0.0.1:${port}/relay/session-second`, {
    headers: {
      "x-role": "mac",
      "x-notification-secret": "secret-stable-second",
      "x-mac-device-id": macDeviceId,
      "x-mac-identity-public-key": macIdentityPublicKey,
      "x-machine-name": "stable-host",
      "x-trusted-phone-device-id": phoneDeviceId,
      "x-trusted-phone-public-key": phoneIdentityPublicKey,
    },
  });
  sockets.push(secondMacSocket);
  await waitForOpen(secondMacSocket);

  const resolveResponse = await requestJson({
    method: "POST",
    port,
    path: "/v1/trusted/session/resolve",
    body: signedResolveRequest({
      macDeviceId,
      phoneDeviceId,
      phoneIdentityPublicKey,
      phonePrivateKey,
    }),
  });

  assert.equal(resolveResponse.statusCode, 200);
  assert.equal(resolveResponse.body.sessionId, stableHostId);

  const secondMobileSocket = new WebSocket(`ws://127.0.0.1:${port}/relay/${stableHostId}`, {
    headers: {
      "x-role": "mobile",
    },
  });
  sockets.push(secondMobileSocket);
  await waitForOpen(secondMobileSocket);

  const secondRelayMessage = waitForMessage(secondMacSocket);
  secondMobileSocket.send("hello-second");
  assert.equal(await secondRelayMessage, "hello-second");
});

test("relay server accepts the previous trusted recovery credential during recovery rotation", async (t) => {
  const { publicKey: macPublicKey } = generateKeyPairSync("ed25519");
  const { privateKey: previousRecoveryPrivateKey, publicKey: previousRecoveryPublicKey } = generateKeyPairSync("ed25519");
  const { publicKey: currentRecoveryPublicKey } = generateKeyPairSync("ed25519");
  const macPublicJwk = macPublicKey.export({ format: "jwk" });
  const previousRecoveryPublicJwk = previousRecoveryPublicKey.export({ format: "jwk" });
  const currentRecoveryPublicJwk = currentRecoveryPublicKey.export({ format: "jwk" });
  const macIdentityPublicKey = base64UrlToBase64(macPublicJwk.x);
  const previousRecoveryIdentityPublicKey = base64UrlToBase64(previousRecoveryPublicJwk.x);
  const currentRecoveryIdentityPublicKey = base64UrlToBase64(currentRecoveryPublicJwk.x);
  const macDeviceId = "mac-recover";
  const phoneDeviceId = "phone-recover";
  const sessionId = "session-recover";

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
      "x-notification-secret": "secret-recover",
      "x-mac-device-id": macDeviceId,
      "x-mac-identity-public-key": macIdentityPublicKey,
      "x-machine-name": "recover-host",
      "x-trusted-phone-device-id": phoneDeviceId,
      "x-trusted-phone-public-key": "unused-for-recovery-route",
      "x-trusted-phone-recovery-public-key": currentRecoveryIdentityPublicKey,
      "x-trusted-phone-previous-recovery-public-key": previousRecoveryIdentityPublicKey,
    },
  });
  sockets.push(macSocket);
  await waitForOpen(macSocket);

  const nonce = "recover-nonce";
  const timestamp = Date.now();
  const transcriptBytes = buildTrustedRecoverTranscript({
    macDeviceId,
    phoneDeviceId,
    recoveryIdentityPublicKey: previousRecoveryIdentityPublicKey,
    nonce,
    timestamp,
  });
  const signature = sign(
    null,
    transcriptBytes,
    previousRecoveryPrivateKey
  ).toString("base64");

  const recoverResponse = await requestJson({
    method: "POST",
    port,
    path: "/v1/trusted/session/recover",
    body: {
      macDeviceId,
      phoneDeviceId,
      recoveryIdentityPublicKey: previousRecoveryIdentityPublicKey,
      nonce,
      timestamp,
      signature,
    },
  });

  assert.equal(recoverResponse.statusCode, 200);
  assert.equal(recoverResponse.body.ok, true);
  assert.equal(recoverResponse.body.sessionId, sessionId);
  assert.equal(recoverResponse.body.macDeviceId, macDeviceId);
  assert.equal(recoverResponse.body.macIdentityPublicKey, macIdentityPublicKey);
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

function waitForClose(socket) {
  return new Promise((resolve) => {
    if (socket.readyState === WebSocket.CLOSED) {
      resolve();
      return;
    }
    socket.once("close", resolve);
  });
}

function waitForMessage(socket) {
  return new Promise((resolve, reject) => {
    socket.once("message", (data) => {
      resolve(typeof data === "string" ? data : data.toString("utf8"));
    });
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

function signedResolveRequest({
  macDeviceId,
  phoneDeviceId,
  phoneIdentityPublicKey,
  phonePrivateKey,
}) {
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

  return {
    macDeviceId,
    phoneDeviceId,
    phoneIdentityPublicKey,
    nonce,
    timestamp,
    signature,
  };
}

function stableRelayHostId(macDeviceId) {
  return `mac.${macDeviceId}`;
}

function buildTrustedRecoverTranscript({
  macDeviceId,
  phoneDeviceId,
  recoveryIdentityPublicKey,
  nonce,
  timestamp,
}) {
  return Buffer.concat([
    encodeLengthPrefixedUTF8("androdex-trusted-session-recover-v1"),
    encodeLengthPrefixedUTF8(macDeviceId),
    encodeLengthPrefixedUTF8(phoneDeviceId),
    encodeLengthPrefixedBuffer(Buffer.from(recoveryIdentityPublicKey, "base64")),
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
