// FILE: index.js
// Purpose: Small entrypoint wrapper for the daemon-backed bridge runtime.
// Layer: CLI entry
// Exports: daemon-backed CLI actions and thread helpers.
// Depends on: ./daemon-control, ./daemon-runtime, ./secure-device-state, ./session-state, ./rollout-watch

const {
  createPairing,
  getDaemonStatus,
  startBridge,
  startDaemonCli,
  stopDaemonCli,
} = require("./daemon-control");
const { runDaemonProcess } = require("./daemon-runtime");
const { resetBridgeDeviceState } = require("./secure-device-state");
const { openLastActiveThread } = require("./session-state");
const { watchThreadRollout } = require("./rollout-watch");

async function resetBridgePairing() {
  try {
    await stopDaemonCli();
  } catch {
    // Reset should still proceed if the daemon is already down or stale.
  }
  return resetBridgeDeviceState();
}

module.exports = {
  createPairing,
  getDaemonStatus,
  openLastActiveThread,
  resetBridgePairing,
  runDaemonProcess,
  startBridge,
  startDaemonCli,
  stopDaemonCli,
  watchThreadRollout,
};
