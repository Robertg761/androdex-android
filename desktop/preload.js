const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("androdexDesktop", {
  applyRuntimeConfig(payload) {
    return ipcRenderer.invoke("control:apply-runtime-config", payload);
  },
  getDoctorReport() {
    return ipcRenderer.invoke("control:get-doctor-report");
  },
  getSnapshot() {
    return ipcRenderer.invoke("control:get-snapshot");
  },
  openPath(targetPath) {
    return ipcRenderer.invoke("control:open-path", targetPath);
  },
  pickDirectory() {
    return ipcRenderer.invoke("control:pick-directory");
  },
  removeT3Descriptor() {
    return ipcRenderer.invoke("control:remove-t3-descriptor");
  },
  resetPairing() {
    return ipcRenderer.invoke("control:reset-pairing");
  },
  restartService(payload) {
    return ipcRenderer.invoke("control:restart-service", payload);
  },
  startService(payload) {
    return ipcRenderer.invoke("control:start-service", payload);
  },
  stopService() {
    return ipcRenderer.invoke("control:stop-service");
  },
  updateRuntimeConfig(payload) {
    return ipcRenderer.invoke("control:update-runtime-config", payload);
  },
  writeT3Descriptor(payload) {
    return ipcRenderer.invoke("control:write-t3-descriptor", payload);
  },
});
