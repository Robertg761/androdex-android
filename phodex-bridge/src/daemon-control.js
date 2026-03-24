// FILE: daemon-control.js
// Purpose: CLI-facing helpers for starting, stopping, and talking to the background daemon.
// Layer: CLI helper
// Exports: daemon control actions used by the relaydex CLI.
// Depends on: child_process, http, path, ./daemon-store

const fs = require("fs");
const http = require("http");
const path = require("path");
const { spawn } = require("child_process");
const {
  clearDaemonControlState,
  getDaemonLogPath,
  readDaemonControlState,
} = require("./daemon-store");

const CONTROL_HOST = "127.0.0.1";
const DAEMON_START_TIMEOUT_MS = 8_000;

async function startBridge() {
  const response = await activateWorkspace(process.cwd());
  return response.status;
}

async function activateWorkspace(cwd) {
  await ensureDaemonRunning();
  return requestDaemon("POST", "/activate", { cwd });
}

async function createPairing() {
  await ensureDaemonRunning();
  return requestDaemon("POST", "/pair", {});
}

async function getDaemonStatus() {
  const control = readDaemonControlState();
  if (!control) {
    return {
      ok: true,
      status: {
        relayStatus: "stopped",
        workspaceActive: false,
        currentCwd: null,
      },
    };
  }

  try {
    return await requestDaemon("GET", "/status");
  } catch {
    clearDaemonControlState();
    return {
      ok: true,
      status: {
        relayStatus: "stopped",
        workspaceActive: false,
        currentCwd: null,
      },
    };
  }
}

async function startDaemonCli() {
  await ensureDaemonRunning();
  return getDaemonStatus();
}

async function stopDaemonCli() {
  const control = readDaemonControlState();
  if (!control) {
    return {
      ok: true,
      status: {
        relayStatus: "stopped",
      },
    };
  }

  try {
    return await requestDaemon("POST", "/stop", {});
  } finally {
    clearDaemonControlState();
  }
}

async function ensureDaemonRunning() {
  const existingState = readDaemonControlState();
  if (existingState) {
    try {
      await requestDaemon("GET", "/status");
      return;
    } catch {
      clearDaemonControlState();
    }
  }

  const daemonScriptPath = path.join(__dirname, "..", "bin", "remodex.js");
  const logPath = getDaemonLogPath();
  const stdoutFd = fs.openSync(logPath, "a");
  const stderrFd = fs.openSync(logPath, "a");
  const child = spawn(
    process.execPath,
    [daemonScriptPath, "__daemon-run"],
    {
      detached: true,
      stdio: ["ignore", stdoutFd, stderrFd],
      windowsHide: true,
    }
  );
  child.unref();
  fs.closeSync(stdoutFd);
  fs.closeSync(stderrFd);

  const startedAt = Date.now();
  while (Date.now() - startedAt < DAEMON_START_TIMEOUT_MS) {
    const controlState = readDaemonControlState();
    if (controlState) {
      try {
        await requestDaemon("GET", "/status");
        return;
      } catch {
        // Keep polling until the daemon finishes booting or times out.
      }
    }
    await sleep(200);
  }

  throw new Error("Timed out while starting the Relaydex daemon.");
}

function requestDaemon(method, requestPath, body = null) {
  const controlState = readDaemonControlState();
  if (!controlState?.port || !controlState?.token) {
    return Promise.reject(new Error("The Relaydex daemon is not running."));
  }

  return new Promise((resolve, reject) => {
    const payload = body == null ? "" : JSON.stringify(body);
    const request = http.request(
      {
        hostname: CONTROL_HOST,
        port: controlState.port,
        path: requestPath,
        method,
        headers: {
          "x-relaydex-token": controlState.token,
          "content-type": "application/json; charset=utf-8",
          "content-length": Buffer.byteLength(payload),
        },
      },
      (response) => {
        let raw = "";
        response.setEncoding("utf8");
        response.on("data", (chunk) => {
          raw += chunk;
        });
        response.on("end", () => {
          try {
            const parsed = raw.trim() ? JSON.parse(raw) : {};
            if (response.statusCode >= 400) {
              reject(new Error(parsed.error || `Daemon request failed with status ${response.statusCode}.`));
              return;
            }
            resolve(parsed);
          } catch (error) {
            reject(error);
          }
        });
      }
    );

    request.on("error", reject);
    if (payload) {
      request.write(payload);
    }
    request.end();
  });
}

function sleep(timeoutMs) {
  return new Promise((resolve) => setTimeout(resolve, timeoutMs));
}

module.exports = {
  activateWorkspace,
  createPairing,
  getDaemonStatus,
  startBridge,
  startDaemonCli,
  stopDaemonCli,
};
