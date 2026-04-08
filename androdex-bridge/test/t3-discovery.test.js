const test = require("node:test");
const assert = require("node:assert/strict");

const {
  detectInstalledT3Runtime,
  readDesktopT3Session,
  resolveT3RuntimeEndpoint,
} = require("../src/runtime/t3-discovery");

test("resolveT3RuntimeEndpoint defaults T3 companion mode to the standard local websocket", () => {
  const resolved = resolveT3RuntimeEndpoint({
    env: {},
  });

  assert.equal(resolved.endpoint, "ws://127.0.0.1:3773/ws");
  assert.equal(resolved.source, "default-loopback");
});

test("readDesktopT3Session extracts the latest desktop baseUrl and auth flag from local logs", () => {
  const files = new Map([
    ["/tmp/home/.t3/userdata/logs/desktop-main.log", [
      "[desktop] bootstrap resolved websocket endpoint baseUrl=ws://127.0.0.1:52331",
      "[desktop] bootstrap resolved websocket endpoint baseUrl=ws://127.0.0.1:57816",
    ].join("\n")],
    ["/tmp/home/.t3/userdata/logs/server.log", [
      'timestamp=... message="{\\"authEnabled\\":false}"',
      'timestamp=... message="{\\"authEnabled\\":true}"',
    ].join("\n")],
  ]);

  const session = readDesktopT3Session({
    homeDir: "/tmp/home",
    fsImpl: {
      readFileSync(filePath) {
        if (!files.has(filePath)) {
          throw new Error(`missing file ${filePath}`);
        }
        return files.get(filePath);
      },
    },
  });

  assert.equal(session.endpoint, "ws://127.0.0.1:57816");
  assert.equal(session.authEnabled, true);
});

test("detectInstalledT3Runtime returns desktop session data alongside app discovery", () => {
  const runtime = detectInstalledT3Runtime({
    fsImpl: {
      existsSync(filePath) {
        return filePath === "/Applications/T3 Code (Alpha).app";
      },
      readFileSync(filePath) {
        if (filePath.endsWith("desktop-main.log")) {
          return "[desktop] bootstrap resolved websocket endpoint baseUrl=ws://127.0.0.1:57816";
        }
        if (filePath.endsWith("server.log")) {
          return 'timestamp=... message="{\\"authEnabled\\":true}"';
        }
        throw new Error(`unexpected file ${filePath}`);
      },
    },
    execFileSyncImpl() {
      throw new Error("missing command");
    },
  });

  assert.equal(runtime.desktopAppInstalled, true);
  assert.equal(runtime.desktopSession.endpoint, "ws://127.0.0.1:57816");
  assert.equal(runtime.desktopSession.authEnabled, true);
});
