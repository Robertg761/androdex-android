const snapshotPollIntervalMs = 2500;

const state = {
  doctorReport: null,
  logKind: "stdout",
  pairingPayload: null,
  pairingQrDataUrl: null,
  snapshot: null,
};

const els = {
  bridgePid: document.querySelector("#bridge-pid"),
  bridgeState: document.querySelector("#bridge-state"),
  connectionChip: document.querySelector("#connection-chip"),
  descriptorAuthTokenInput: document.querySelector("#descriptor-auth-token-input"),
  descriptorBackendPidInput: document.querySelector("#descriptor-backend-pid-input"),
  descriptorBaseUrlInput: document.querySelector("#descriptor-base-url-input"),
  descriptorForm: document.querySelector("#descriptor-form"),
  descriptorStatusNote: document.querySelector("#descriptor-status-note"),
  doctorRecommendations: document.querySelector("#doctor-recommendations"),
  doctorSummary: document.querySelector("#doctor-summary"),
  launchdState: document.querySelector("#launchd-state"),
  lastRefreshLabel: document.querySelector("#last-refresh-label"),
  logsOutput: document.querySelector("#logs-output"),
  pairingCreatedAtLabel: document.querySelector("#pairing-created-at-label"),
  pairingPayloadText: document.querySelector("#pairing-payload-text"),
  pairingQrImage: document.querySelector("#pairing-qr-image"),
  pairingQrPlaceholder: document.querySelector("#pairing-qr-placeholder"),
  pairingStatusLabel: document.querySelector("#pairing-status-label"),
  applyRuntimeConfigButton: document.querySelector("#apply-runtime-config-button"),
  refreshEnabledInput: document.querySelector("#refresh-enabled-input"),
  refreshState: document.querySelector("#refresh-state"),
  runtimeAuthTokenInput: document.querySelector("#runtime-auth-token-input"),
  runtimeChoiceNote: document.querySelector("#runtime-choice-note"),
  runtimeConfigForm: document.querySelector("#runtime-config-form"),
  runtimeConfigNote: document.querySelector("#runtime-config-note"),
  runtimeEndpointInput: document.querySelector("#runtime-endpoint-input"),
  runtimeLabel: document.querySelector("#runtime-label"),
  saveRuntimeConfigButton: document.querySelector("#save-runtime-config-button"),
  runtimeSyncFoot: document.querySelector("#runtime-sync-foot"),
  runtimeSyncState: document.querySelector("#runtime-sync-state"),
  serviceInstalled: document.querySelector("#service-installed"),
  t3RuntimeFields: document.querySelector("#t3-runtime-fields"),
  t3DiscoveryFoot: document.querySelector("#t3-discovery-foot"),
  t3DiscoveryState: document.querySelector("#t3-discovery-state"),
  toast: document.querySelector("#toast"),
  trustedPhone: document.querySelector("#trusted-phone"),
  pairingFreshness: document.querySelector("#pairing-freshness"),
};

function setToast(message, variant = "info") {
  els.toast.textContent = message;
  els.toast.dataset.variant = variant;
  els.toast.hidden = false;
  window.clearTimeout(setToast.timeoutId);
  setToast.timeoutId = window.setTimeout(() => {
    els.toast.hidden = true;
  }, 2600);
}

function formatBoolean(value, {
  truthy = "Yes",
  falsy = "No",
} = {}) {
  return value ? truthy : falsy;
}

function formatRuntimeLabel(snapshot) {
  const runtimeTarget = snapshot?.serviceStatus?.runtimeConfig?.runtimeTarget
    || snapshot?.serviceStatus?.bridgeStatus?.runtimeTarget
    || "unknown";
  return runtimeTarget === "t3-server" ? "Runtime: T3 Server" : "Runtime: Codex Native";
}

function formatConnectionLabel(snapshot) {
  const connection = snapshot?.serviceStatus?.bridgeStatus?.connectionStatus || "unknown";
  const bridgeState = snapshot?.serviceStatus?.bridgeStatus?.state || "unknown";
  return `${bridgeState} / ${connection}`;
}

function listRuntimeTargetOptions(snapshot) {
  return Array.isArray(snapshot?.runtimeTargetOptions) ? snapshot.runtimeTargetOptions : [];
}

function findRuntimeTargetOption(snapshot, value) {
  const normalizedValue = typeof value === "string" ? value.trim() : "";
  return listRuntimeTargetOptions(snapshot).find((option) => option?.value === normalizedValue) || null;
}

function selectedRuntimeTargetValue(snapshot) {
  return document.querySelector('input[name="targetKind"]:checked')?.value
    || snapshot?.daemonConfig?.runtimeTarget
    || snapshot?.serviceStatus?.runtimeConfig?.runtimeTarget
    || "codex-native";
}

function describeRuntimeChoice(snapshot, targetKind) {
  const option = findRuntimeTargetOption(snapshot, targetKind);
  const blockedAlternate = listRuntimeTargetOptions(snapshot).find((candidate) => candidate?.enabled === false && !candidate?.selected);
  if (!option) {
    return {
      disabled: false,
      message: "Pick the host runtime you want to run right now.",
      variant: "default",
    };
  }
  if (option.selected && option.enabled !== false) {
    if (blockedAlternate?.availabilityMessage) {
      return {
        disabled: false,
        message: `${option.title} is active. ${blockedAlternate.title} is unavailable right now: ${blockedAlternate.availabilityMessage}`,
        variant: "warning",
      };
    }
    return {
      disabled: false,
      message: `${option.title} is active on the host right now.`,
      variant: "success",
    };
  }
  if (option.enabled === false) {
    return {
      disabled: true,
      message: option.availabilityMessage || `${option.title} is not ready yet.`,
      variant: "warning",
    };
  }
  return {
    disabled: false,
    message: `${option.title} is ready. Apply when you want the bridge to switch over.`,
    variant: "success",
  };
}

function syncRuntimeSelectionUi(snapshot) {
  const selectedTarget = selectedRuntimeTargetValue(snapshot);
  const selectionState = describeRuntimeChoice(snapshot, selectedTarget);

  document.querySelectorAll('label[data-runtime-option]').forEach((label) => {
    const option = findRuntimeTargetOption(snapshot, label.dataset.runtimeOption);
    const input = label.querySelector('input[name="targetKind"]');
    if (!input) {
      return;
    }
    const isUnavailable = Boolean(option && option.enabled === false && !option.selected);
    const isSelectedUnavailable = Boolean(option && option.enabled === false && option.selected);
    input.disabled = isUnavailable;
    label.classList.toggle("is-unavailable", isUnavailable);
    label.classList.toggle("is-selected-unavailable", isSelectedUnavailable);
    label.title = option?.availabilityMessage || "";
  });

  if (els.t3RuntimeFields) {
    els.t3RuntimeFields.classList.toggle("is-hidden", selectedTarget !== "t3-server");
  }
  if (els.runtimeChoiceNote) {
    els.runtimeChoiceNote.textContent = selectionState.message;
    if (selectionState.variant === "default") {
      delete els.runtimeChoiceNote.dataset.variant;
    } else {
      els.runtimeChoiceNote.dataset.variant = selectionState.variant;
    }
  }
  if (els.saveRuntimeConfigButton) {
    els.saveRuntimeConfigButton.disabled = selectionState.disabled;
    els.saveRuntimeConfigButton.title = selectionState.disabled ? selectionState.message : "";
  }
  if (els.applyRuntimeConfigButton) {
    els.applyRuntimeConfigButton.disabled = selectionState.disabled;
    els.applyRuntimeConfigButton.title = selectionState.disabled ? selectionState.message : "";
  }
}

function updatePairingView(snapshot) {
  const pairingSession = snapshot?.serviceStatus?.pairingSession || null;
  const freshness = snapshot?.serviceStatus?.pairingFreshness || "missing";
  const payload = state.pairingPayload || pairingSession?.pairingPayload || null;
  const createdAt = pairingSession?.createdAt || "Unknown";

  els.pairingStatusLabel.textContent = payload
    ? `Payload ready (${freshness})`
    : `No payload (${freshness})`;
  els.pairingCreatedAtLabel.textContent = createdAt;
  els.pairingPayloadText.value = payload
    ? JSON.stringify(payload, null, 2)
    : "";

  if (state.pairingQrDataUrl) {
    els.pairingQrImage.src = state.pairingQrDataUrl;
    els.pairingQrImage.hidden = false;
    els.pairingQrPlaceholder.hidden = true;
  } else {
    els.pairingQrImage.hidden = true;
    els.pairingQrPlaceholder.hidden = false;
  }
}

function updateDoctorView() {
  const report = state.doctorReport;
  if (!report) {
    return;
  }
  const probeLabel = report.endpointProbe
    ? (report.endpointProbe.reachable ? "Endpoint reachable" : `Endpoint probe: ${report.endpointProbe.reasonCode}`)
    : "No probe run yet";
  els.doctorSummary.textContent = `${report.t3Availability?.summary || "No T3 summary"} • ${probeLabel}`;
  els.doctorRecommendations.innerHTML = "";
  const recommendations = Array.isArray(report.recommendations) ? report.recommendations : [];
  if (recommendations.length === 0) {
    const item = document.createElement("li");
    item.textContent = "No doctor recommendations right now.";
    els.doctorRecommendations.appendChild(item);
    return;
  }
  for (const recommendation of recommendations) {
    const item = document.createElement("li");
    item.textContent = recommendation;
    els.doctorRecommendations.appendChild(item);
  }
}

function render(snapshot) {
  state.snapshot = snapshot;
  const serviceStatus = snapshot?.serviceStatus || {};
  const bridgeStatus = serviceStatus.bridgeStatus || {};
  const runtimeConfig = serviceStatus.runtimeConfig || {};
  const installedT3Runtime = snapshot?.installedT3Runtime || {};
  const t3Session = installedT3Runtime.desktopSession || {};

  els.connectionChip.textContent = formatConnectionLabel(snapshot);
  els.runtimeLabel.textContent = formatRuntimeLabel(snapshot);
  els.lastRefreshLabel.textContent = `Refreshed ${new Date().toLocaleTimeString()}`;

  els.bridgeState.textContent = bridgeStatus.state || "unknown";
  els.bridgePid.textContent = `PID ${serviceStatus.launchdPid || bridgeStatus.pid || "unknown"}`;
  els.trustedPhone.textContent = formatBoolean(serviceStatus.hasTrustedPhone, {
    truthy: "Trusted",
    falsy: "No trusted phone",
  });
  els.pairingFreshness.textContent = `Pairing ${serviceStatus.pairingFreshness || "unknown"}`;

  const attachState = bridgeStatus.runtimeAttachState || bridgeStatus.runtimeSubscriptionState || "Unavailable";
  els.runtimeSyncState.textContent = attachState;
  els.runtimeSyncFoot.textContent = runtimeConfig.runtimeTarget === "t3-server"
    ? [
        bridgeStatus.runtimeEndpointHost ? `Endpoint ${bridgeStatus.runtimeEndpointHost}` : "",
        Number.isFinite(bridgeStatus.runtimeSnapshotSequence) ? `Snapshot ${bridgeStatus.runtimeSnapshotSequence}` : "",
        Number.isFinite(bridgeStatus.runtimeReplaySequence) ? `Replay ${bridgeStatus.runtimeReplaySequence}` : "",
      ].filter(Boolean).join(" • ") || "Waiting for T3 attach metadata"
    : "Codex-native does not expose T3 replay metadata";

  els.t3DiscoveryState.textContent = installedT3Runtime.desktopAppInstalled || installedT3Runtime.cliInstalled
    ? "T3 detected"
    : "T3 not detected";
  els.t3DiscoveryFoot.textContent = t3Session.endpoint
    ? `${t3Session.source || "unknown"} • ${t3Session.descriptorStatus || "missing"}`
    : (installedT3Runtime.desktopAppInstalled ? "Desktop app present, no active session" : "No active desktop session");

  els.launchdState.textContent = formatBoolean(serviceStatus.launchdLoaded, {
    truthy: "Loaded",
    falsy: "Not loaded",
  });
  els.serviceInstalled.textContent = formatBoolean(serviceStatus.installed, {
    truthy: "Installed",
    falsy: "Not installed",
  });
  els.refreshState.textContent = formatBoolean(serviceStatus.refreshEnabled, {
    truthy: "Enabled",
    falsy: "Disabled",
  });

  const daemonConfig = snapshot?.daemonConfig || {};
  const runtimeTarget = daemonConfig.runtimeTarget || runtimeConfig.runtimeTarget || "codex-native";
  const radio = document.querySelector(`input[name="targetKind"][value="${runtimeTarget}"]`);
  if (radio) {
    radio.checked = true;
  }
  els.runtimeEndpointInput.value = daemonConfig.runtimeEndpoint || "";
  els.runtimeAuthTokenInput.value = daemonConfig.runtimeEndpointAuthToken || "";
  els.refreshEnabledInput.checked = Boolean(daemonConfig.refreshEnabled);
  els.runtimeConfigNote.textContent = runtimeTarget === "t3-server"
    ? (serviceStatus.t3Availability?.summary || "T3 attach not selected.")
    : "Codex-native is active. Switch to T3 when you want the bridge to attach to a local T3 runtime.";
  syncRuntimeSelectionUi(snapshot);

  const descriptor = snapshot?.runtimeSessionDescriptor || null;
  els.descriptorBaseUrlInput.value = descriptor?.baseUrl || "";
  els.descriptorAuthTokenInput.value = descriptor?.authToken || "";
  els.descriptorBackendPidInput.value = descriptor?.backendPid || "";
  els.descriptorStatusNote.textContent = descriptor
    ? `Descriptor loaded from ${snapshot.paths.runtimeSessionPath}`
    : `No runtime-session descriptor at ${snapshot.paths.runtimeSessionPath}`;

  const activeLog = state.logKind === "stderr" ? snapshot?.logs?.stderr : snapshot?.logs?.stdout;
  els.logsOutput.textContent = activeLog?.text || "No log output yet.";
  document.querySelectorAll("[data-log-kind]").forEach((button) => {
    button.classList.toggle("active", button.dataset.logKind === state.logKind);
  });

  updatePairingView(snapshot);
  updateDoctorView();
}

async function refreshSnapshot({
  quiet = false,
} = {}) {
  try {
    const snapshot = await window.androdexDesktop.getSnapshot();
    render(snapshot);
  } catch (error) {
    if (!quiet) {
      setToast(error.message || "Failed to refresh the bridge snapshot.", "danger");
    }
  }
}

async function refreshDoctorReport() {
  try {
    state.doctorReport = await window.androdexDesktop.getDoctorReport();
    updateDoctorView();
    setToast("Doctor report refreshed.", "success");
  } catch (error) {
    setToast(error.message || "Failed to run doctor.", "danger");
  }
}

async function applyRuntimeConfig(shouldRestart) {
  const formData = new FormData(els.runtimeConfigForm);
  const payload = {
    endpoint: els.runtimeEndpointInput.value.trim(),
    endpointAuthToken: els.runtimeAuthTokenInput.value.trim(),
    refreshEnabled: els.refreshEnabledInput.checked,
    targetKind: formData.get("targetKind"),
  };
  try {
    if (shouldRestart) {
      const snapshot = await window.androdexDesktop.applyRuntimeConfig(payload);
      render(snapshot);
      setToast("Runtime config saved and bridge restarted.", "success");
    } else {
      await window.androdexDesktop.updateRuntimeConfig(payload);
      setToast("Runtime config saved.", "success");
      await refreshSnapshot({ quiet: true });
    }
  } catch (error) {
    setToast(error.message || "Failed to apply runtime config.", "danger");
  }
}

async function writeDescriptor() {
  const backendPidValue = els.descriptorBackendPidInput.value.trim();
  try {
    await window.androdexDesktop.writeT3Descriptor({
      authToken: els.descriptorAuthTokenInput.value.trim(),
      backendPid: backendPidValue ? Number.parseInt(backendPidValue, 10) : null,
      baseUrl: els.descriptorBaseUrlInput.value.trim(),
    });
    setToast("T3 runtime-session descriptor written.", "success");
    await refreshSnapshot({ quiet: true });
  } catch (error) {
    setToast(error.message || "Failed to write the T3 descriptor.", "danger");
  }
}

async function generatePairingQr() {
  try {
    const result = await window.androdexDesktop.startService({
      waitForPairing: true,
    });
    state.pairingPayload = result.pairingPayload;
    state.pairingQrDataUrl = result.qrDataUrl;
    render(result.snapshot);
    setToast("Fresh pairing QR generated.", "success");
  } catch (error) {
    setToast(error.message || "Failed to generate a fresh pairing QR.", "danger");
  }
}

async function handleAction(action) {
  try {
    if (action === "refresh-all") {
      await refreshSnapshot();
      return;
    }
    if (action === "run-doctor") {
      await refreshDoctorReport();
      return;
    }
    if (action === "start-service") {
      const result = await window.androdexDesktop.startService({});
      state.pairingPayload = result.pairingPayload || state.pairingPayload;
      state.pairingQrDataUrl = result.qrDataUrl || state.pairingQrDataUrl;
      render(result.snapshot);
      setToast("Bridge service started.", "success");
      return;
    }
    if (action === "restart-service") {
      const result = await window.androdexDesktop.restartService({});
      render(result.snapshot);
      setToast("Bridge service restarted.", "success");
      return;
    }
    if (action === "stop-service") {
      const snapshot = await window.androdexDesktop.stopService();
      render(snapshot);
      setToast("Bridge service stopped.", "success");
      return;
    }
    if (action === "reset-pairing") {
      const snapshot = await window.androdexDesktop.resetPairing();
      state.pairingPayload = null;
      state.pairingQrDataUrl = null;
      render(snapshot);
      setToast("Saved pairing cleared and bridge stopped.", "success");
      return;
    }
    if (action === "generate-pairing") {
      await generatePairingQr();
      return;
    }
    if (action === "remove-descriptor") {
      await window.androdexDesktop.removeT3Descriptor();
      setToast("T3 runtime-session descriptor removed.", "success");
      await refreshSnapshot({ quiet: true });
      return;
    }
    if (action === "save-runtime-config") {
      await applyRuntimeConfig(false);
    }
  } catch (error) {
    setToast(error.message || `Action failed: ${action}`, "danger");
  }
}

function installListeners() {
  document.querySelectorAll('input[name="targetKind"]').forEach((input) => {
    input.addEventListener("change", () => {
      syncRuntimeSelectionUi(state.snapshot);
    });
  });

  document.querySelectorAll("[data-action]").forEach((button) => {
    button.addEventListener("click", () => {
      void handleAction(button.dataset.action);
    });
  });

  document.querySelectorAll("[data-open-path]").forEach((button) => {
    button.addEventListener("click", () => {
      const key = button.dataset.openPath;
      const targetPath = state.snapshot?.paths?.[key];
      if (!targetPath) {
        setToast("No path available yet.", "danger");
        return;
      }
      void window.androdexDesktop.openPath(targetPath);
    });
  });

  document.querySelectorAll("[data-log-kind]").forEach((button) => {
    button.addEventListener("click", () => {
      state.logKind = button.dataset.logKind || "stdout";
      render(state.snapshot);
    });
  });

  els.runtimeConfigForm.addEventListener("submit", (event) => {
    event.preventDefault();
    void applyRuntimeConfig(true);
  });

  els.descriptorForm.addEventListener("submit", (event) => {
    event.preventDefault();
    void writeDescriptor();
  });
}

installListeners();
void refreshSnapshot();
void refreshDoctorReport();
window.setInterval(() => {
  void refreshSnapshot({ quiet: true });
}, snapshotPollIntervalMs);
