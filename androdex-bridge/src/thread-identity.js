// FILE: thread-identity.js
// Purpose: Owns the canonical Androdex thread identity format and bridge-side thread-id rewriting between Android and host runtimes.
// Layer: Bridge helper
// Exports: canonical thread-id helpers and message rewriters

const CANONICAL_THREAD_ID_PREFIX = "androdex-thread";
const THREAD_ID_FIELD_NAMES = new Set([
  "threadId",
  "thread_id",
  "conversationId",
  "conversation_id",
  "forkedFromThreadId",
  "forked_from_thread_id",
  "parentThreadId",
  "parent_thread_id",
  "receiverThreadId",
  "receiver_thread_id",
  "newThreadId",
  "new_thread_id",
]);
const THREAD_ID_LIST_FIELD_NAMES = new Set([
  "receiverThreadIds",
  "receiver_thread_ids",
  "threadIds",
  "thread_ids",
]);
const THREAD_OBJECT_MARKER_KEYS = new Set([
  "title",
  "preview",
  "cwd",
  "turns",
  "latestTurn",
  "messages",
  "worktreePath",
  "runtimeMode",
  "interactionMode",
  "forkedFromThreadId",
  "forked_from_thread_id",
  "parentThreadId",
  "parent_thread_id",
]);

function createCanonicalThreadId(runtimeTarget, backendThreadId) {
  const normalizedRuntimeTarget = normalizeNonEmptyString(runtimeTarget);
  const normalizedBackendThreadId = normalizeNonEmptyString(backendThreadId);
  if (!normalizedRuntimeTarget || !normalizedBackendThreadId) {
    return "";
  }

  const existing = decodeCanonicalThreadId(normalizedBackendThreadId);
  if (existing && existing.runtimeTarget === normalizedRuntimeTarget) {
    return normalizedBackendThreadId;
  }

  return `${CANONICAL_THREAD_ID_PREFIX}:${encodeURIComponent(normalizedRuntimeTarget)}:${encodeURIComponent(normalizedBackendThreadId)}`;
}

function decodeCanonicalThreadId(value) {
  const normalizedValue = normalizeNonEmptyString(value);
  if (!normalizedValue.startsWith(`${CANONICAL_THREAD_ID_PREFIX}:`)) {
    return null;
  }

  const remainder = normalizedValue.slice(CANONICAL_THREAD_ID_PREFIX.length + 1);
  const separatorIndex = remainder.indexOf(":");
  if (separatorIndex <= 0 || separatorIndex >= remainder.length - 1) {
    return null;
  }

  const runtimeTarget = decodeURIComponentSafe(remainder.slice(0, separatorIndex));
  const backendThreadId = decodeURIComponentSafe(remainder.slice(separatorIndex + 1));
  if (!runtimeTarget || !backendThreadId) {
    return null;
  }

  return {
    runtimeTarget,
    backendThreadId,
  };
}

function rewriteBridgeMessageThreadIdsForAndroid(rawMessage, runtimeTarget) {
  return rewriteBridgeMessageThreadIds(rawMessage, {
    runtimeTarget,
    direction: "toAndroid",
  });
}

function rewriteBridgeMessageThreadIdsForRuntime(rawMessage, runtimeTarget) {
  return rewriteBridgeMessageThreadIds(rawMessage, {
    runtimeTarget,
    direction: "toRuntime",
  });
}

function rewriteBridgeMessageThreadIds(rawMessage, {
  runtimeTarget = "",
  direction = "toAndroid",
} = {}) {
  const normalizedRuntimeTarget = normalizeNonEmptyString(runtimeTarget);
  if (!normalizedRuntimeTarget || typeof rawMessage !== "string" || !rawMessage.trim()) {
    return rawMessage;
  }

  let parsed;
  try {
    parsed = JSON.parse(rawMessage);
  } catch {
    return rawMessage;
  }

  const rewritten = rewriteValue(parsed, {
    runtimeTarget: normalizedRuntimeTarget,
    direction,
    parentKey: "",
  });
  const patched = patchThreadStartedId(rewritten, {
    runtimeTarget: normalizedRuntimeTarget,
    direction,
  });

  return JSON.stringify(patched);
}

function rewriteValue(value, context) {
  if (Array.isArray(value)) {
    if (THREAD_ID_LIST_FIELD_NAMES.has(context.parentKey)) {
      return value.map((entry) => rewriteThreadIdScalar(entry, context));
    }
    return value.map((entry) => rewriteValue(entry, {
      ...context,
      parentKey: context.parentKey,
    }));
  }

  if (!value || typeof value !== "object") {
    if (typeof value === "string" && THREAD_ID_FIELD_NAMES.has(context.parentKey)) {
      return rewriteThreadIdScalar(value, context);
    }
    return value;
  }

  const nextObject = {};
  for (const [key, child] of Object.entries(value)) {
    nextObject[key] = rewriteValue(child, {
      ...context,
      parentKey: key,
    });
  }

  if (typeof nextObject.id === "string" && shouldTreatObjectIdAsThreadId(nextObject, context.parentKey)) {
    nextObject.id = rewriteThreadIdScalar(nextObject.id, context);
  }

  return nextObject;
}

function patchThreadStartedId(parsed, {
  runtimeTarget,
  direction,
} = {}) {
  if (!parsed || typeof parsed !== "object") {
    return parsed;
  }

  const method = normalizeNonEmptyString(parsed.method);
  if (method !== "thread/started" && method !== "thread/start") {
    return parsed;
  }

  const params = parsed.params && typeof parsed.params === "object" && !Array.isArray(parsed.params)
    ? { ...parsed.params }
    : null;
  if (!params || typeof params.id !== "string") {
    return parsed;
  }

  return {
    ...parsed,
    params: {
      ...params,
      id: rewriteThreadIdScalar(params.id, {
        runtimeTarget,
        direction,
        parentKey: "id",
      }),
    },
  };
}

function rewriteThreadIdScalar(value, {
  runtimeTarget,
  direction,
} = {}) {
  const normalizedValue = normalizeNonEmptyString(value);
  if (!normalizedValue) {
    return value;
  }

  if (direction === "toRuntime") {
    const decoded = decodeCanonicalThreadId(normalizedValue);
    if (!decoded) {
      return normalizedValue;
    }
    if (decoded.runtimeTarget !== runtimeTarget) {
      throw new Error(
        `Thread ${normalizedValue} belongs to runtime target ${decoded.runtimeTarget}, not ${runtimeTarget}. Reopen the thread from the active runtime and retry.`
      );
    }
    return decoded.backendThreadId;
  }

  return createCanonicalThreadId(runtimeTarget, normalizedValue) || normalizedValue;
}

function shouldTreatObjectIdAsThreadId(value, parentKey) {
  if (parentKey === "thread") {
    return true;
  }

  return [...THREAD_OBJECT_MARKER_KEYS].some((key) => Object.prototype.hasOwnProperty.call(value, key));
}

function decodeURIComponentSafe(value) {
  try {
    return decodeURIComponent(value);
  } catch {
    return "";
  }
}

function normalizeNonEmptyString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

module.exports = {
  createCanonicalThreadId,
  decodeCanonicalThreadId,
  rewriteBridgeMessageThreadIdsForAndroid,
  rewriteBridgeMessageThreadIdsForRuntime,
};
