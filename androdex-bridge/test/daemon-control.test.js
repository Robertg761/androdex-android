const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { EventEmitter } = require("events");
const childProcess = require("child_process");
const http = require("http");

const daemonControlPath = path.resolve(__dirname, "../src/daemon-control.js");
const daemonStorePath = path.resolve(__dirname, "../src/daemon-store.js");

async function withTempHome(run) {
  const tempHome = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-daemon-control-"));
  const previousHome = process.env.HOME;
  const previousUserProfile = process.env.USERPROFILE;
  process.env.HOME = tempHome;
  process.env.USERPROFILE = tempHome;
  delete require.cache[daemonStorePath];
  delete require.cache[daemonControlPath];

  try {
    return await run({ tempHome });
  } finally {
    delete require.cache[daemonStorePath];
    delete require.cache[daemonControlPath];
    if (previousHome == null) {
      delete process.env.HOME;
    } else {
      process.env.HOME = previousHome;
    }
    if (previousUserProfile == null) {
      delete process.env.USERPROFILE;
    } else {
      process.env.USERPROFILE = previousUserProfile;
    }
    fs.rmSync(tempHome, { recursive: true, force: true });
  }
}

function installDaemonHarness() {
  const daemonStore = require(daemonStorePath);
  const previousSpawn = childProcess.spawn;
  const previousHttpRequest = http.request;
  let spawnCalls = 0;

  childProcess.spawn = () => {
    spawnCalls += 1;
    setTimeout(() => {
      daemonStore.writeDaemonControlState({
        pid: 43210,
        port: 43123,
        token: "test-token",
        startedAt: Date.now(),
      });
    }, 40);
    return {
      unref() {},
    };
  };

  http.request = (options, callback) => {
    const request = new EventEmitter();
    request.write = () => {};
    request.end = () => {
      setImmediate(() => {
        const controlState = daemonStore.readDaemonControlState();
        const authorized = Boolean(
          controlState
          && options.port === controlState.port
          && options.headers?.["x-androdex-token"] === controlState.token
        );
        if (!authorized) {
          request.emit("error", new Error("daemon unavailable"));
          return;
        }

        const response = new EventEmitter();
        response.statusCode = 200;
        response.setEncoding = () => {};
        callback(response);
        setImmediate(() => {
          response.emit("data", JSON.stringify({
            ok: true,
            status: {
              relayStatus: "connected",
              workspaceActive: false,
              currentCwd: null,
            },
          }));
          response.emit("end");
        });
      });
    };
    return request;
  };

  return {
    daemonStore,
    restore() {
      childProcess.spawn = previousSpawn;
      http.request = previousHttpRequest;
      delete require.cache[daemonControlPath];
      delete require.cache[daemonStorePath];
    },
    get spawnCalls() {
      return spawnCalls;
    },
  };
}

test("concurrent daemon starts share one spawn", async () => {
  await withTempHome(async () => {
    const harness = installDaemonHarness();
    try {
      const { startDaemonCli } = require(daemonControlPath);

      await Promise.all([startDaemonCli(), startDaemonCli(), startDaemonCli()]);

      assert.equal(harness.spawnCalls, 1);
    } finally {
      harness.restore();
    }
  });
});

test("stale daemon start lock is cleared before spawning", async () => {
  await withTempHome(async ({ tempHome }) => {
    const storeDir = path.join(tempHome, ".androdex");
    fs.mkdirSync(storeDir, { recursive: true });
    fs.writeFileSync(
      path.join(storeDir, "daemon-start.lock"),
      JSON.stringify({
        pid: 999999,
        createdAt: Date.now() - 60_000,
      }),
      "utf8"
    );

    const harness = installDaemonHarness();
    try {
      const { startDaemonCli } = require(daemonControlPath);

      await startDaemonCli();

      assert.equal(harness.spawnCalls, 1);
      assert.equal(fs.existsSync(path.join(storeDir, "daemon-start.lock")), false);
    } finally {
      harness.restore();
    }
  });
});
