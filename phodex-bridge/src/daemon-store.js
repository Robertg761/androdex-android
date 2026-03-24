// FILE: daemon-store.js
// Purpose: Persists daemon control metadata and stable runtime preferences under ~/.relaydex.
// Layer: CLI helper
// Exports: read/write helpers for daemon control and runtime state.
// Depends on: fs, os, path, crypto

const fs = require("fs");
const os = require("os");
const path = require("path");
const { randomBytes } = require("crypto");

const STORE_DIR = path.join(os.homedir(), ".relaydex");
const CONTROL_FILE = path.join(STORE_DIR, "daemon-control.json");
const RUNTIME_FILE = path.join(STORE_DIR, "daemon-runtime.json");
const LOG_FILE = path.join(STORE_DIR, "daemon.log");

function ensureStoreDir() {
  fs.mkdirSync(STORE_DIR, { recursive: true });
}

function readDaemonControlState() {
  return readJsonFile(CONTROL_FILE);
}

function writeDaemonControlState(state) {
  writeJsonFile(CONTROL_FILE, state);
}

function clearDaemonControlState() {
  try {
    fs.rmSync(CONTROL_FILE, { force: true });
  } catch {
    // Ignore cleanup failures for stale daemon metadata.
  }
}

function readDaemonRuntimeState() {
  const runtimeState = readJsonFile(RUNTIME_FILE);
  return {
    lastActiveCwd: normalizeNonEmptyString(runtimeState?.lastActiveCwd),
  };
}

function writeDaemonRuntimeState(state) {
  writeJsonFile(RUNTIME_FILE, {
    lastActiveCwd: normalizeNonEmptyString(state?.lastActiveCwd),
  });
}

function createDaemonControlToken() {
  return randomBytes(24).toString("hex");
}

function getDaemonLogPath() {
  ensureStoreDir();
  return LOG_FILE;
}

function readJsonFile(filePath) {
  try {
    if (!fs.existsSync(filePath)) {
      return null;
    }
    return JSON.parse(fs.readFileSync(filePath, "utf8"));
  } catch {
    return null;
  }
}

function writeJsonFile(filePath, value) {
  ensureStoreDir();
  fs.writeFileSync(filePath, JSON.stringify(value, null, 2), { mode: 0o600 });
  try {
    fs.chmodSync(filePath, 0o600);
  } catch {
    // Best effort only.
  }
}

function normalizeNonEmptyString(value) {
  return typeof value === "string" ? value.trim() : "";
}

module.exports = {
  clearDaemonControlState,
  createDaemonControlToken,
  getDaemonLogPath,
  readDaemonControlState,
  readDaemonRuntimeState,
  writeDaemonControlState,
  writeDaemonRuntimeState,
};
