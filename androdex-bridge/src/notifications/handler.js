// FILE: notifications/handler.js
// Purpose: Intercepts notifications/push/* bridge RPCs and forwards Android device registration to the configured push service.
// Layer: Bridge handler
// Exports: createNotificationsHandler
// Depends on: none

function createNotificationsHandler({ pushServiceClient, logPrefix = "[androdex]" } = {}) {
  function handleNotificationsRequest(rawMessage, sendResponse) {
    let parsed;
    try {
      parsed = JSON.parse(rawMessage);
    } catch {
      return false;
    }

    const method = typeof parsed?.method === "string" ? parsed.method.trim() : "";
    if (method !== "notifications/push/register") {
      return false;
    }

    const requestId = parsed.id;
    const params = parsed.params || {};

    handleNotificationsMethod(params)
      .then((result) => {
        sendResponse(JSON.stringify({ id: requestId, result }));
      })
      .catch((error) => {
        console.error(`${logPrefix} push registration failed: ${error.message}`);
        sendResponse(JSON.stringify({
          id: requestId,
          error: {
            code: -32000,
            message: error.userMessage || error.message || "Push registration failed.",
            data: {
              errorCode: error.errorCode || "push_registration_failed",
            },
          },
        }));
      });

    return true;
  }

  async function handleNotificationsMethod(params) {
    if (!pushServiceClient?.hasConfiguredBaseUrl) {
      return { ok: false, skipped: true };
    }

    const deviceToken = readString(params.deviceToken);
    const alertsEnabled = Boolean(params.alertsEnabled);
    const devicePlatform = readDevicePlatform(params.devicePlatform || params.platform || params.pushProvider);
    const appEnvironment = readAppEnvironment(params.appEnvironment || params.environment);
    if (!deviceToken) {
      throw notificationsError(
        "missing_device_token",
        "notifications/push/register requires a deviceToken."
      );
    }

    await pushServiceClient.registerDevice({
      deviceToken,
      alertsEnabled,
      devicePlatform,
      appEnvironment,
    });

    return {
      ok: true,
      alertsEnabled,
      devicePlatform,
      appEnvironment,
    };
  }

  return {
    handleNotificationsRequest,
  };
}

function readString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function readDevicePlatform(value) {
  const normalized = readString(value)?.toLowerCase();
  return normalized || "android";
}

function readAppEnvironment(value) {
  const normalized = readString(value)?.toLowerCase();
  return normalized === "development" ? "development" : "production";
}

function notificationsError(errorCode, userMessage) {
  const error = new Error(userMessage);
  error.errorCode = errorCode;
  error.userMessage = userMessage;
  return error;
}

module.exports = {
  createNotificationsHandler,
};
