// FILE: account-status.js
// Purpose: Converts raw Codex account/auth responses into a sanitized host-account snapshot for Android.
// Layer: CLI helper
// Exports: composeAccountStatus, composeSanitizedAuthStatusFromSettledResults, redactAuthStatus
// Depends on: ../package.json

const { version: bridgePackageVersion = "" } = require("../package.json");

function composeAccountStatus({
  accountRead = null,
  authStatus = null,
  loginInFlight = false,
  bridgeVersionInfo = null,
} = {}) {
  const account = accountRead?.account || null;
  const authToken = normalizeString(authStatus?.authToken);
  const hasAccountLogin = hasExplicitAccountLogin(account);
  const authMethod = firstNonEmpty([
    normalizeString(authStatus?.authMethod),
    normalizeString(account?.type),
  ]) || null;
  const tokenReady = Boolean(authToken);
  const requiresOpenaiAuth = Boolean(accountRead?.requiresOpenaiAuth || authStatus?.requiresOpenaiAuth);
  const hasPriorLoginContext = hasAccountLogin || Boolean(authMethod);
  const needsReauth = !loginInFlight && requiresOpenaiAuth && hasPriorLoginContext;
  const isAuthenticated = !needsReauth && (tokenReady || hasAccountLogin);
  const status = isAuthenticated
    ? "authenticated"
    : (loginInFlight ? "pending_login" : (needsReauth ? "expired" : "not_logged_in"));

  return {
    status,
    authMethod,
    email: normalizeString(account?.email) || null,
    planType: normalizeString(account?.planType) || null,
    loginInFlight: Boolean(loginInFlight),
    needsReauth,
    tokenReady,
    expiresAt: null,
    requiresOpenaiAuth,
    bridgeVersion: firstNonEmpty([
      normalizeString(bridgeVersionInfo?.bridgeVersion),
      normalizeString(bridgePackageVersion),
    ]) || null,
    bridgeLatestVersion: normalizeString(bridgeVersionInfo?.bridgeLatestVersion) || null,
    rateLimits: extractRateLimits(accountRead),
  };
}

function redactAuthStatus(authStatus = null, extras = {}) {
  const composed = composeAccountStatus({
    accountRead: extras.accountRead || null,
    authStatus,
    loginInFlight: Boolean(extras.loginInFlight),
    bridgeVersionInfo: extras.bridgeVersionInfo || null,
  });

  return {
    authMethod: composed.authMethod,
    status: composed.status,
    email: composed.email,
    planType: composed.planType,
    loginInFlight: composed.loginInFlight,
    needsReauth: composed.needsReauth,
    tokenReady: composed.tokenReady,
    expiresAt: composed.expiresAt,
    bridgeVersion: composed.bridgeVersion,
    bridgeLatestVersion: composed.bridgeLatestVersion,
    rateLimits: composed.rateLimits,
  };
}

function composeSanitizedAuthStatusFromSettledResults({
  accountReadResult = null,
  authStatusResult = null,
  loginInFlight = false,
  bridgeVersionInfo = null,
} = {}) {
  const accountRead = accountReadResult?.status === "fulfilled" ? accountReadResult.value : null;
  const authStatus = authStatusResult?.status === "fulfilled" ? authStatusResult.value : null;

  if (!accountRead && !authStatus) {
    const error = new Error("Unable to read host account status from the bridge.");
    error.errorCode = "auth_status_unavailable";
    throw error;
  }

  return redactAuthStatus(authStatus, {
    accountRead,
    loginInFlight: Boolean(loginInFlight),
    bridgeVersionInfo,
  });
}

function hasExplicitAccountLogin(account) {
  if (!account || typeof account !== "object") {
    return false;
  }

  if (parseBoolean(account.loggedIn) || parseBoolean(account.logged_in) || parseBoolean(account.isLoggedIn)) {
    return true;
  }

  return Boolean(normalizeString(account.email));
}

function firstNonEmpty(values) {
  for (const value of values) {
    const normalized = normalizeString(value);
    if (normalized) {
      return normalized;
    }
  }
  return "";
}

function normalizeString(value) {
  return typeof value === "string" ? value.trim() : "";
}

function parseBoolean(value) {
  return value === true;
}

function extractRateLimits(accountRead = null) {
  const candidates = [
    accountRead?.rateLimits,
    accountRead?.rate_limits,
    accountRead?.limits,
    accountRead?.buckets,
    accountRead?.usage,
    accountRead?.usage?.rateLimits,
    accountRead?.usage?.rate_limits,
    accountRead?.usage?.limits,
    accountRead?.usage?.buckets,
    accountRead?.account?.rateLimits,
    accountRead?.account?.rate_limits,
    accountRead?.account?.limits,
    accountRead?.account?.buckets,
    accountRead?.account?.usage,
    accountRead?.account?.usage?.rateLimits,
    accountRead?.account?.usage?.rate_limits,
    accountRead?.account?.usage?.limits,
    accountRead?.account?.usage?.buckets,
  ];

  for (const candidate of candidates) {
    const decoded = decodeRateLimitCollection(candidate);
    if (decoded.length > 0) {
      return decoded;
    }
  }

  return [];
}

function decodeRateLimitCollection(value) {
  if (Array.isArray(value)) {
    return value
      .map((item, index) => normalizeRateLimitBucket(item, `limit-${index + 1}`))
      .filter(Boolean);
  }

  if (!isPlainObject(value)) {
    return [];
  }

  for (const key of ["items", "buckets", "limits", "rateLimits", "rate_limits"]) {
    const decoded = decodeRateLimitCollection(value[key]);
    if (decoded.length > 0) {
      return decoded;
    }
  }

  return Object.entries(value)
    .map(([key, item], index) => normalizeRateLimitBucket(item, normalizeString(key) || `limit-${index + 1}`))
    .filter(Boolean);
}

function normalizeRateLimitBucket(value, fallbackName) {
  if (!isPlainObject(value)) {
    return null;
  }

  const remaining = readIntegerField(value, ["remaining", "remaining_tokens", "remainingRequests"]);
  const limit = readIntegerField(value, ["limit", "max", "quota"]);
  const used = readIntegerField(value, ["used", "consumed", "used_tokens"])
    ?? (remaining != null && limit != null ? Math.max(0, limit - remaining) : null);
  const resetsAt = readField(value, ["resetsAt", "resets_at", "resetAt", "reset_at"]);
  const name = firstNonEmpty([
    normalizeString(value.name),
    normalizeString(value.id),
    normalizeString(value.bucket),
    normalizeString(value.scope),
    normalizeString(value.label),
    normalizeString(value.model),
    normalizeString(value.title),
    normalizeString(fallbackName),
  ]) || null;

  if (!name && remaining == null && limit == null && used == null && resetsAt == null) {
    return null;
  }

  return {
    name: name || fallbackName || "limit",
    remaining,
    limit,
    used,
    resetsAt,
  };
}

function readIntegerField(value, names) {
  const rawValue = readField(value, names);
  if (rawValue == null) {
    return null;
  }

  if (typeof rawValue === "number" && Number.isFinite(rawValue)) {
    return Math.trunc(rawValue);
  }

  if (typeof rawValue === "string" && rawValue.trim()) {
    const parsed = Number(rawValue.trim());
    if (Number.isFinite(parsed)) {
      return Math.trunc(parsed);
    }
  }

  return null;
}

function readField(value, names) {
  for (const name of names) {
    const candidate = value?.[name];
    if (candidate !== undefined && candidate !== null) {
      return candidate;
    }
  }
  return null;
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

module.exports = {
  composeAccountStatus,
  composeSanitizedAuthStatusFromSettledResults,
  redactAuthStatus,
};
