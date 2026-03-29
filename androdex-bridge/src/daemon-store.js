// FILE: daemon-store.js
// Purpose: Persists daemon control metadata and stable runtime preferences under ~/.androdex.
// Layer: CLI helper
// Exports: read/write helpers for daemon control and runtime state.
// Depends on: fs, os, path, crypto

const fs = require("fs");
const os = require("os");
const path = require("path");
const { randomBytes } = require("crypto");

const STORE_DIR = path.join(os.homedir(), ".androdex");
const CONTROL_FILE = path.join(STORE_DIR, "daemon-control.json");
const RUNTIME_FILE = path.join(STORE_DIR, "daemon-runtime.json");
const LOG_FILE = path.join(STORE_DIR, "daemon.log");
const START_LOCK_FILE = path.join(STORE_DIR, "daemon-start.lock");
const MAX_RECENT_WORKSPACES = 25;

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
    recentWorkspaces: normalizeRecentWorkspaces(runtimeState?.recentWorkspaces),
  };
}

function writeDaemonRuntimeState(state) {
  writeJsonFile(RUNTIME_FILE, {
    lastActiveCwd: normalizeNonEmptyString(state?.lastActiveCwd),
    recentWorkspaces: normalizeRecentWorkspaces(state?.recentWorkspaces),
  });
}

function createDaemonControlToken() {
  return randomBytes(24).toString("hex");
}

function tryAcquireDaemonStartLock() {
  ensureStoreDir();
  const lockPayload = JSON.stringify({
    pid: process.pid,
    createdAt: Date.now(),
  });
  let fd = null;
  try {
    fd = fs.openSync(START_LOCK_FILE, "wx", 0o600);
    fs.writeFileSync(fd, lockPayload, "utf8");
    return {
      fd,
      path: START_LOCK_FILE,
    };
  } catch (error) {
    if (fd != null) {
      try {
        fs.closeSync(fd);
      } catch {
        // Ignore cleanup failures for a partially acquired lock.
      }
    }
    if (error && error.code === "EEXIST") {
      return null;
    }
    throw error;
  }
}

function releaseDaemonStartLock(lockHandle) {
  if (!lockHandle) {
    return;
  }
  try {
    if (typeof lockHandle.fd === "number") {
      fs.closeSync(lockHandle.fd);
    }
  } catch {
    // Ignore close failures on lock release.
  }
  try {
    fs.rmSync(lockHandle.path || START_LOCK_FILE, { force: true });
  } catch {
    // Ignore cleanup failures for stale lock metadata.
  }
}

function readDaemonStartLockState() {
  return readJsonFile(START_LOCK_FILE);
}

function clearStaleDaemonStartLock({ staleAfterMs = 15_000 } = {}) {
  const lockState = readDaemonStartLockState();
  if (!lockState) {
    clearDaemonStartLockFile();
    return true;
  }

  const lockPid = Number(lockState.pid);
  const createdAt = Number(lockState.createdAt);
  const ageMs = Number.isFinite(createdAt) ? Date.now() - createdAt : Number.POSITIVE_INFINITY;
  const lockLooksStale = ageMs >= staleAfterMs || !isProcessAlive(lockPid);
  if (!lockLooksStale) {
    return false;
  }

  clearDaemonStartLockFile();
  return true;
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

function normalizeRecentWorkspaces(value) {
  if (!Array.isArray(value)) {
    return [];
  }

  const normalized = [];
  const seen = new Set();
  for (const candidate of value) {
    const trimmed = normalizeNonEmptyString(candidate);
    if (!trimmed || !path.isAbsolute(trimmed)) {
      continue;
    }

    const dedupeKey = process.platform === "win32" ? trimmed.toLowerCase() : trimmed;
    if (seen.has(dedupeKey)) {
      continue;
    }

    seen.add(dedupeKey);
    normalized.push(trimmed);
    if (normalized.length >= MAX_RECENT_WORKSPACES) {
      break;
    }
  }

  return normalized;
}

function clearDaemonStartLockFile() {
  try {
    fs.rmSync(START_LOCK_FILE, { force: true });
  } catch {
    // Ignore cleanup failures for stale lock metadata.
  }
}

function isProcessAlive(pid) {
  if (!Number.isInteger(pid) || pid <= 0) {
    return false;
  }
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return error?.code === "EPERM";
  }
}

module.exports = {
  clearStaleDaemonStartLock,
  clearDaemonControlState,
  createDaemonControlToken,
  getDaemonLogPath,
  MAX_RECENT_WORKSPACES,
  readDaemonControlState,
  readDaemonStartLockState,
  readDaemonRuntimeState,
  releaseDaemonStartLock,
  tryAcquireDaemonStartLock,
  writeDaemonControlState,
  writeDaemonRuntimeState,
};
