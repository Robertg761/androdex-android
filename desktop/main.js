const path = require("path");
const fs = require("fs");
const { app, BrowserWindow, dialog, ipcMain, shell } = require("electron");
const QRCode = require("qrcode");
const bridge = require("../androdex-bridge/src");

function createWindow() {
  const window = new BrowserWindow({
    width: 1560,
    height: 1040,
    minWidth: 1280,
    minHeight: 860,
    title: "Androdex Control Room",
    backgroundColor: "#0b1117",
    autoHideMenuBar: true,
    webPreferences: {
      contextIsolation: true,
      preload: path.join(__dirname, "preload.js"),
      sandbox: false,
    },
  });

  window.loadFile(path.join(__dirname, "index.html"));
}

async function buildPairingPayloadResponse(result) {
  const pairingPayload = result?.pairingSession?.pairingPayload || null;
  const qrDataUrl = pairingPayload
    ? await QRCode.toDataURL(JSON.stringify(pairingPayload), {
        color: {
          dark: "#0b1117",
          light: "#f4efe6",
        },
        margin: 1,
        width: 420,
      })
    : null;

  return {
    pairingPayload,
    qrDataUrl,
  };
}

function registerIpc() {
  ipcMain.handle("control:get-snapshot", async () => {
    return bridge.getBridgeDesktopSnapshot({
      includeDoctor: false,
      includeLogs: true,
    });
  });

  ipcMain.handle("control:get-doctor-report", async () => {
    const snapshot = await bridge.getBridgeDesktopSnapshot({
      includeDoctor: true,
      includeLogs: false,
    });
    return snapshot.doctorReport;
  });

  ipcMain.handle("control:update-runtime-config", async (_event, payload = {}) => {
    return bridge.updateBridgeRuntimeConfig({
      endpoint: payload.endpoint,
      endpointAuthToken: payload.endpointAuthToken,
      refreshEnabled: payload.refreshEnabled,
      targetKind: payload.targetKind,
    });
  });

  ipcMain.handle("control:apply-runtime-config", async (_event, payload = {}) => {
    await bridge.updateBridgeRuntimeConfig({
      endpoint: payload.endpoint,
      endpointAuthToken: payload.endpointAuthToken,
      refreshEnabled: payload.refreshEnabled,
      targetKind: payload.targetKind,
    });
    await bridge.restartBridgeServiceFromConfig();
    return bridge.getBridgeDesktopSnapshot({
      includeDoctor: false,
      includeLogs: true,
    });
  });

  ipcMain.handle("control:start-service", async (_event, payload = {}) => {
    const result = await bridge.startBridgeServiceFromConfig({
      activeCwd: payload.activeCwd,
      waitForPairing: Boolean(payload.waitForPairing),
    });
    return {
      ...(await buildPairingPayloadResponse(result)),
      snapshot: await bridge.getBridgeDesktopSnapshot({
        includeDoctor: false,
        includeLogs: true,
      }),
    };
  });

  ipcMain.handle("control:restart-service", async (_event, payload = {}) => {
    const result = await bridge.restartBridgeServiceFromConfig({
      activeCwd: payload.activeCwd,
      waitForPairing: Boolean(payload.waitForPairing),
    });
    return {
      ...(await buildPairingPayloadResponse(result)),
      snapshot: await bridge.getBridgeDesktopSnapshot({
        includeDoctor: false,
        includeLogs: true,
      }),
    };
  });

  ipcMain.handle("control:stop-service", async () => {
    bridge.stopBridgeService();
    return bridge.getBridgeDesktopSnapshot({
      includeDoctor: false,
      includeLogs: true,
    });
  });

  ipcMain.handle("control:reset-pairing", async () => {
    bridge.resetBridgePairing();
    return bridge.getBridgeDesktopSnapshot({
      includeDoctor: false,
      includeLogs: true,
    });
  });

  ipcMain.handle("control:write-t3-descriptor", async (_event, payload = {}) => {
    return bridge.writeDesktopT3RuntimeSessionDescriptor({
      authToken: payload.authToken,
      backendPid: Number.isInteger(payload.backendPid) ? payload.backendPid : null,
      baseUrl: payload.baseUrl,
    });
  });

  ipcMain.handle("control:remove-t3-descriptor", async () => {
    return {
      filePath: bridge.removeDesktopT3RuntimeSessionDescriptor(),
    };
  });

  ipcMain.handle("control:open-path", async (_event, targetPath) => {
    if (typeof targetPath !== "string" || !targetPath.trim()) {
      throw new Error("A valid path is required.");
    }
    const normalizedPath = targetPath.trim();
    const fallbackDirectory = path.dirname(normalizedPath);
    const resolvedPath = fs.existsSync(normalizedPath)
      ? normalizedPath
      : fallbackDirectory;
    return shell.openPath(resolvedPath);
  });

  ipcMain.handle("control:pick-directory", async () => {
    const result = await dialog.showOpenDialog({
      properties: ["openDirectory", "createDirectory"],
    });
    if (result.canceled || !Array.isArray(result.filePaths) || result.filePaths.length === 0) {
      return null;
    }
    return result.filePaths[0];
  });
}

app.whenReady().then(() => {
  registerIpc();
  createWindow();

  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") {
    app.quit();
  }
});
