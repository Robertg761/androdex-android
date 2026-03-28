// FILE: codex-desktop-windows-devtools.js
// Purpose: Talks to the Windows Codex desktop DevTools endpoint for trusted renderer actions.
// Layer: CLI helper
// Exports: Windows desktop DevTools helpers
// Depends on: http, ws

const http = require("http");
const WebSocket = require("ws");

const DEFAULT_WINDOWS_REMOTE_DEBUGGING_PORT = 9333;
const DEFAULT_DEVTOOLS_HOST = "127.0.0.1";
const DEFAULT_DEVTOOLS_REQUEST_TIMEOUT_MS = 1_500;
const DEFAULT_DEVTOOLS_SOCKET_TIMEOUT_MS = 2_000;
const TRUSTED_CODEX_URL_PREFIX = "app://-/";
const TRUSTED_CODEX_INDEX_URL_PREFIX = "app://-/index.html";
const DEFAULT_TRUSTED_TARGET_HOST_ID = "local";

async function restartCodexDesktopAppServerViaTrustedRenderer({
  host = DEFAULT_DEVTOOLS_HOST,
  port = DEFAULT_WINDOWS_REMOTE_DEBUGGING_PORT,
  requestTimeoutMs = DEFAULT_DEVTOOLS_REQUEST_TIMEOUT_MS,
  socketTimeoutMs = DEFAULT_DEVTOOLS_SOCKET_TIMEOUT_MS,
  httpGetJson = requestJson,
  WebSocketImpl = WebSocket,
} = {}) {
  return evaluateTrustedCodexRendererExpression({
    host,
    port,
    requestTimeoutMs,
    socketTimeoutMs,
    httpGetJson,
    WebSocketImpl,
    expressionFactory: ({ hostId }) => buildAppServerRestartExpression(hostId),
    unavailableMessage: (resolvedHost, resolvedPort) => (
      `Codex desktop DevTools is unavailable on ${resolvedHost}:${resolvedPort}.`
    ),
    missingTargetMessage: "Could not find a trusted Codex desktop renderer target.",
    actionDescription: "the restart dispatch",
  });
}

async function navigateCodexDesktopRendererToRouteViaTrustedRenderer({
  host = DEFAULT_DEVTOOLS_HOST,
  port = DEFAULT_WINDOWS_REMOTE_DEBUGGING_PORT,
  requestTimeoutMs = DEFAULT_DEVTOOLS_REQUEST_TIMEOUT_MS,
  socketTimeoutMs = DEFAULT_DEVTOOLS_SOCKET_TIMEOUT_MS,
  httpGetJson = requestJson,
  WebSocketImpl = WebSocket,
  path = "/settings",
} = {}) {
  return evaluateTrustedCodexRendererExpression({
    host,
    port,
    requestTimeoutMs,
    socketTimeoutMs,
    httpGetJson,
    WebSocketImpl,
    expressionFactory: () => buildNavigateToRouteExpression(path),
    unavailableMessage: (resolvedHost, resolvedPort) => (
      `Codex desktop DevTools is unavailable on ${resolvedHost}:${resolvedPort}.`
    ),
    missingTargetMessage: "Could not find a trusted Codex desktop renderer target.",
    actionDescription: `the renderer navigation to ${path}`,
  });
}

async function evaluateTrustedCodexRendererExpression({
  host = DEFAULT_DEVTOOLS_HOST,
  port = DEFAULT_WINDOWS_REMOTE_DEBUGGING_PORT,
  requestTimeoutMs = DEFAULT_DEVTOOLS_REQUEST_TIMEOUT_MS,
  socketTimeoutMs = DEFAULT_DEVTOOLS_SOCKET_TIMEOUT_MS,
  httpGetJson = requestJson,
  WebSocketImpl = WebSocket,
  expressionFactory = null,
  unavailableMessage = (resolvedHost, resolvedPort) => (
    `Codex desktop DevTools is unavailable on ${resolvedHost}:${resolvedPort}.`
  ),
  missingTargetMessage = "Could not find a trusted Codex desktop renderer target.",
  actionDescription = "the renderer action dispatch",
} = {}) {
  const resolvedPort = resolveWindowsRemoteDebuggingPort(port);
  let targets = null;

  try {
    targets = await httpGetJson({
      host,
      port: resolvedPort,
      path: "/json/list",
      timeoutMs: requestTimeoutMs,
    });
  } catch (error) {
    throw createDevtoolsError(
      "cdp_port_unavailable",
      unavailableMessage(host, resolvedPort),
      error
    );
  }

  const trustedTarget = selectTrustedCodexRendererTarget(targets);
  if (!trustedTarget?.webSocketDebuggerUrl) {
    throw createDevtoolsError(
      "trusted_target_missing",
      missingTargetMessage
    );
  }

  const hostId = resolveTrustedTargetHostId(trustedTarget);
  await evaluateOnDevtoolsTarget({
    webSocketDebuggerUrl: trustedTarget.webSocketDebuggerUrl,
    expression: typeof expressionFactory === "function"
      ? expressionFactory({ hostId, trustedTarget })
      : "",
    timeoutMs: socketTimeoutMs,
    WebSocketImpl,
    actionDescription,
  });

  return {
    hostId,
    targetId: normalizeNonEmptyString(trustedTarget.id),
    targetUrl: normalizeNonEmptyString(trustedTarget.url),
  };
}

function resolveWindowsRemoteDebuggingPort(value) {
  const parsed = Number.parseInt(String(value), 10);
  if (Number.isInteger(parsed) && parsed > 0 && parsed <= 65535) {
    return parsed;
  }
  return DEFAULT_WINDOWS_REMOTE_DEBUGGING_PORT;
}

function selectTrustedCodexRendererTarget(targets) {
  if (!Array.isArray(targets)) {
    return null;
  }

  return (
    targets.find((target) => isTrustedCodexRendererTarget(target, { preferIndexUrl: true }))
    || targets.find((target) => isTrustedCodexRendererTarget(target))
    || null
  );
}

function isTrustedCodexRendererTarget(target, { preferIndexUrl = false } = {}) {
  if (!target || typeof target !== "object") {
    return false;
  }

  const url = normalizeNonEmptyString(target.url);
  const debuggerUrl = normalizeNonEmptyString(target.webSocketDebuggerUrl);
  if (!url || !debuggerUrl) {
    return false;
  }

  if (preferIndexUrl) {
    return url.startsWith(TRUSTED_CODEX_INDEX_URL_PREFIX);
  }

  return url.startsWith(TRUSTED_CODEX_URL_PREFIX);
}

function requestJson({
  host = DEFAULT_DEVTOOLS_HOST,
  port = DEFAULT_WINDOWS_REMOTE_DEBUGGING_PORT,
  path = "/json/list",
  timeoutMs = DEFAULT_DEVTOOLS_REQUEST_TIMEOUT_MS,
} = {}) {
  return new Promise((resolve, reject) => {
    const request = http.request({
      host,
      port,
      path,
      method: "GET",
    }, (response) => {
      const chunks = [];
      response.on("data", (chunk) => chunks.push(chunk));
      response.on("end", () => {
        const body = Buffer.concat(chunks).toString("utf8");
        if (response.statusCode !== 200) {
          reject(new Error(`DevTools returned HTTP ${response.statusCode || "unknown"}.`));
          return;
        }

        try {
          resolve(JSON.parse(body));
        } catch (error) {
          reject(error);
        }
      });
    });

    request.setTimeout(timeoutMs, () => {
      request.destroy(new Error("DevTools request timed out."));
    });

    request.on("error", reject);
    request.end();
  });
}

function evaluateOnDevtoolsTarget({
  webSocketDebuggerUrl,
  expression,
  timeoutMs = DEFAULT_DEVTOOLS_SOCKET_TIMEOUT_MS,
  WebSocketImpl = WebSocket,
  actionDescription = "the renderer action dispatch",
} = {}) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocketImpl(webSocketDebuggerUrl);
    const commandId = 1;
    let settled = false;

    const finish = (error) => {
      if (settled) {
        return;
      }

      settled = true;
      clearTimeout(timeoutHandle);
      tryCloseSocket(socket);
      if (error) {
        reject(error);
        return;
      }
      resolve();
    };

    const timeoutHandle = setTimeout(() => {
      finish(createDevtoolsError(
        "renderer_eval_failed",
        `Timed out waiting for ${actionDescription}.`
      ));
    }, timeoutMs);

    socket.on("open", () => {
      socket.send(JSON.stringify({
        id: commandId,
        method: "Runtime.evaluate",
        params: {
          expression,
          awaitPromise: true,
          returnByValue: true,
        },
      }));
    });

    socket.on("message", (rawMessage) => {
      let parsed = null;
      try {
        parsed = JSON.parse(rawMessage.toString("utf8"));
      } catch (error) {
        finish(createDevtoolsError(
          "renderer_eval_failed",
          "Codex desktop DevTools returned invalid JSON.",
          error
        ));
        return;
      }

      if (parsed?.id !== commandId) {
        return;
      }

      if (parsed.error) {
        finish(createDevtoolsError(
          "renderer_eval_failed",
          parsed.error.message || "Codex desktop rejected the renderer evaluation."
        ));
        return;
      }

      if (parsed.result?.exceptionDetails) {
        const exceptionText = normalizeNonEmptyString(parsed.result.exceptionDetails.text);
        finish(createDevtoolsError(
          "renderer_eval_failed",
          exceptionText || `Codex desktop threw while dispatching ${actionDescription}.`
        ));
        return;
      }

      finish();
    });

    socket.on("error", (error) => {
      finish(createDevtoolsError(
        "renderer_eval_failed",
        "Could not connect to the Codex desktop renderer target.",
        error
      ));
    });

    socket.on("close", () => {
      if (!settled) {
        finish(createDevtoolsError(
          "renderer_eval_failed",
          "Codex desktop closed the renderer DevTools session before acknowledging the restart."
        ));
      }
    });
  });
}

function createDevtoolsError(code, message, cause = null) {
  const error = new Error(message);
  error.code = code;
  if (cause) {
    error.cause = cause;
  }
  return error;
}

function buildAppServerRestartExpression(hostId) {
  return `window.electronBridge.sendMessageFromView({ type: "codex-app-server-restart", hostId: ${JSON.stringify(hostId)} })`;
}

function buildNavigateToRouteExpression(path) {
  const normalizedPath = normalizeNonEmptyString(path) || "/settings";
  return `window.postMessage({ type: "navigate-to-route", path: ${JSON.stringify(normalizedPath)} }, window.location.origin)`;
}

function resolveTrustedTargetHostId(target) {
  const targetUrl = normalizeNonEmptyString(target?.url);
  if (!targetUrl) {
    return DEFAULT_TRUSTED_TARGET_HOST_ID;
  }

  try {
    const parsedUrl = new URL(targetUrl);
    return normalizeNonEmptyString(parsedUrl.searchParams.get("hostId"))
      || DEFAULT_TRUSTED_TARGET_HOST_ID;
  } catch {
    return DEFAULT_TRUSTED_TARGET_HOST_ID;
  }
}

function tryCloseSocket(socket) {
  if (!socket) {
    return;
  }

  if (socket.readyState === WebSocket.CONNECTING || socket.readyState === WebSocket.OPEN) {
    socket.close();
  }
}

function normalizeNonEmptyString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

module.exports = {
  DEFAULT_WINDOWS_REMOTE_DEBUGGING_PORT,
  buildAppServerRestartExpression,
  buildNavigateToRouteExpression,
  isTrustedCodexRendererTarget,
  navigateCodexDesktopRendererToRouteViaTrustedRenderer,
  requestJson,
  resolveTrustedTargetHostId,
  resolveWindowsRemoteDebuggingPort,
  restartCodexDesktopAppServerViaTrustedRenderer,
  selectTrustedCodexRendererTarget,
};
