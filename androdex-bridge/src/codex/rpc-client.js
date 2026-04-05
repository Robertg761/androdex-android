// FILE: codex/rpc-client.js
// Purpose: Lets the bridge make private Codex RPC calls and consume their responses without forwarding them to Android.
// Layer: CLI helper
// Exports: createCodexRpcClient
// Depends on: none

function createCodexRpcClient({
  sendToCodex,
  requestTimeoutMs = 15_000,
  requestIdPrefix = "androdex-bridge-rpc",
} = {}) {
  if (typeof sendToCodex !== "function") {
    throw new Error("createCodexRpcClient requires a sendToCodex function.");
  }

  const pendingRequests = new Map();
  let requestCounter = 0;

  function sendRequest(method, params = {}) {
    return new Promise((resolve, reject) => {
      const requestId = `${requestIdPrefix}-${++requestCounter}`;
      const timeoutId = setTimeout(() => {
        pendingRequests.delete(requestId);
        const error = new Error(`Timed out waiting for Codex response to ${method}.`);
        error.code = "codex_rpc_timeout";
        reject(error);
      }, requestTimeoutMs);

      pendingRequests.set(requestId, {
        method,
        resolve,
        reject,
        timeoutId,
      });

      try {
        sendToCodex(JSON.stringify({
          id: requestId,
          method,
          params,
        }));
      } catch (error) {
        pendingRequests.delete(requestId);
        clearTimeout(timeoutId);
        reject(error);
      }
    });
  }

  function handleCodexMessage(rawMessage) {
    const parsed = safeParseJSON(rawMessage);
    const responseId = parsed?.id;
    if (responseId == null) {
      return false;
    }

    const pendingRequest = pendingRequests.get(String(responseId));
    if (!pendingRequest) {
      return false;
    }

    pendingRequests.delete(String(responseId));
    clearTimeout(pendingRequest.timeoutId);

    if (parsed?.error) {
      const error = new Error(parsed.error.message || `Codex RPC failed for ${pendingRequest.method}.`);
      error.code = parsed.error.code;
      error.data = parsed.error.data;
      pendingRequest.reject(error);
      return true;
    }

    pendingRequest.resolve(parsed?.result ?? null);
    return true;
  }

  function rejectAllPending(error) {
    for (const [requestId, pendingRequest] of pendingRequests.entries()) {
      pendingRequests.delete(requestId);
      clearTimeout(pendingRequest.timeoutId);
      pendingRequest.reject(error);
    }
  }

  return {
    sendRequest,
    handleCodexMessage,
    rejectAllPending,
  };
}

function safeParseJSON(value) {
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

module.exports = {
  createCodexRpcClient,
};
