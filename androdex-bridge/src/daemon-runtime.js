// FILE: daemon-runtime.js
// Purpose: Runs the persistent host daemon and exposes a localhost control API for CLI commands.
// Layer: CLI service
// Exports: runDaemonProcess
// Depends on: http, ./daemon-store, ./host-runtime

const http = require("http");
const { clearDaemonControlState, createDaemonControlToken, writeDaemonControlState } = require("./daemon-store");
const { HostRuntime } = require("./host-runtime");

async function runDaemonProcess() {
  const runtime = new HostRuntime();
  const controlToken = createDaemonControlToken();
  let isShuttingDown = false;

  runtime.start();

  const server = http.createServer(async (req, res) => {
    try {
      if (!authorizeRequest(req, controlToken)) {
        writeJson(res, 401, { error: "Unauthorized" });
        return;
      }

      const url = new URL(req.url || "/", "http://127.0.0.1");
      if (req.method === "GET" && url.pathname === "/status") {
        writeJson(res, 200, { ok: true, status: runtime.getStatus() });
        return;
      }

      if (req.method === "POST" && url.pathname === "/activate") {
        const body = await readJsonBody(req);
        const status = await runtime.activateWorkspace({ cwd: body?.cwd });
        writeJson(res, 200, { ok: true, status });
        return;
      }

      if (req.method === "POST" && url.pathname === "/pair") {
        writeJson(res, 200, {
          ok: true,
          pairingPayload: runtime.getPairingPayload(),
          status: runtime.getStatus(),
        });
        return;
      }

      if (req.method === "POST" && url.pathname === "/stop") {
        writeJson(res, 200, { ok: true });
        setTimeout(() => {
          void shutdown();
        }, 25);
        return;
      }

      writeJson(res, 404, { error: "Not found" });
    } catch (error) {
      writeJson(res, 500, { error: error.message || "Daemon request failed." });
    }
  });

  server.on("clientError", (error, socket) => {
    socket.end("HTTP/1.1 400 Bad Request\r\n\r\n");
    if (error?.message) {
      console.error(`[androdex] Daemon control client error: ${error.message}`);
    }
  });

  await new Promise((resolve) => {
    server.listen(0, "127.0.0.1", resolve);
  });

  const address = server.address();
  writeDaemonControlState({
    pid: process.pid,
    port: typeof address === "object" && address ? address.port : 0,
    token: controlToken,
    startedAt: Date.now(),
  });

  async function shutdown() {
    if (isShuttingDown) {
      return;
    }
    isShuttingDown = true;
    clearDaemonControlState();
    await runtime.stop();
    await new Promise((resolve) => server.close(resolve));
    process.exit(0);
  }

  process.on("SIGINT", () => {
    void shutdown();
  });
  process.on("SIGTERM", () => {
    void shutdown();
  });
}

function authorizeRequest(req, controlToken) {
  return req.headers["x-androdex-token"] === controlToken;
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let raw = "";
    req.setEncoding("utf8");
    req.on("data", (chunk) => {
      raw += chunk;
      if (raw.length > 1024 * 1024) {
        reject(new Error("Request body too large."));
      }
    });
    req.on("end", () => {
      if (!raw.trim()) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(raw));
      } catch {
        reject(new Error("Invalid JSON request body."));
      }
    });
    req.on("error", reject);
  });
}

function writeJson(res, statusCode, body) {
  res.writeHead(statusCode, { "content-type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(body));
}

module.exports = {
  runDaemonProcess,
};
