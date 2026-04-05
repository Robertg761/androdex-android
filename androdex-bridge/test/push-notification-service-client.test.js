const test = require("node:test");
const assert = require("node:assert/strict");

const { createPushNotificationServiceClient } = require("../src/notifications/service-client");

test("push service client posts Android registration payloads to the configured endpoint", async () => {
  const requests = [];
  const client = createPushNotificationServiceClient({
    baseUrl: "https://push.example.test/",
    sessionId: "session-register",
    fetchImpl: async (url, options) => {
      requests.push({
        url,
        options,
      });
      return {
        ok: true,
        async text() {
          return JSON.stringify({ ok: true });
        },
      };
    },
  });

  const result = await client.registerDevice({
    deviceToken: "fcm-token-123",
    alertsEnabled: true,
    devicePlatform: "android",
    appEnvironment: "development",
  });

  assert.deepEqual(result, { ok: true });
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, "https://push.example.test/v1/push/session/register-device");
  assert.equal(requests[0].options.method, "POST");
  assert.equal(requests[0].options.headers["content-type"], "application/json");
  assert.deepEqual(JSON.parse(requests[0].options.body), {
    sessionId: "session-register",
    deviceToken: "fcm-token-123",
    alertsEnabled: true,
    devicePlatform: "android",
    appEnvironment: "development",
  });
});

test("push service client aborts stalled requests with a timeout error", async () => {
  const client = createPushNotificationServiceClient({
    baseUrl: "https://push.example.test",
    sessionId: "session-timeout",
    requestTimeoutMs: 20,
    fetchImpl: async (_url, options) => new Promise((_, reject) => {
      options.signal.addEventListener("abort", () => {
        reject(options.signal.reason);
      }, { once: true });
    }),
  });

  await assert.rejects(
    client.registerDevice({
      deviceToken: "aabbcc",
      alertsEnabled: true,
      devicePlatform: "android",
      appEnvironment: "production",
    }),
    /timed out after 20ms/
  );
});
