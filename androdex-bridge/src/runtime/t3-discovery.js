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
} = {}) {
  const explicitEndpoint = normalizeNonEmptyString(env?.ANDRODEX_T3_ENDPOINT);
  if (explicitEndpoint) {
    return {
      endpoint: explicitEndpoint,
      source: "explicit",
    };
  }

  return {
    endpoint: DEFAULT_T3_LOOPBACK_ENDPOINT,
    source: "default-loopback",
  };
}

function detectInstalledT3Runtime({
  fsImpl = fs,
  execFileSyncImpl = execFileSync,
} = {}) {
  const desktopAppPath = KNOWN_T3_DESKTOP_APP_PATHS.find((candidate) => fsImpl.existsSync(candidate)) || "";
  const cliPath = detectCommandPath("t3", { execFileSyncImpl });
  const desktopSession = readDesktopT3Session({ fsImpl });
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
  homeDir = os.homedir(),
} = {}) {
  const desktopLogPath = path.join(homeDir, ".t3", "userdata", "logs", "desktop-main.log");
  const serverLogPath = path.join(homeDir, ".t3", "userdata", "logs", "server.log");
  const desktopLog = safeReadUtf8(fsImpl, desktopLogPath);
  const serverLog = safeReadUtf8(fsImpl, serverLogPath);
  const endpoint = extractLatestDesktopBaseUrl(desktopLog);
  const authEnabled = extractLatestAuthEnabled(serverLog);
  return {
    desktopLogPath,
    serverLogPath,
    endpoint,
    authEnabled,
  };
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

function extractLatestDesktopBaseUrl(logText) {
  const pattern = /bootstrap resolved websocket endpoint baseUrl=(ws:\/\/127\.0\.0\.1:\d+)/g;
  let match = null;
  let latest = "";
  while ((match = pattern.exec(logText)) !== null) {
    latest = normalizeNonEmptyString(match[1]);
  }
  return latest;
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
  readDesktopT3Session,
  resolveT3RuntimeEndpoint,
};
