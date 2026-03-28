// FILE: account-handler.js
// Purpose: Exposes a sanitized host-owned account snapshot to Android without leaking auth tokens over the relay.
// Layer: Bridge handler
// Exports: createAccountStatusHandler
// Depends on: ./account-status

const { composeSanitizedAuthStatusFromSettledResults } = require("./account-status");

function createAccountStatusHandler({
  sendCodexRequest,
  readBridgeVersionInfo = () => null,
} = {}) {
  if (typeof sendCodexRequest !== "function") {
    throw new Error("createAccountStatusHandler requires sendCodexRequest.");
  }

  function handleAccountStatusRequest(rawMessage, sendResponse) {
    let parsed;
    try {
      parsed = JSON.parse(rawMessage);
    } catch {
      return false;
    }

    const method = typeof parsed?.method === "string" ? parsed.method.trim() : "";
    if (method !== "account/status/read" && method !== "getAuthStatus") {
      return false;
    }

    const requestId = parsed.id;
    readSanitizedAuthStatus()
      .then((result) => {
        sendResponse(JSON.stringify({ id: requestId, result }));
      })
      .catch((error) => {
        sendResponse(JSON.stringify({
          id: requestId,
          error: {
            code: -32000,
            message: error.userMessage || error.message || "Unable to read host account status.",
            data: {
              errorCode: error.errorCode || error.code || "account_status_unavailable",
            },
          },
        }));
      });

    return true;
  }

  async function readSanitizedAuthStatus() {
    const [accountReadResult, authStatusResult] = await Promise.allSettled([
      sendCodexRequest("account/read", {
        refreshToken: false,
      }),
      sendCodexRequest("getAuthStatus", {
        includeToken: true,
        refreshToken: true,
      }),
    ]);

    return composeSanitizedAuthStatusFromSettledResults({
      accountReadResult,
      authStatusResult,
      bridgeVersionInfo: readBridgeVersionInfo(),
    });
  }

  return {
    handleAccountStatusRequest,
  };
}

module.exports = {
  createAccountStatusHandler,
};
