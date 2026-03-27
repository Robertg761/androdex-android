const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const {
  createPushSessionService,
  createFileBackedPushStateStore,
  createWebhookPushClient,
  resolvePushStateFilePath,
} = require("../push-service");

test("push service stores Android registrations and forwards one completion notification", async () => {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-push-state-"));
  const sent = [];
  const service = createPushSessionService({
    webhookClient: {
      hasConfiguredBaseUrl: true,
      async notifyCompletion(payload) {
        sent.push(payload);
        return { ok: true };
      },
    },
    canRegisterSession: () => true,
    canNotifyCompletion: () => true,
    stateStore: createFileBackedPushStateStore({
      stateFilePath: path.join(tempDir, "push-state.json"),
    }),
  });

  await service.registerDevice({
    sessionId: "session-1",
    deviceToken: "aa bb cc",
    alertsEnabled: true,
    devicePlatform: "android",
    appEnvironment: "development",
  });

  await service.notifyCompletion({
    sessionId: "session-1",
    threadId: "thread-1",
    turnId: "turn-1",
    dedupeKey: "done-1",
    title: "Fix auth bug",
    body: "Response ready",
  });
  await service.notifyCompletion({
    sessionId: "session-1",
    threadId: "thread-1",
    turnId: "turn-1",
    dedupeKey: "done-1",
  });

  assert.equal(sent.length, 1);
  assert.equal(sent[0].deviceToken, "aabbcc");
  assert.equal(sent[0].devicePlatform, "android");
  assert.equal(sent[0].appEnvironment, "development");
  assert.equal(sent[0].result, "completed");
  assert.equal(sent[0].title, "Fix auth bug");
  assert.equal(sent[0].body, "Response ready");
  const persistedState = JSON.parse(fs.readFileSync(path.join(tempDir, "push-state.json"), "utf8"));
  assert.equal(persistedState.sessions.length, 1);
  const fileMode = fs.statSync(path.join(tempDir, "push-state.json")).mode & 0o777;
  if (process.platform === "win32") {
    assert.equal(fileMode > 0, true);
  } else {
    assert.equal(fileMode, 0o600);
  }
});

test("push service rejects registration when the relay session is not active", async () => {
  const service = createPushSessionService({
    webhookClient: {
      hasConfiguredBaseUrl: true,
      async notifyCompletion() {},
    },
    canRegisterSession: () => false,
  });

  await assert.rejects(
    service.registerDevice({
      sessionId: "session-missing",
      deviceToken: "aabbcc",
      alertsEnabled: true,
    }),
    /active relay session/
  );
});

test("push service uses a durable state file path under the Androdex home dir", () => {
  assert.equal(
    resolvePushStateFilePath({ CODEX_HOME: "/tmp/codex-home" }),
    path.join("/tmp", "codex-home", "androdex", "push-state.json")
  );
});

test("webhook push client aborts stalled requests with a timeout error", async () => {
  const client = createWebhookPushClient({
    baseUrl: "https://push.example.test",
    token: "secret-token",
    pathname: "/v1/push/webhook/notify-completion",
    requestTimeoutMs: 20,
    fetchImpl: async (_url, options) => new Promise((_, reject) => {
      options.signal.addEventListener("abort", () => {
        reject(options.signal.reason);
      }, { once: true });
    }),
  });

  await assert.rejects(
    client.notifyCompletion({ sessionId: "session-timeout" }),
    /timed out after 20ms/
  );
});

test("webhook push client posts completion payloads to the configured endpoint", async () => {
  const requests = [];
  const client = createWebhookPushClient({
    baseUrl: "https://push.example.test/",
    token: "secret-token",
    pathname: "/v1/push/webhook/notify-completion",
    fetchImpl: async (url, options) => {
      requests.push({ url, options });
      return {
        ok: true,
        async text() {
          return JSON.stringify({ ok: true });
        },
      };
    },
  });

  const result = await client.notifyCompletion({
    sessionId: "session-1",
    threadId: "thread-1",
    turnId: "turn-1",
    result: "completed",
    title: "Fix auth bug",
    body: "Response ready",
    dedupeKey: "done-1",
    deviceToken: "aabbcc",
    devicePlatform: "android",
    appEnvironment: "production",
  });

  assert.deepEqual(result, { ok: true });
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, "https://push.example.test/v1/push/webhook/notify-completion");
  assert.equal(requests[0].options.method, "POST");
  assert.equal(requests[0].options.headers.authorization, "Bearer secret-token");
  assert.deepEqual(JSON.parse(requests[0].options.body), {
    sessionId: "session-1",
    threadId: "thread-1",
    turnId: "turn-1",
    result: "completed",
    title: "Fix auth bug",
    body: "Response ready",
    dedupeKey: "done-1",
    deviceToken: "aabbcc",
    devicePlatform: "android",
    appEnvironment: "production",
  });
});
