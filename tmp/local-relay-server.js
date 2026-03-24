const http = require("http");
const { WebSocketServer } = require("../androdex-bridge/node_modules/ws");
const { setupRelay, getRelayStats } = require("../relay/relay");

const host = process.env.ANDRODEX_LOCAL_RELAY_HOST || "0.0.0.0";
const port = Number(process.env.ANDRODEX_LOCAL_RELAY_PORT || 8787);

const server = http.createServer((req, res) => {
  if (req.url === "/healthz") {
    res.writeHead(200, { "content-type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({ ok: true, ...getRelayStats() }));
    return;
  }

  res.writeHead(404, { "content-type": "application/json; charset=utf-8" });
  res.end(JSON.stringify({ ok: false, error: "Not found" }));
});

const wss = new WebSocketServer({ server });
setupRelay(wss);

server.listen(port, host, () => {
  console.log(`[androdex-relay] listening on ws://${host}:${port}/relay`);
});
