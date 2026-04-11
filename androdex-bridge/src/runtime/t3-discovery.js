// FILE: runtime/t3-discovery.js
// Purpose: Centralizes default T3 desktop discovery and the default loopback endpoint used for attach-first companion mode.
// Layer: runtime helper
// Exports: T3 endpoint defaults and installed-runtime discovery helpers
// Depends on: child_process, fs, os, path

const { execFileSync } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");

const DEFAULT_T3_LOOPBACK_ENDPOINT = "ws://127.0.0.1:3773/ws";
const KNOWN_T3_DESKTOP_APP_PATHS = Object.freeze([
  "/Applications/T3 Code (Alpha).app",
  "/Applications/T3 Code.app",
]);

function resolveT3RuntimeEndpoint({
  env = process.env,
  fsImpl = fs,
  homeDir = resolveHomeDir({ env }),
  isProcessAliveImpl = isProcessAlive,
} = {}) {
  const explicitEndpoint = normalizeNonEmptyString(env?.ANDRODEX_T3_ENDPOINT);
  if (explicitEndpoint) {
    return {
      endpoint: explicitEndpoint,
      authToken: normalizeNonEmptyString(env?.ANDRODEX_T3_AUTH_TOKEN),
      source: "explicit",
    };
  }

  const desktopSession = readDesktopT3Session({
    fsImpl,
    homeDir,
    isProcessAliveImpl,
  });
  if (normalizeNonEmptyString(desktopSession.endpoint) && normalizeNonEmptyString(desktopSession.authToken)) {
    return {
      endpoint: desktopSession.endpoint,
      authToken: desktopSession.authToken,
      source: "runtime-session-file",
    };
  }

  return {
    endpoint: DEFAULT_T3_LOOPBACK_ENDPOINT,
    authToken: "",
    source: "default-loopback",
  };
}

function detectInstalledT3Runtime({
  env = process.env,
  fsImpl = fs,
  execFileSyncImpl = execFileSync,
  homeDir = resolveHomeDir({ env }),
  isProcessAliveImpl = isProcessAlive,
} = {}) {
  const desktopAppPath = KNOWN_T3_DESKTOP_APP_PATHS.find((candidate) => fsImpl.existsSync(candidate)) || "";
  const cliPath = detectCommandPath("t3", { execFileSyncImpl });
  const desktopSession = readDesktopT3Session({
    fsImpl,
    homeDir,
    isProcessAliveImpl,
  });
  return {
    desktopAppInstalled: Boolean(desktopAppPath),
    desktopAppPath,
    cliInstalled: Boolean(cliPath),
    cliPath,
    desktopSession,
  };
}

function readDesktopT3Session({
  fsImpl = fs,
  env = process.env,
  homeDir = resolveHomeDir({ env }),
  isProcessAliveImpl = isProcessAlive,
} = {}) {
  const runtimeSessionPath = path.join(homeDir, ".t3", "userdata", "runtime-session.json");
  const desktopLogPath = path.join(homeDir, ".t3", "userdata", "logs", "desktop-main.log");
  const serverLogPath = path.join(homeDir, ".t3", "userdata", "logs", "server.log");
  const runtimeSession = readRuntimeSessionDescriptor({
    fsImpl,
    filePath: runtimeSessionPath,
    isProcessAliveImpl,
  });
  const desktopLog = safeReadUtf8(fsImpl, desktopLogPath);
  const serverLog = safeReadUtf8(fsImpl, serverLogPath);
  const trustedRuntimeSession = runtimeSession?.descriptor || null;
  const endpoint = trustedRuntimeSession?.endpoint || extractLatestDesktopWebSocketEndpoint(desktopLog);
  const authEnabled = extractLatestAuthEnabled(serverLog);
  return {
    runtimeSessionPath,
    desktopLogPath,
    serverLogPath,
    endpoint,
    authEnabled: typeof trustedRuntimeSession?.authToken === "string" && trustedRuntimeSession.authToken
      ? true
      : authEnabled,
    authToken: normalizeNonEmptyString(trustedRuntimeSession?.authToken),
    source: trustedRuntimeSession ? "runtime-session-file" : "desktop-log",
    descriptorStatus: runtimeSession?.problem?.code || (trustedRuntimeSession ? "trusted" : "missing"),
    descriptorDetail: runtimeSession?.problem?.detail || "",
  };
}

function resolveHomeDir({ env = process.env } = {}) {
  return normalizeNonEmptyString(env?.HOME) || os.homedir();
}

function detectCommandPath(command, {
  execFileSyncImpl = execFileSync,
} = {}) {
  try {
    const output = execFileSyncImpl("which", [command], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    });
    return normalizeNonEmptyString(output);
  } catch {
    return "";
  }
}

function normalizeNonEmptyString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

function safeReadUtf8(fsImpl, filePath) {
  try {
    return fsImpl.readFileSync(filePath, "utf8");
  } catch {
    return "";
  }
}

function readRuntimeSessionDescriptor({
  fsImpl,
  filePath,
  isProcessAliveImpl = isProcessAlive,
}) {
  try {
    const parsed = JSON.parse(fsImpl.readFileSync(filePath, "utf8"));
    if (!parsed || typeof parsed !== "object") {
      return {
        descriptor: null,
        problem: {
          code: "invalid-json",
          detail: "Runtime session descriptor must be a JSON object.",
        },
      };
    }
    if (parsed.version !== 1) {
      return {
        descriptor: null,
        problem: {
          code: "unsupported-version",
          detail: "Runtime session descriptor version is unsupported.",
        },
      };
    }
    if (normalizeNonEmptyString(parsed.source) !== "desktop") {
      return {
        descriptor: null,
        problem: {
          code: "invalid-source",
          detail: "Runtime session descriptor source must be \"desktop\".",
        },
      };
    }
    if (normalizeNonEmptyString(parsed.transport) !== "websocket") {
      return {
        descriptor: null,
        problem: {
          code: "invalid-transport",
          detail: "Runtime session descriptor transport must be \"websocket\".",
        },
      };
    }

    const baseUrl = normalizeLoopbackWebSocketUrl(parsed.baseUrl);
    const authToken = normalizeNonEmptyString(parsed.authToken);
    if (!baseUrl) {
      return {
        descriptor: null,
        problem: {
          code: "invalid-endpoint",
          detail: "Runtime session descriptor baseUrl must be a loopback ws:// URL.",
        },
      };
    }
    if (!authToken) {
      return {
        descriptor: null,
        problem: {
          code: "missing-auth-token",
          detail: "Runtime session descriptor is missing its auth token.",
        },
      };
    }

    const backendPid = Number.isInteger(parsed.backendPid) && parsed.backendPid > 0
      ? parsed.backendPid
      : null;
    if (backendPid && !isProcessAliveImpl(backendPid)) {
      return {
        descriptor: null,
        problem: {
          code: "stale-backend-pid",
          detail: `Runtime session descriptor references a backend process (${backendPid}) that is no longer running.`,
        },
      };
    }

    return {
      descriptor: {
        baseUrl,
        endpoint: normalizeDesktopT3WebSocketEndpoint(baseUrl),
        authToken,
      },
      problem: null,
    };
  } catch {
    return {
      descriptor: null,
      problem: {
        code: "missing",
        detail: "",
      },
    };
  }
}

function normalizeLoopbackWebSocketUrl(value) {
  const rawValue = normalizeNonEmptyString(value);
  if (!rawValue) {
    return "";
  }
  try {
    const parsed = new URL(rawValue);
    const protocol = normalizeNonEmptyString(parsed.protocol).toLowerCase();
    if (protocol !== "ws:" && protocol !== "wss:") {
      return "";
    }
    const hostname = normalizeNonEmptyString(parsed.hostname).toLowerCase();
    if (hostname !== "127.0.0.1" && hostname !== "localhost" && hostname !== "::1" && hostname !== "[::1]") {
      return "";
    }
    return parsed.toString().replace(/\/$/, "");
  } catch {
    return "";
  }
}

function isProcessAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return normalizeNonEmptyString(error?.code) === "EPERM";
  }
}

function extractLatestDesktopWebSocketEndpoint(logText) {
  const pattern = /bootstrap resolved websocket endpoint baseUrl=(ws:\/\/127\.0\.0\.1:\d+)/g;
  let match = null;
  let latest = "";
  while ((match = pattern.exec(logText)) !== null) {
    latest = normalizeDesktopT3WebSocketEndpoint(match[1]);
  }
  return latest;
}

function normalizeDesktopT3WebSocketEndpoint(value) {
  const normalizedUrl = normalizeLoopbackWebSocketUrl(value);
  if (!normalizedUrl) {
    return "";
  }
  try {
    const parsed = new URL(normalizedUrl);
    const pathname = normalizeNonEmptyString(parsed.pathname);
    if (pathname && pathname !== "/" && pathname !== "/ws" && pathname !== "/ws/") {
      return "";
    }
    parsed.pathname = "/ws";
    parsed.search = "";
    parsed.hash = "";
    return parsed.toString();
  } catch {
    return "";
  }
}

function extractLatestAuthEnabled(logText) {
  const pattern = /(?:\\?"authEnabled\\?"):(true|false)/g;
  let match = null;
  let latest = null;
  while ((match = pattern.exec(logText)) !== null) {
    latest = match[1] === "true";
  }
  return latest;
}

module.exports = {
  DEFAULT_T3_LOOPBACK_ENDPOINT,
  KNOWN_T3_DESKTOP_APP_PATHS,
  detectInstalledT3Runtime,
  extractLatestDesktopWebSocketEndpoint,
  isProcessAlive,
  normalizeDesktopT3WebSocketEndpoint,
  normalizeLoopbackWebSocketUrl,
  readDesktopT3Session,
  resolveHomeDir,
  resolveT3RuntimeEndpoint,
};
