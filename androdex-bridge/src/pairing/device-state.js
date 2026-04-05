// FILE: pairing/device-state.js
// Purpose: Persists canonical bridge identity and trusted-phone state for local QR pairing.
// Layer: CLI helper
// Exports: loadOrCreateBridgeDeviceState, resetBridgeDeviceState, rememberTrustedPhone, getTrustedPhonePublicKey, getTrustedPhoneRecoveryIdentity, resolveBridgeRelaySession
// Depends on: fs, os, path, crypto, child_process

const fs = require("fs");
const os = require("os");
const path = require("path");
const { randomUUID, generateKeyPairSync } = require("crypto");
const { execFileSync } = require("child_process");

const DEFAULT_STORE_DIR = path.join(os.homedir(), ".androdex");
const KEYCHAIN_SERVICE = "io.androdex.bridge.device-state";
const KEYCHAIN_ACCOUNT = "default";
const KEYCHAIN_COMMAND_TIMEOUT_MS = 1_500;
const STABLE_RELAY_HOST_PREFIX = "mac.";
let hasLoggedMismatch = false;

function loadOrCreateBridgeDeviceState() {
  const fileRecord = readCanonicalFileStateRecord();
  const backupRecord = readBackupFileStateRecord();
  const keychainRecord = readKeychainStateRecord();

  if (fileRecord.state) {
    reconcileLegacyKeychainMirror(fileRecord.state, keychainRecord);
    return fileRecord.state;
  }

  if (fileRecord.error) {
    if (keychainRecord.state) {
      warnOnce("[androdex] Recovering the canonical device-state.json from the legacy Keychain pairing mirror.");
      writeBridgeDeviceState(keychainRecord.state, { backupExisting: false });
      return keychainRecord.state;
    }
    if (backupRecord.state) {
      warnOnce("[androdex] Recovering the canonical device-state.json from the last known-good backup.");
      writeBridgeDeviceState(backupRecord.state, { backupExisting: false });
      return backupRecord.state;
    }
    throw corruptedStateError("device-state.json", fileRecord.error);
  }

  if (keychainRecord.error) {
    throw corruptedStateError("legacy Keychain bridge state", keychainRecord.error);
  }

  if (keychainRecord.state) {
    writeBridgeDeviceState(keychainRecord.state, { backupExisting: false });
    return keychainRecord.state;
  }

  if (backupRecord.state) {
    writeBridgeDeviceState(backupRecord.state, { backupExisting: false });
    return backupRecord.state;
  }

  const nextState = createBridgeDeviceState();
  writeBridgeDeviceState(nextState);
  return nextState;
}

function resetBridgeDeviceState() {
  const removedCanonicalFile = deleteCanonicalFileState();
  const removedBackupFile = deleteBackupFileState();
  const removedKeychainMirror = deleteKeychainStateString();
  return {
    hadState: removedCanonicalFile || removedBackupFile || removedKeychainMirror,
    removedCanonicalFile,
    removedBackupFile,
    removedKeychainMirror,
  };
}

function resolveBridgeRelaySession(state) {
  return {
    deviceState: state,
    isPersistent: false,
    sessionId: randomUUID(),
  };
}

function stableRelayHostIdForMacDeviceId(macDeviceId) {
  const normalizedMacDeviceId = normalizeNonEmptyString(macDeviceId);
  if (!normalizedMacDeviceId) {
    return null;
  }
  return `${STABLE_RELAY_HOST_PREFIX}${normalizedMacDeviceId}`;
}

function rememberTrustedPhone(
  state,
  phoneDeviceId,
  phoneIdentityPublicKey,
  {
    recoveryIdentity = null,
    previousRecoveryIdentity = null,
    persist = true,
  } = {}
) {
  const normalizedDeviceId = normalizeNonEmptyString(phoneDeviceId);
  const normalizedPublicKey = normalizeNonEmptyString(phoneIdentityPublicKey);
  if (!normalizedDeviceId || !normalizedPublicKey) {
    return state;
  }

  const nextRecoveryIdentities = {};
  const normalizedRecoveryIdentity = normalizeRecoveryIdentity(recoveryIdentity);
  const normalizedPreviousRecoveryIdentity = normalizeRecoveryIdentity(previousRecoveryIdentity);
  const nextRecoveryIdentityChain = buildNextRecoveryIdentityChain({
    existingRecoveryIdentities: getTrustedPhoneRecoveryIdentities(state, normalizedDeviceId),
    nextRecoveryIdentity: normalizedRecoveryIdentity,
    previousRecoveryIdentity: normalizedPreviousRecoveryIdentity,
  });
  if (nextRecoveryIdentityChain) {
    nextRecoveryIdentities[normalizedDeviceId] = nextRecoveryIdentityChain;
  }

  const nextState = normalizeBridgeDeviceState({
    ...state,
    trustedPhones: {
      [normalizedDeviceId]: normalizedPublicKey,
    },
    trustedPhoneRecoveryIdentities: nextRecoveryIdentities,
  });
  if (persist) {
    writeBridgeDeviceState(nextState);
  }
  return nextState;
}

function getTrustedPhonePublicKey(state, phoneDeviceId) {
  const normalizedDeviceId = normalizeNonEmptyString(phoneDeviceId);
  if (!normalizedDeviceId) {
    return null;
  }
  return state.trustedPhones?.[normalizedDeviceId] || null;
}

function getTrustedPhoneRecoveryIdentity(state, phoneDeviceId) {
  return getTrustedPhoneRecoveryIdentities(state, phoneDeviceId)[0] || null;
}

function getTrustedPhoneRecoveryIdentities(state, phoneDeviceId) {
  const normalizedDeviceId = normalizeNonEmptyString(phoneDeviceId);
  if (!normalizedDeviceId) {
    return [];
  }
  const recoveryIdentityChain = normalizeRecoveryIdentityChain(
    state.trustedPhoneRecoveryIdentities?.[normalizedDeviceId]
  );
  if (!recoveryIdentityChain) {
    return [];
  }
  return [
    recoveryIdentityChain.current,
    recoveryIdentityChain.previous,
  ].filter(Boolean);
}

function hasTrustedPhones(state) {
  return Object.keys(state?.trustedPhones || {}).length > 0;
}

function createBridgeDeviceState() {
  const { publicKey, privateKey } = generateKeyPairSync("ed25519");
  const privateJwk = privateKey.export({ format: "jwk" });
  const publicJwk = publicKey.export({ format: "jwk" });

  return {
    version: 1,
    macDeviceId: randomUUID(),
    macIdentityPublicKey: base64UrlToBase64(publicJwk.x),
    macIdentityPrivateKey: base64UrlToBase64(privateJwk.d),
    trustedPhones: {},
    trustedPhoneRecoveryIdentities: {},
  };
}

function readKeychainStateRecord() {
  const rawState = readKeychainStateString();
  if (!rawState) {
    return { state: null, error: null };
  }

  try {
    return {
      state: normalizeBridgeDeviceState(JSON.parse(rawState)),
      error: null,
    };
  } catch (error) {
    return { state: null, error };
  }
}

function writeBridgeDeviceState(state, { backupExisting = true } = {}) {
  const serialized = JSON.stringify(state, null, 2);
  writeCanonicalFileStateString(serialized, { backupExisting });
  writeKeychainStateString(serialized);
}

function writeCanonicalFileStateString(serialized, { backupExisting = true } = {}) {
  const storeDir = resolveStoreDir();
  const storeFile = resolveStoreFile();
  if (backupExisting) {
    backupExistingCanonicalFile(storeFile, serialized);
  }
  fs.mkdirSync(storeDir, { recursive: true });
  fs.writeFileSync(storeFile, serialized, { mode: 0o600 });
  try {
    fs.chmodSync(storeFile, 0o600);
  } catch {
    // Best-effort only on filesystems that support POSIX modes.
  }
}

function resolveStoreDir() {
  return normalizeNonEmptyString(process.env.ANDRODEX_DEVICE_STATE_DIR) || DEFAULT_STORE_DIR;
}

function resolveStoreFile() {
  return normalizeNonEmptyString(process.env.ANDRODEX_DEVICE_STATE_FILE)
    || path.join(resolveStoreDir(), "device-state.json");
}

function resolveBackupFile() {
  return path.join(resolveStoreDir(), "device-state.backup.json");
}

function readKeychainStateString() {
  if (process.platform !== "darwin") {
    return null;
  }

  try {
    return execFileSync(
      "security",
      [
        "find-generic-password",
        "-s",
        KEYCHAIN_SERVICE,
        "-a",
        KEYCHAIN_ACCOUNT,
        "-w",
      ],
      {
        encoding: "utf8",
        stdio: ["ignore", "pipe", "ignore"],
        timeout: KEYCHAIN_COMMAND_TIMEOUT_MS,
      }
    ).trim();
  } catch {
    return null;
  }
}

function writeKeychainStateString(value) {
  if (process.platform !== "darwin") {
    return false;
  }

  try {
    execFileSync(
      "security",
      [
        "add-generic-password",
        "-U",
        "-s",
        KEYCHAIN_SERVICE,
        "-a",
        KEYCHAIN_ACCOUNT,
        "-w",
        value,
      ],
      {
        stdio: ["ignore", "ignore", "ignore"],
        timeout: KEYCHAIN_COMMAND_TIMEOUT_MS,
      }
    );
    return true;
  } catch {
    return false;
  }
}

function deleteCanonicalFileState() {
  const storeFile = resolveStoreFile();
  const existed = fs.existsSync(storeFile);
  try {
    fs.rmSync(storeFile, { force: true });
    return existed;
  } catch {
    return false;
  }
}

function deleteBackupFileState() {
  const backupFile = resolveBackupFile();
  const existed = fs.existsSync(backupFile);
  try {
    fs.rmSync(backupFile, { force: true });
    return existed;
  } catch {
    return false;
  }
}

function reconcileLegacyKeychainMirror(canonicalState, keychainRecord) {
  if (keychainRecord.error) {
    warnOnce("[androdex] Ignoring unreadable legacy Keychain pairing mirror; using canonical device-state.json.");
    return;
  }

  if (!keychainRecord.state) {
    writeKeychainStateString(JSON.stringify(canonicalState, null, 2));
    return;
  }

  if (JSON.stringify(canonicalState) === JSON.stringify(keychainRecord.state)) {
    return;
  }

  warnOnce("[androdex] Canonical bridge pairing state differs from the legacy Keychain mirror; using device-state.json.");
  writeKeychainStateString(JSON.stringify(canonicalState, null, 2));
}

function deleteKeychainStateString() {
  if (process.platform !== "darwin") {
    return false;
  }

  try {
    execFileSync(
      "security",
      [
        "delete-generic-password",
        "-s",
        KEYCHAIN_SERVICE,
        "-a",
        KEYCHAIN_ACCOUNT,
      ],
      {
        stdio: ["ignore", "ignore", "ignore"],
        timeout: KEYCHAIN_COMMAND_TIMEOUT_MS,
      }
    );
    return true;
  } catch {
    return false;
  }
}

function normalizeBridgeDeviceState(rawState) {
  const macDeviceId = normalizeNonEmptyString(rawState?.macDeviceId);
  const macIdentityPublicKey = normalizeNonEmptyString(rawState?.macIdentityPublicKey);
  const macIdentityPrivateKey = normalizeNonEmptyString(rawState?.macIdentityPrivateKey);

  if (!macDeviceId || !macIdentityPublicKey || !macIdentityPrivateKey) {
    throw new Error("Bridge device state is incomplete");
  }
  if (!allowsSyntheticDeviceState() && !isUuid(macDeviceId)) {
    throw new Error("Bridge device state uses a non-persistent synthetic macDeviceId.");
  }

  const trustedPhones = {};
  if (rawState?.trustedPhones && typeof rawState.trustedPhones === "object") {
    for (const [deviceId, publicKey] of Object.entries(rawState.trustedPhones)) {
      const normalizedDeviceId = normalizeNonEmptyString(deviceId);
      const normalizedPublicKey = normalizeNonEmptyString(publicKey);
      if (!normalizedDeviceId || !normalizedPublicKey) {
        continue;
      }
      if (!allowsSyntheticDeviceState() && !isUuid(normalizedDeviceId)) {
        throw new Error("Bridge device state uses a non-persistent synthetic trusted phone device id.");
      }
      trustedPhones[normalizedDeviceId] = normalizedPublicKey;
    }
  }

  const trustedPhoneRecoveryIdentities = {};
  if (rawState?.trustedPhoneRecoveryIdentities && typeof rawState.trustedPhoneRecoveryIdentities === "object") {
    for (const [deviceId, recoveryIdentity] of Object.entries(rawState.trustedPhoneRecoveryIdentities)) {
      const normalizedDeviceId = normalizeNonEmptyString(deviceId);
      const normalizedRecoveryIdentity = normalizeRecoveryIdentityChain(recoveryIdentity);
      if (!normalizedDeviceId || !normalizedRecoveryIdentity) {
        continue;
      }
      trustedPhoneRecoveryIdentities[normalizedDeviceId] = normalizedRecoveryIdentity;
    }
  }

  return {
    version: 1,
    macDeviceId,
    macIdentityPublicKey,
    macIdentityPrivateKey,
    trustedPhones,
    trustedPhoneRecoveryIdentities,
  };
}

function normalizeRecoveryIdentity(rawIdentity) {
  const recoveryIdentityPublicKey = normalizeNonEmptyString(rawIdentity?.recoveryIdentityPublicKey);
  const recoveryIdentityPrivateKey = normalizeNonEmptyString(rawIdentity?.recoveryIdentityPrivateKey);
  if (!recoveryIdentityPublicKey) {
    return null;
  }
  return recoveryIdentityPrivateKey
    ? {
      recoveryIdentityPublicKey,
      recoveryIdentityPrivateKey,
    }
    : {
      recoveryIdentityPublicKey,
    };
}

function normalizeRecoveryIdentityChain(rawIdentity) {
  const directIdentity = normalizeRecoveryIdentity(rawIdentity);
  if (directIdentity) {
    return {
      current: directIdentity,
      previous: null,
    };
  }

  const current = normalizeRecoveryIdentity(rawIdentity?.current);
  let previous = normalizeRecoveryIdentity(rawIdentity?.previous);
  if (!current && !previous) {
    return null;
  }
  if (!current) {
    return {
      current: previous,
      previous: null,
    };
  }
  if (sameRecoveryIdentity(current, previous)) {
    previous = null;
  }
  return {
    current,
    previous,
  };
}

function buildNextRecoveryIdentityChain({
  existingRecoveryIdentities,
  nextRecoveryIdentity,
  previousRecoveryIdentity,
}) {
  const [existingCurrentRecoveryIdentity, existingPreviousRecoveryIdentity] = existingRecoveryIdentities;
  if (!nextRecoveryIdentity) {
    if (!existingCurrentRecoveryIdentity) {
      return null;
    }
    return {
      current: existingCurrentRecoveryIdentity,
      previous: existingPreviousRecoveryIdentity || null,
    };
  }

  if (
    sameRecoveryIdentity(nextRecoveryIdentity, existingCurrentRecoveryIdentity)
    && !previousRecoveryIdentity
  ) {
    return existingCurrentRecoveryIdentity
      ? {
        current: existingCurrentRecoveryIdentity,
        previous: existingPreviousRecoveryIdentity || null,
      }
      : null;
  }

  const fallbackRecoveryIdentity = previousRecoveryIdentity
    || existingCurrentRecoveryIdentity
    || existingPreviousRecoveryIdentity
    || null;
  return {
    current: nextRecoveryIdentity,
    previous: sameRecoveryIdentity(nextRecoveryIdentity, fallbackRecoveryIdentity)
      ? null
      : fallbackRecoveryIdentity,
  };
}

function sameRecoveryIdentity(left, right) {
  return Boolean(
    left
    && right
    && left.recoveryIdentityPublicKey === right.recoveryIdentityPublicKey
  );
}

function corruptedStateError(source, cause) {
  const error = new Error(`Saved bridge identity state is corrupted in ${source}. Reset pairing to generate a fresh Androdex host identity.`);
  error.cause = cause;
  return error;
}

function warnOnce(message) {
  if (hasLoggedMismatch) {
    return;
  }
  hasLoggedMismatch = true;
  console.warn(message);
}

function normalizeNonEmptyString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

function allowsSyntheticDeviceState() {
  return normalizeNonEmptyString(process.env.ANDRODEX_ALLOW_SYNTHETIC_DEVICE_STATE) === "1";
}

function isUuid(value) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
    normalizeNonEmptyString(value)
  );
}

function readBackupFileStateRecord() {
  return readStateFileRecord(resolveBackupFile());
}

function readCanonicalFileStateRecord() {
  return readStateFileRecord(resolveStoreFile());
}

function readStateFileRecord(storeFile) {
  if (!fs.existsSync(storeFile)) {
    return { state: null, error: null };
  }

  try {
    return {
      state: normalizeBridgeDeviceState(JSON.parse(fs.readFileSync(storeFile, "utf8"))),
      error: null,
    };
  } catch (error) {
    return { state: null, error };
  }
}

function backupExistingCanonicalFile(storeFile, nextSerializedState) {
  if (!fs.existsSync(storeFile)) {
    return;
  }

  let currentSerializedState = "";
  try {
    currentSerializedState = fs.readFileSync(storeFile, "utf8");
  } catch {
    return;
  }
  if (!currentSerializedState || currentSerializedState === nextSerializedState) {
    return;
  }

  const backupFile = resolveBackupFile();
  fs.mkdirSync(path.dirname(backupFile), { recursive: true });
  fs.writeFileSync(backupFile, currentSerializedState, { mode: 0o600 });
  try {
    fs.chmodSync(backupFile, 0o600);
  } catch {
    // Best-effort only on filesystems that support POSIX modes.
  }
}

function base64UrlToBase64(value) {
  return String(value).replace(/-/g, "+").replace(/_/g, "/");
}

module.exports = {
  hasTrustedPhones,
  getTrustedPhonePublicKey,
  getTrustedPhoneRecoveryIdentity,
  getTrustedPhoneRecoveryIdentities,
  loadOrCreateBridgeDeviceState,
  rememberTrustedPhone,
  resetBridgeDeviceState,
  resolveBridgeRelaySession,
  stableRelayHostIdForMacDeviceId,
};
