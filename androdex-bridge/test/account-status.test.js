const test = require("node:test");
const assert = require("node:assert/strict");
const {
  composeAccountStatus,
  composeSanitizedAuthStatusFromSettledResults,
} = require("../src/account-status");

test("composeAccountStatus preserves account-read rate limits for refresh snapshots", () => {
  const status = composeAccountStatus({
    accountRead: {
      account: {
        email: "host@example.com",
      },
      rateLimits: [
        {
          name: "gpt-5.4",
          remaining: 42,
          limit: 100,
          resetsAt: "2026-03-27T12:30:00Z",
        },
      ],
    },
    authStatus: {
      authToken: "token",
    },
  });

  assert.deepEqual(status.rateLimits, [
    {
      name: "gpt-5.4",
      remaining: 42,
      limit: 100,
      used: 58,
      resetsAt: "2026-03-27T12:30:00Z",
    },
  ]);
});

test("composeAccountStatus normalizes keyed account usage maps into rate-limit buckets", () => {
  const status = composeAccountStatus({
    accountRead: {
      usage: {
        limits: {
          "gpt-5.4": {
            remaining: "7",
            quota: "10",
          },
        },
      },
    },
    authStatus: {
      authToken: "token",
    },
  });

  assert.deepEqual(status.rateLimits, [
    {
      name: "gpt-5.4",
      remaining: 7,
      limit: 10,
      used: 3,
      resetsAt: null,
    },
  ]);
});

test("composeSanitizedAuthStatusFromSettledResults keeps rate limits in the bridge response", () => {
  const status = composeSanitizedAuthStatusFromSettledResults({
    accountReadResult: {
      status: "fulfilled",
      value: {
        account: {
          email: "host@example.com",
        },
        rateLimits: [
          {
            name: "gpt-5.4",
            remaining: 42,
            limit: 100,
          },
        ],
      },
    },
    authStatusResult: {
      status: "fulfilled",
      value: {
        authToken: "token",
      },
    },
  });

  assert.deepEqual(status.rateLimits, [
    {
      name: "gpt-5.4",
      remaining: 42,
      limit: 100,
      used: 58,
      resetsAt: null,
    },
  ]);
});
