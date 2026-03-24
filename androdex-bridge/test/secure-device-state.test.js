const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");

const modulePath = path.resolve(__dirname, "../src/secure-device-state.js");

function withTempHome(run) {
  const tempHome = fs.mkdtempSync(path.join(os.tmpdir(), "androdex-device-state-"));
  const previousHome = process.env.HOME;
  const previousUserProfile = process.env.USERPROFILE;
  process.env.HOME = tempHome;
  process.env.USERPROFILE = tempHome;
  delete require.cache[modulePath];

  try {
    return run({
      tempHome,
      secureDeviceState: require(modulePath),
    });
  } finally {
    delete require.cache[modulePath];
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

test("trusted phone state is reloaded from persisted bridge device state", () => {
  withTempHome(({ secureDeviceState }) => {
    const initialState = secureDeviceState.loadOrCreateBridgeDeviceState();
    const updatedState = secureDeviceState.rememberTrustedPhone(
      initialState,
      "phone-1",
      "public-key-1"
    );

    delete require.cache[modulePath];
    const reloadedModule = require(modulePath);
    const reloadedState = reloadedModule.loadOrCreateBridgeDeviceState();

    assert.equal(reloadedState.hostId, updatedState.hostId);
    assert.equal(reloadedState.macDeviceId, updatedState.macDeviceId);
    assert.equal(
      reloadedModule.getTrustedPhonePublicKey(reloadedState, "phone-1"),
      "public-key-1"
    );
  });
});

test("resetBridgeDeviceState removes persisted trusted phone state", () => {
  withTempHome(({ secureDeviceState }) => {
    const initialState = secureDeviceState.loadOrCreateBridgeDeviceState();
    secureDeviceState.rememberTrustedPhone(initialState, "phone-2", "public-key-2");

    const resetResult = secureDeviceState.resetBridgeDeviceState();
    assert.equal(resetResult.hadState, true);

    delete require.cache[modulePath];
    const reloadedModule = require(modulePath);
    const reloadedState = reloadedModule.loadOrCreateBridgeDeviceState();

    assert.equal(
      reloadedModule.getTrustedPhonePublicKey(reloadedState, "phone-2"),
      null
    );
    assert.deepEqual(reloadedState.trustedPhones, {});
  });
});
