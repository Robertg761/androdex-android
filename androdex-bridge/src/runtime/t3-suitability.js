// FILE: runtime/t3-suitability.js
// Purpose: Extracts and validates the minimum host-local T3 attach requirements for the read-only milestone.
// Layer: CLI helper
// Exports: T3 endpoint/config suitability helpers
// Depends on: path

const path = require("path");

const T3_REQUIRED_RPC_METHODS = Object.freeze([
  "server.getConfig",
  "orchestration.getSnapshot",
  "orchestration.replayEvents",
]);

const T3_REQUIRED_SUBSCRIPTIONS = Object.freeze([
  "subscribeOrchestrationDomainEvents",
]);

function createT3AttachRequirements({ endpoint = "", env = process.env } = {}) {
  return {
    endpoint: normalizeNonEmptyString(endpoint),
    expectedAuthMode: normalizeNonEmptyString(env?.ANDRODEX_T3_AUTH_MODE),
    expectedBaseDir: normalizePathString(env?.ANDRODEX_T3_BASE_DIR),
    expectedProtocolVersion: normalizeNonEmptyString(env?.ANDRODEX_T3_PROTOCOL_VERSION),
    requiredRpcMethods: [...T3_REQUIRED_RPC_METHODS],
    requiredSubscriptions: [...T3_REQUIRED_SUBSCRIPTIONS],
  };
}

function ensureLoopbackEndpoint(endpoint) {
  const normalizedEndpoint = normalizeNonEmptyString(endpoint);
  if (!normalizedEndpoint) {
    throw new Error("A host-local T3 endpoint is required before attach can begin.");
  }

  let parsed;
  try {
    parsed = new URL(normalizedEndpoint);
  } catch {
    throw new Error(`Invalid T3 endpoint URL: ${normalizedEndpoint}`);
  }

  const protocol = normalizeNonEmptyString(parsed.protocol).toLowerCase();
  if (protocol !== "ws:" && protocol !== "wss:") {
    throw new Error(`Unsupported T3 endpoint protocol "${protocol || "<unknown>"}". Use ws:// or wss://.`);
  }

  const hostname = normalizeNonEmptyString(parsed.hostname).toLowerCase();
  if (!isLoopbackHostname(hostname)) {
    throw new Error(
      `Refusing to attach to non-local T3 endpoint "${hostname || "<unknown>"}". Host-local loopback access is required.`
    );
  }

  return parsed;
}

function extractT3RuntimeMetadata(config) {
  const runtimeConfig = config && typeof config === "object" ? config : {};
  const declaredRpcMethods = extractDeclaredMethods(runtimeConfig, [
    ["rpcMethods"],
    ["methods"],
    ["capabilities", "rpcMethods"],
    ["capabilities", "methods"],
    ["server", "rpcMethods"],
    ["server", "methods"],
  ]);
  const declaredSubscriptions = extractDeclaredMethods(runtimeConfig, [
    ["subscriptions"],
    ["capabilities", "subscriptions"],
    ["server", "subscriptions"],
  ]);

  return {
    runtimeProtocolVersion: firstString(runtimeConfig, [
      ["protocolVersion"],
      ["protocol_version"],
      ["versions", "protocol"],
      ["server", "protocolVersion"],
      ["server", "protocol_version"],
      ["server", "version"],
      ["version"],
    ]),
    runtimeAuthMode: firstString(runtimeConfig, [
      ["authMode"],
      ["auth_mode"],
      ["auth", "mode"],
      ["security", "authMode"],
      ["security", "auth_mode"],
      ["server", "authMode"],
      ["server", "auth_mode"],
    ]),
    runtimeStateRoot: extractRuntimeStateRoot(runtimeConfig),
    declaredRpcMethods,
    declaredSubscriptions,
  };
}

function validateT3AttachConfig({
  endpoint = "",
  config = null,
  requirements = createT3AttachRequirements({ endpoint }),
} = {}) {
  ensureLoopbackEndpoint(endpoint);
  const runtimeMetadata = extractT3RuntimeMetadata(config);

  if (!runtimeMetadata.runtimeStateRoot) {
    throw new Error("The T3 server did not report a state-root/baseDir identity required for replay scoping.");
  }

  if (!runtimeMetadata.runtimeAuthMode && requirements.expectedAuthMode) {
    throw new Error("The T3 server did not report an auth mode, so attach suitability could not be verified.");
  }

  if (!runtimeMetadata.runtimeProtocolVersion && requirements.expectedProtocolVersion) {
    throw new Error("The T3 server did not report a protocol version, so attach suitability could not be verified.");
  }

  if (requirements.expectedBaseDir
    && normalizePathString(runtimeMetadata.runtimeStateRoot) !== requirements.expectedBaseDir) {
    throw new Error(
      `The T3 server state root "${runtimeMetadata.runtimeStateRoot}" does not match the expected baseDir "${requirements.expectedBaseDir}".`
    );
  }

  if (requirements.expectedAuthMode
    && normalizeNonEmptyString(runtimeMetadata.runtimeAuthMode) !== requirements.expectedAuthMode) {
    throw new Error(
      `The T3 server auth mode "${runtimeMetadata.runtimeAuthMode}" does not match the required auth mode "${requirements.expectedAuthMode}".`
    );
  }

  if (requirements.expectedProtocolVersion
    && normalizeNonEmptyString(runtimeMetadata.runtimeProtocolVersion) !== requirements.expectedProtocolVersion) {
    throw new Error(
      `The T3 server protocol version "${runtimeMetadata.runtimeProtocolVersion}" does not match the required version "${requirements.expectedProtocolVersion}".`
    );
  }

  if (runtimeMetadata.declaredRpcMethods.length > 0) {
    ensureRequiredMethodsPresent({
      declaredValues: runtimeMetadata.declaredRpcMethods,
      requiredValues: requirements.requiredRpcMethods,
      noun: "RPC methods",
    });
  }
  if (runtimeMetadata.declaredSubscriptions.length > 0) {
    ensureRequiredMethodsPresent({
      declaredValues: runtimeMetadata.declaredSubscriptions,
      requiredValues: requirements.requiredSubscriptions,
      noun: "subscriptions",
    });
  }

  return runtimeMetadata;
}

function ensureRequiredMethodsPresent({
  declaredValues,
  requiredValues,
  noun,
}) {
  if (!Array.isArray(declaredValues) || declaredValues.length === 0) {
    throw new Error(`The T3 server did not advertise the ${noun} required for the Androdex adapter.`);
  }

  const normalizedDeclared = new Set(declaredValues.map((value) => normalizeNonEmptyString(value)));
  const missingValues = requiredValues.filter((value) => !normalizedDeclared.has(normalizeNonEmptyString(value)));
  if (missingValues.length > 0) {
    throw new Error(`The T3 server is missing required ${noun}: ${missingValues.join(", ")}.`);
  }
}

function extractDeclaredMethods(root, pathCandidates) {
  for (const candidate of pathCandidates) {
    const value = readNestedValue(root, candidate);
    const normalized = normalizeStringArray(value);
    if (normalized.length > 0) {
      return normalized;
    }
  }
  return [];
}

function firstString(root, pathCandidates) {
  for (const candidate of pathCandidates) {
    const value = readNestedValue(root, candidate);
    const normalized = normalizeNonEmptyString(value);
    if (normalized) {
      return normalized;
    }
  }
  return "";
}

function readNestedValue(root, pathSegments) {
  let current = root;
  for (const segment of pathSegments) {
    if (!current || typeof current !== "object" || Array.isArray(current)) {
      return null;
    }
    current = current[segment];
  }
  return current;
}

function normalizeStringArray(value) {
  if (!Array.isArray(value)) {
    return [];
  }

  return value
    .map((item) => {
      if (typeof item === "string") {
        return item;
      }
      if (item && typeof item === "object") {
        return item.name || item.method || item.id || item.type || "";
      }
      return "";
    })
    .map((item) => normalizeNonEmptyString(item))
    .filter(Boolean);
}

function extractRuntimeStateRoot(runtimeConfig) {
  const directStateRoot = normalizePathString(firstString(runtimeConfig, [
    ["baseDir"],
    ["base_dir"],
    ["stateRoot"],
    ["state_root"],
    ["storage", "baseDir"],
    ["storage", "base_dir"],
    ["paths", "baseDir"],
    ["paths", "base_dir"],
    ["server", "baseDir"],
    ["server", "base_dir"],
  ]));
  if (directStateRoot) {
    return directStateRoot;
  }

  const keybindingsConfigPath = normalizePathString(firstString(runtimeConfig, [
    ["keybindingsConfigPath"],
  ]));
  if (keybindingsConfigPath) {
    return normalizePathString(path.dirname(path.dirname(keybindingsConfigPath)));
  }

  const logsDirectoryPath = normalizePathString(firstString(runtimeConfig, [
    ["observability", "logsDirectoryPath"],
  ]));
  if (logsDirectoryPath) {
    return normalizePathString(path.dirname(path.dirname(logsDirectoryPath)));
  }

  return "";
}

function normalizePathString(value) {
  const trimmed = normalizeNonEmptyString(value);
  return trimmed ? path.normalize(trimmed) : "";
}

function normalizeNonEmptyString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

function isLoopbackHostname(hostname) {
  return hostname === "127.0.0.1"
    || hostname === "localhost"
    || hostname === "::1"
    || hostname === "[::1]";
}

module.exports = {
  createT3AttachRequirements,
  ensureLoopbackEndpoint,
  extractT3RuntimeMetadata,
  validateT3AttachConfig,
};
