// FILE: runtime/t3-discovery.js
// Purpose: Centralizes default T3 desktop discovery and the default loopback endpoint used for attach-first companion mode.
// Layer: runtime helper
// Exports: T3 endpoint defaults and installed-runtime discovery helpers
// Depends on: child_process, fs

const { execFileSync } = require("child_process");
const fs = require("fs");

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
  return {
    desktopAppInstalled: Boolean(desktopAppPath),
    desktopAppPath,
    cliInstalled: Boolean(cliPath),
    cliPath,
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

module.exports = {
  DEFAULT_T3_LOOPBACK_ENDPOINT,
  KNOWN_T3_DESKTOP_APP_PATHS,
  detectInstalledT3Runtime,
  resolveT3RuntimeEndpoint,
};
