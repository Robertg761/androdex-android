const test = require("node:test");
const assert = require("node:assert/strict");

const { createAccountStatusHandler } = require("../src/account-handler");

test("account/status/read returns sanitized account status", async () => {
  const responses = {
    "account/read": {
      account: {
        email: "dev@example.com",
        planType: "pro",
        type: "chatgpt",
      },
    },
    "getAuthStatus": {
      authMethod: "chatgpt",
      authToken: "secret-token",
      requiresOpenaiAuth: false,
    },
  };

  const handler = createAccountStatusHandler({
    async sendCodexRequest(method) {
      return responses[method];
    },
    readBridgeVersionInfo() {
      return {
        bridgeVersion: "1.1.3",
      };
    },
  });

  const payloads = [];
  const handled = handler.handleAccountStatusRequest(
    JSON.stringify({
      id: "req-1",
      method: "account/status/read",
      params: {},
    }),
    (rawMessage) => payloads.push(JSON.parse(rawMessage))
  );

  assert.equal(handled, true);
  await waitFor(() => payloads.length === 1);

  assert.deepEqual(payloads[0], {
    id: "req-1",
    result: {
      authMethod: "chatgpt",
      status: "authenticated",
      email: "dev@example.com",
      planType: "pro",
      loginInFlight: false,
      needsReauth: false,
      tokenReady: true,
      expiresAt: null,
      bridgeVersion: "1.1.3",
      bridgeLatestVersion: null,
    },
  });
});

function waitFor(predicate, timeoutMs = 500) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + timeoutMs;
    const tick = () => {
      if (predicate()) {
        resolve();
        return;
      }
      if (Date.now() > deadline) {
        reject(new Error("Timed out waiting for condition."));
        return;
      }
      setTimeout(tick, 10);
    };
    tick();
  });
}
