#!/usr/bin/env node
// FILE: remodex.js
// Purpose: CLI surface for daemon start, workspace activation, pairing, thread resume, and rollout tailing.
// Layer: CLI binary
// Exports: none
// Depends on: ../src

const {
  createPairing,
  getDaemonStatus,
  runDaemonProcess,
  startBridge,
  startDaemonCli,
  stopDaemonCli,
  resetBridgePairing,
  openLastActiveThread,
  watchThreadRollout,
} = require("../src");
const { printQR } = require("../src/qr");

const command = process.argv[2] || "up";

void main().catch((error) => {
  console.error(`[relaydex] ${(error && error.message) || "Command failed."}`);
  process.exit(1);
});

async function main() {
  if (command === "__daemon-run") {
    await runDaemonProcess();
    return;
  }

  if (command === "up") {
    const status = await startBridge();
    console.log(`[relaydex] Active workspace: ${status.currentCwd || process.cwd()}`);
    console.log(`[relaydex] Relay: ${status.relayStatus}`);
    return;
  }

  if (command === "pair") {
    const response = await createPairing();
    printQR(response.pairingPayload);
    return;
  }

  if (command === "daemon") {
    await handleDaemonCommand(process.argv[3] || "status");
    return;
  }

  if (command === "reset-pairing") {
    await resetBridgePairing();
    console.log("[relaydex] Cleared the saved pairing state. Run `relaydex pair` to create a fresh pairing QR.");
    return;
  }

  if (command === "resume") {
    const state = openLastActiveThread();
    console.log(
      `[relaydex] Opened last active thread: ${state.threadId} (${state.source || "unknown"})`
    );
    return;
  }

  if (command === "watch") {
    watchThreadRollout(process.argv[3] || "");
    return;
  }

  console.error(`Unknown command: ${command}`);
  console.error("Usage: relaydex up | relaydex pair | relaydex daemon [start|stop|status] | relaydex reset-pairing | relaydex resume | relaydex watch [threadId]");
  process.exit(1);
}

async function handleDaemonCommand(subcommand) {
  if (subcommand === "start") {
    const response = await startDaemonCli();
    printDaemonStatus(response.status);
    return;
  }

  if (subcommand === "stop") {
    await stopDaemonCli();
    console.log("[relaydex] Daemon stopped.");
    return;
  }

  if (subcommand === "status") {
    const response = await getDaemonStatus();
    printDaemonStatus(response.status);
    return;
  }

  throw new Error(`Unknown daemon subcommand: ${subcommand}`);
}

function printDaemonStatus(status) {
  console.log(`[relaydex] Relay: ${status.relayStatus || "unknown"}`);
  console.log(`[relaydex] Host ID: ${status.hostId || "unavailable"}`);
  console.log(`[relaydex] Workspace: ${status.currentCwd || "none"}`);
  console.log(`[relaydex] Workspace active: ${status.workspaceActive ? "yes" : "no"}`);
}
