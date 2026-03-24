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
const CLI_NAME = "androdex";
const CLI_PREFIX = `[${CLI_NAME}]`;

void main().catch((error) => {
  console.error(`${CLI_PREFIX} ${(error && error.message) || "Command failed."}`);
  process.exit(1);
});

async function main() {
  if (command === "__daemon-run") {
    await runDaemonProcess();
    return;
  }

  if (command === "up") {
    const status = await startBridge();
    console.log(`${CLI_PREFIX} Active workspace: ${status.currentCwd || process.cwd()}`);
    console.log(`${CLI_PREFIX} Relay: ${status.relayStatus}`);
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
    console.log(`${CLI_PREFIX} Cleared the saved pairing state. Run \`${CLI_NAME} pair\` to create a fresh pairing QR.`);
    return;
  }

  if (command === "resume") {
    const state = openLastActiveThread();
    console.log(
      `${CLI_PREFIX} Opened last active thread: ${state.threadId} (${state.source || "unknown"})`
    );
    return;
  }

  if (command === "watch") {
    watchThreadRollout(process.argv[3] || "");
    return;
  }

  console.error(`Unknown command: ${command}`);
  console.error("Usage: androdex up | androdex pair | androdex daemon [start|stop|status] | androdex reset-pairing | androdex resume | androdex watch [threadId]");
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
    console.log(`${CLI_PREFIX} Daemon stopped.`);
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
  console.log(`${CLI_PREFIX} Relay: ${status.relayStatus || "unknown"}`);
  console.log(`${CLI_PREFIX} Host ID: ${status.hostId || "unavailable"}`);
  console.log(`${CLI_PREFIX} Workspace: ${status.currentCwd || "none"}`);
  console.log(`${CLI_PREFIX} Workspace active: ${status.workspaceActive ? "yes" : "no"}`);
}
