// FILE: server.js
// Purpose: Standalone HTTP/HTTPS wrapper around the Androdex WebSocket relay.
// Layer: relay runtime

const fs = require("fs");
const http = require("http");
const https = require("https");
const { WebSocketServer } = require("ws");
const { setupRelay, getRelayStats } = require("./relay");

const host = readEnv("ANDRODEX_RELAY_HOST", "0.0.0.0");
const port = parsePort(readEnv("ANDRODEX_RELAY_PORT", "8787"));
const tlsCertPath = readEnv("ANDRODEX_RELAY_TLS_CERT", "");
const tlsKeyPath = readEnv("ANDRODEX_RELAY_TLS_KEY", "");
const isTlsEnabled = Boolean(tlsCertPath && tlsKeyPath);

const server = createServer();
const wss = new WebSocketServer({ server });
setupRelay(wss);

server.listen(port, host, () => {
  const protocol = isTlsEnabled ? "wss" : "ws";
  console.log(`[androdex-relay] listening on ${protocol}://${host}:${port}/relay`);
});

function createServer() {
  const requestListener = (req, res) => {
    if (req.url === "/healthz") {
      writeJson(res, 200, {
        ok: true,
        protocol: isTlsEnabled ? "https" : "http",
        relayPath: "/relay/{hostId}",
        ...getRelayStats(),
      });
      return;
    }

    writeJson(res, 404, { ok: false, error: "Not found" });
  };

  if (!isTlsEnabled) {
    return http.createServer(requestListener);
  }

  return https.createServer(
    {
      cert: fs.readFileSync(tlsCertPath),
      key: fs.readFileSync(tlsKeyPath),
    },
    requestListener
  );
}

function writeJson(res, statusCode, body) {
  res.writeHead(statusCode, { "content-type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(body));
}

function parsePort(rawValue) {
  const parsed = Number(rawValue);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 65535) {
    throw new Error(`Invalid ANDRODEX_RELAY_PORT: ${rawValue}`);
  }
  return parsed;
}

function readEnv(name, fallback) {
  const value = process.env[name];
  return typeof value === "string" && value.trim() ? value.trim() : fallback;
}
