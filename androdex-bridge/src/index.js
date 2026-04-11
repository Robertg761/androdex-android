// FILE: index.js
// Purpose: Small entrypoint wrapper for bridge lifecycle commands.
// Layer: CLI entry
// Exports: bridge lifecycle, pairing reset, thread resume/watch, and macOS service helpers.
// Depends on: ./bridge, ./pairing/device-state, ./session-state, ./rollout/watch, ./macos-launch-agent

const { startBridge } = require("./bridge");
const { runBridgeDoctor } = require("./doctor");
const { getBridgeDoctorReport } = require("./doctor");
const { openLastActiveThread } = require("./session-state");
const { watchThreadRollout } = require("./rollout/watch");
const { readBridgeConfig } = require("./codex-desktop-refresher");
const {
  getBridgeDesktopSnapshot,
  readBridgeLogTail,
  removeDesktopT3RuntimeSessionDescriptor,
  restartBridgeServiceFromConfig,
  resetBridgePairing,
  resolveT3RuntimeSessionPath,
  startBridgeServiceFromConfig,
  stopBridgeService,
  updateBridgeRuntimeConfig,
  writeDesktopT3RuntimeSessionDescriptor,
} = require("./control-panel");
const {
  getMacOSBridgeServiceStatus,
  printMacOSBridgePairingQr,
  printMacOSBridgeServiceStatus,
  readForegroundBridgeConfig,
  resetMacOSBridgePairing,
  runMacOSBridgeService,
  startMacOSBridgeService,
  stopMacOSBridgeService,
} = require("./macos-launch-agent");

module.exports = {
  getMacOSBridgeServiceStatus,
  getBridgeDesktopSnapshot,
  getBridgeDoctorReport,
  printMacOSBridgePairingQr,
  printMacOSBridgeServiceStatus,
  readForegroundBridgeConfig,
  readBridgeLogTail,
  readBridgeConfig,
  removeDesktopT3RuntimeSessionDescriptor,
  resetMacOSBridgePairing,
  runBridgeDoctor,
  startBridgeServiceFromConfig,
  startBridge,
  runMacOSBridgeService,
  startMacOSBridgeService,
  restartBridgeServiceFromConfig,
  stopMacOSBridgeService,
  stopBridgeService,
  updateBridgeRuntimeConfig,
  writeDesktopT3RuntimeSessionDescriptor,
  resolveT3RuntimeSessionPath,
  resetBridgePairing,
  openLastActiveThread,
  watchThreadRollout,
};
