const test = require("node:test");
const assert = require("node:assert/strict");

const { createPushNotificationTracker } = require("../src/notifications/tracker");
const { createNotificationsHandler } = require("../src/notifications/handler");

test("notifications handler routes Android push registration to the push service", async () => {
  const registrations = [];
  const handler = createNotificationsHandler({
    pushServiceClient: {
      hasConfiguredBaseUrl: true,
      async registerDevice(payload) {
        registrations.push(payload);
        return { ok: true };
      },
    },
  });
  const responses = [];

  const handled = handler.handleNotificationsRequest(JSON.stringify({
    id: "register-1",
    method: "notifications/push/register",
    params: {
      deviceToken: "fcm-token-123",
      alertsEnabled: true,
      devicePlatform: "android",
      appEnvironment: "development",
    },
  }), (raw) => responses.push(JSON.parse(raw)));

  await new Promise((resolve) => setTimeout(resolve, 5));

  assert.equal(handled, true);
  assert.equal(registrations.length, 1);
  assert.deepEqual(registrations[0], {
    deviceToken: "fcm-token-123",
    alertsEnabled: true,
    devicePlatform: "android",
    appEnvironment: "development",
  });
  assert.equal(responses[0].result.ok, true);
});

test("push tracker sends one completion push with a stable ready body", async () => {
  const notifications = [];
  const tracker = createPushNotificationTracker({
    sessionId: "session-1",
    pushServiceClient: {
      hasConfiguredBaseUrl: true,
      async notifyCompletion(payload) {
        notifications.push(payload);
        return { ok: true };
      },
    },
    previewMaxChars: 80,
  });

  tracker.handleOutbound(JSON.stringify({
    method: "thread/started",
    params: {
      thread: {
        id: "thread-1",
        title: "Fix auth bug",
      },
    },
  }));
  tracker.handleOutbound(JSON.stringify({
    method: "turn/started",
    params: {
      threadId: "thread-1",
      turnId: "turn-1",
    },
  }));
  tracker.handleOutbound(JSON.stringify({
    method: "item/agentMessage/delta",
    params: {
      threadId: "thread-1",
      turnId: "turn-1",
      delta: "Looking at the login flow.",
    },
  }));
  tracker.handleOutbound(JSON.stringify({
    method: "item/completed",
    params: {
      threadId: "thread-1",
      turnId: "turn-1",
      item: {
        type: "agent_message",
        role: "assistant",
        text: "The login fix is ready to review.",
      },
    },
  }));
  tracker.handleOutbound(JSON.stringify({
    method: "turn/completed",
    params: {
      threadId: "thread-1",
      turnId: "turn-1",
    },
  }));
  tracker.handleOutbound(JSON.stringify({
    method: "turn/completed",
    params: {
      threadId: "thread-1",
      turnId: "turn-1",
    },
  }));

  await new Promise((resolve) => setTimeout(resolve, 10));

  assert.equal(notifications.length, 1);
  assert.equal(notifications[0].threadId, "thread-1");
  assert.equal(notifications[0].turnId, "turn-1");
  assert.equal(notifications[0].result, "completed");
  assert.equal(notifications[0].title, "Fix auth bug");
  assert.equal(notifications[0].body, "Response ready");
});

test("push tracker uses failure previews for failed turns", async () => {
  const notifications = [];
  const tracker = createPushNotificationTracker({
    sessionId: "session-2",
    pushServiceClient: {
      hasConfiguredBaseUrl: true,
      async notifyCompletion(payload) {
        notifications.push(payload);
        return { ok: true };
      },
    },
  });

  tracker.handleOutbound(JSON.stringify({
    method: "turn/started",
    params: {
      threadId: "thread-2",
      turnId: "turn-2",
    },
  }));
  tracker.handleOutbound(JSON.stringify({
    method: "turn/failed",
    params: {
      threadId: "thread-2",
      turnId: "turn-2",
      message: "Tests failed on CI.",
    },
  }));

  await new Promise((resolve) => setTimeout(resolve, 10));

  assert.equal(notifications.length, 1);
  assert.equal(notifications[0].result, "failed");
  assert.equal(notifications[0].body, "Tests failed on CI.");
});

test("push tracker dedupes turnless terminal thread statuses per time bucket", async () => {
  const notifications = [];
  let currentTime = 0;
  const tracker = createPushNotificationTracker({
    sessionId: "session-status",
    pushServiceClient: {
      hasConfiguredBaseUrl: true,
      async notifyCompletion(payload) {
        notifications.push(payload);
        return { ok: true };
      },
    },
    now: () => currentTime,
  });

  tracker.handleOutbound(JSON.stringify({
    method: "thread/started",
    params: {
      thread: {
        id: "thread-status",
        title: "Status-only runtime",
      },
    },
  }));
  tracker.handleOutbound(JSON.stringify({
    method: "thread/status/changed",
    params: {
      threadId: "thread-status",
      status: "completed",
    },
  }));
  tracker.handleOutbound(JSON.stringify({
    method: "thread/status/changed",
    params: {
      threadId: "thread-status",
      status: "completed",
    },
  }));

  await new Promise((resolve) => setTimeout(resolve, 10));

  currentTime = 31_000;
  tracker.handleOutbound(JSON.stringify({
    method: "thread/status/changed",
    params: {
      threadId: "thread-status",
      status: "completed",
    },
  }));

  await new Promise((resolve) => setTimeout(resolve, 10));

  assert.equal(notifications.length, 2);
  assert.equal(notifications[0].result, "completed");
  assert.equal(notifications[1].result, "completed");
});
