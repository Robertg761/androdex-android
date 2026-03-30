#!/usr/bin/env node
// FILE: cli.js
// Purpose: CLI surface for foreground bridge runs, pairing reset, thread resume, and macOS service control.
// Layer: CLI binary
// Exports: none
// Depends on: ../src

const {
  printMacOSBridgePairingQr,
  printMacOSBridgeServiceStatus,
  readBridgeConfig,
  resetMacOSBridgePairing,
  runMacOSBridgeService,
  startBridge,
  startMacOSBridgeService,
  stopMacOSBridgeService,
  resetBridgePairing,
  openLastActiveThread,
  watchThreadRollout,
} = require("../src");
const { version } = require("../package.json");

const defaultDeps = {
  printMacOSBridgePairingQr,
  printMacOSBridgeServiceStatus,
  readBridgeConfig,
  resetMacOSBridgePairing,
  runMacOSBridgeService,
  startBridge,
  startMacOSBridgeService,
  stopMacOSBridgeService,
  resetBridgePairing,
  openLastActiveThread,
  watchThreadRollout,
};

if (require.main === module) {
  void main();
}

async function main({
  argv = process.argv,
  platform = process.platform,
  consoleImpl = console,
  exitImpl = process.exit,
  deps = defaultDeps,
} = {}) {
  const command = argv[2] || "up";

  if (isVersionCommand(command)) {
    consoleImpl.log(version);
    return;
  }

  if (command === "up") {
    assertMacOSCommand(command, {
      platform,
      consoleImpl,
      exitImpl,
    });
    deps.readBridgeConfig();
    const result = await deps.startMacOSBridgeService({
      waitForPairing: true,
      activeCwd: process.cwd(),
    });
    deps.printMacOSBridgePairingQr({
      pairingSession: result.pairingSession,
    });
    return;
  }

  if (command === "run") {
    assertMacOSCommand(command, {
      platform,
      consoleImpl,
      exitImpl,
    });
    deps.startBridge();
    return;
  }

  if (command === "run-service") {
    assertMacOSCommand(command, {
      platform,
      consoleImpl,
      exitImpl,
    });
    deps.runMacOSBridgeService();
    return;
  }

  if (command === "start") {
    assertMacOSCommand(command, {
      platform,
      consoleImpl,
      exitImpl,
    });
    deps.readBridgeConfig();
    await deps.startMacOSBridgeService({
      waitForPairing: false,
    });
    consoleImpl.log("[androdex] macOS bridge service is running.");
    return;
  }

  if (command === "restart") {
    assertMacOSCommand(command, {
      platform,
      consoleImpl,
      exitImpl,
    });
    deps.readBridgeConfig();
    await deps.startMacOSBridgeService({
      waitForPairing: false,
    });
    consoleImpl.log("[androdex] macOS bridge service restarted.");
    return;
  }

  if (command === "stop") {
    assertMacOSCommand(command, {
      platform,
      consoleImpl,
      exitImpl,
    });
    deps.stopMacOSBridgeService();
    consoleImpl.log("[androdex] macOS bridge service stopped.");
    return;
  }

  if (command === "status") {
    assertMacOSCommand(command, {
      platform,
      consoleImpl,
      exitImpl,
    });
    deps.printMacOSBridgeServiceStatus();
    return;
  }

  if (command === "reset-pairing") {
    try {
      if (platform === "darwin") {
        deps.resetMacOSBridgePairing();
        consoleImpl.log("[androdex] Stopped the macOS bridge service and cleared the saved pairing state. Run `androdex up` to pair again.");
      } else {
        deps.resetBridgePairing();
        consoleImpl.log("[androdex] Cleared the saved pairing state. Run `androdex up` to pair again.");
      }
    } catch (error) {
      consoleImpl.error(`[androdex] ${(error && error.message) || "Failed to clear the saved pairing state."}`);
      exitImpl(1);
    }
    return;
  }

  if (command === "resume") {
    try {
      const state = deps.openLastActiveThread();
      consoleImpl.log(
        `[androdex] Opened last active thread: ${state.threadId} (${state.source || "unknown"})`
      );
    } catch (error) {
      consoleImpl.error(`[androdex] ${(error && error.message) || "Failed to reopen the last thread."}`);
      exitImpl(1);
    }
    return;
  }

  if (command === "watch") {
    try {
      deps.watchThreadRollout(argv[3] || "");
    } catch (error) {
      consoleImpl.error(`[androdex] ${(error && error.message) || "Failed to watch the thread rollout."}`);
      exitImpl(1);
    }
    return;
  }

  consoleImpl.error(`Unknown command: ${command}`);
  consoleImpl.error(
    "Usage: androdex up | androdex run | androdex start | androdex restart | androdex stop | androdex status | "
    + "androdex reset-pairing | androdex resume | androdex watch [threadId] | androdex --version"
  );
  exitImpl(1);
}

function assertMacOSCommand(name, {
  platform = process.platform,
  consoleImpl = console,
  exitImpl = process.exit,
} = {}) {
  if (platform === "darwin") {
    return;
  }

  consoleImpl.error(`[androdex] \`${name}\` is only available on macOS right now.`);
  exitImpl(1);
}

function isVersionCommand(value) {
  return value === "-v" || value === "--v" || value === "-V" || value === "--version" || value === "version";
}

module.exports = {
  isVersionCommand,
  main,
};
