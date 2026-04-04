// FILE: notifications/tracker.js
// Purpose: Tracks per-turn titles and previews so the bridge can emit completion pushes after the phone disconnects.
// Layer: Bridge helper
// Exports: createPushNotificationTracker
// Depends on: ./completion-dedupe

const {
  createPushNotificationCompletionDedupe,
} = require("./completion-dedupe");

const DEFAULT_PREVIEW_MAX_CHARS = 160;

function createPushNotificationTracker({
  sessionId,
  pushServiceClient,
  previewMaxChars = DEFAULT_PREVIEW_MAX_CHARS,
  logPrefix = "[androdex]",
  now = () => Date.now(),
} = {}) {
  const threadTitleById = new Map();
  const threadIdByTurnId = new Map();
  const turnStateByKey = new Map();
  const completionDedupe = createPushNotificationCompletionDedupe({ now });

  function handleOutbound(rawMessage) {
    const message = parseOutboundMessage(rawMessage);
    if (!message) {
      return;
    }

    rememberMessageContext(message);
    clearFallbackSuppressionForNewRun(message);

    if (isAssistantDeltaMethod(message.method)) {
      recordAssistantDelta(message.threadId, message.turnId, message.params, message.eventObject);
      return;
    }

    if (isAssistantCompletedMethod(message.method, message.params, message.eventObject)) {
      recordAssistantCompletion(message.threadId, message.turnId, message.params, message.eventObject);
      return;
    }

    routeTerminalMessage(message);
  }

  function routeTerminalMessage({ method, params, eventObject, threadId, turnId }) {
    if (method === "turn/failed" || isFailureEnvelope(method, eventObject)) {
      if (shouldIgnoreRetriableFailure(params, eventObject)) {
        return;
      }

      recordFailure(threadId, turnId, params, eventObject);
      void notifyCompletion(threadId, turnId, params, eventObject, { forcedResult: "failed" });
      return;
    }

    if (method === "error") {
      recordFailure(threadId, turnId, params, eventObject);
      void notifyCompletion(threadId, turnId, params, eventObject, { forcedResult: "failed" });
      return;
    }

    if (isTerminalThreadStatusMethod(method)) {
      void notifyCompletion(threadId, turnId, params, eventObject, {
        forcedResult: resolveThreadStatusResult(params, eventObject),
      });
      return;
    }

    if (method === "turn/completed") {
      void notifyCompletion(threadId, turnId, params, eventObject);
    }
  }

  function rememberMessageContext({ threadId, turnId, params, eventObject }) {
    if (threadId && turnId) {
      threadIdByTurnId.set(turnId, threadId);
      ensureTurnState(threadId, turnId);
    }

    if (!threadId) {
      return;
    }

    const nextTitle = extractThreadTitle(params, eventObject);
    if (nextTitle) {
      threadTitleById.set(threadId, nextTitle);
    }
  }

  function clearFallbackSuppressionForNewRun({ method, threadId, params, eventObject }) {
    if (!threadId) {
      return;
    }

    if (method === "turn/started" || isActiveThreadStatus(method, params, eventObject)) {
      completionDedupe.clearForNewRun(threadId);
    }
  }

  async function notifyCompletion(threadId, turnId, params, eventObject, { forcedResult = null } = {}) {
    const resolvedThreadId = threadId || (turnId ? threadIdByTurnId.get(turnId) : null);
    if (!pushServiceClient?.hasConfiguredBaseUrl || !resolvedThreadId) {
      return;
    }

    const result = forcedResult || resolveCompletionResult(params, eventObject);
    if (!result) {
      cleanupTurnState(resolvedThreadId, turnId);
      return;
    }

    if (completionDedupe.shouldSuppressThreadStatusFallback({
      threadId: resolvedThreadId,
      turnId,
      result,
    })) {
      cleanupTurnState(resolvedThreadId, turnId);
      return;
    }

    const dedupeKey = completionDedupeKey({
      sessionId,
      threadId: resolvedThreadId,
      turnId,
      result,
      now,
    });
    if (completionDedupe.hasActiveDedupeKey(dedupeKey)) {
      cleanupTurnState(resolvedThreadId, turnId);
      return;
    }

    const state = getTurnState(resolvedThreadId, turnId);
    const title = normalizePreviewText(threadTitleById.get(resolvedThreadId)) || "New Thread";
    const body = buildNotificationBody({
      result,
      state,
      params,
      eventObject,
      previewMaxChars,
    });

    try {
      completionDedupe.beginNotification({
        dedupeKey,
        threadId: resolvedThreadId,
        turnId,
        result,
      });
      await pushServiceClient.notifyCompletion({
        threadId: resolvedThreadId,
        turnId,
        result,
        title,
        body,
        dedupeKey,
      });
      completionDedupe.commitNotification({
        dedupeKey,
        threadId: resolvedThreadId,
        turnId,
        result,
      });
    } catch (error) {
      completionDedupe.abortNotification({
        dedupeKey,
        threadId: resolvedThreadId,
        turnId,
        result,
      });
      console.error(`${logPrefix} push notify failed: ${error.message}`);
    } finally {
      cleanupTurnState(resolvedThreadId, turnId);
    }
  }

  function recordAssistantDelta(threadId, turnId, params, eventObject) {
    const resolvedTurnId = turnId || resolveTurnId(params, eventObject);
    const resolvedThreadId = threadId || (resolvedTurnId ? threadIdByTurnId.get(resolvedTurnId) : null);
    if (!resolvedThreadId || !resolvedTurnId) {
      return;
    }

    const delta = extractAssistantDeltaText(params, eventObject);
    if (!delta) {
      return;
    }

    const state = ensureTurnState(resolvedThreadId, resolvedTurnId);
    state.latestAssistantPreview = truncatePreview(`${state.latestAssistantPreview || ""}${delta}`, previewMaxChars);
  }

  function recordAssistantCompletion(threadId, turnId, params, eventObject) {
    const resolvedTurnId = turnId || resolveTurnId(params, eventObject);
    const resolvedThreadId = threadId || (resolvedTurnId ? threadIdByTurnId.get(resolvedTurnId) : null);
    if (!resolvedThreadId || !resolvedTurnId) {
      return;
    }

    const completedText = extractAssistantCompletedText(params, eventObject);
    if (!completedText) {
      return;
    }

    const state = ensureTurnState(resolvedThreadId, resolvedTurnId);
    state.latestAssistantPreview = truncatePreview(completedText, previewMaxChars);
  }

  function recordFailure(threadId, turnId, params, eventObject) {
    const resolvedTurnId = turnId || resolveTurnId(params, eventObject);
    const resolvedThreadId = threadId || (resolvedTurnId ? threadIdByTurnId.get(resolvedTurnId) : null);
    if (!resolvedThreadId || !resolvedTurnId) {
      return;
    }

    const failureMessage = extractFailureMessage(params, eventObject);
    const state = ensureTurnState(resolvedThreadId, resolvedTurnId);
    if (failureMessage) {
      state.latestFailurePreview = truncatePreview(failureMessage, previewMaxChars);
    }
  }

  function ensureTurnState(threadId, turnId) {
    const key = turnStateKey(threadId, turnId);
    if (!turnStateByKey.has(key)) {
      turnStateByKey.set(key, {
        latestAssistantPreview: "",
        latestFailurePreview: "",
      });
    }

    return turnStateByKey.get(key);
  }

  function getTurnState(threadId, turnId) {
    if (!threadId) {
      return null;
    }
    return turnStateByKey.get(turnStateKey(threadId, turnId)) || null;
  }

  function cleanupTurnState(threadId, turnId) {
    if (!threadId) {
      return;
    }

    const resolvedTurnId = turnId || null;
    if (resolvedTurnId) {
      threadIdByTurnId.delete(resolvedTurnId);
    }
    turnStateByKey.delete(turnStateKey(threadId, resolvedTurnId));
  }

  return {
    handleOutbound,
  };
}

function parseOutboundMessage(rawMessage) {
  const parsed = safeParseJSON(rawMessage);
  if (!parsed || typeof parsed.method !== "string") {
    return null;
  }

  const method = parsed.method.trim();
  const params = objectValue(parsed.params) || {};
  const eventObject = envelopeEventObject(params);

  return {
    method,
    params,
    eventObject,
    threadId: resolveThreadId(params, eventObject),
    turnId: resolveTurnId(params, eventObject),
  };
}

function envelopeEventObject(params) {
  if (params?.event && typeof params.event === "object") {
    return params.event;
  }
  if (params?.msg && typeof params.msg === "object") {
    return params.msg;
  }
  return null;
}

function resolveThreadId(params, eventObject) {
  return firstNonEmptyString([
    params?.threadId,
    params?.thread_id,
    params?.conversationId,
    params?.conversation_id,
    params?.thread?.id,
    params?.thread?.threadId,
    params?.turn?.threadId,
    eventObject?.threadId,
    eventObject?.thread_id,
    eventObject?.conversationId,
  ]) || null;
}

function resolveTurnId(params, eventObject) {
  return firstNonEmptyString([
    params?.turnId,
    params?.turn_id,
    params?.turn?.id,
    params?.turn?.turnId,
    eventObject?.turnId,
    eventObject?.turn_id,
  ]) || null;
}

function extractThreadTitle(params, eventObject) {
  return firstNonEmptyString([
    params?.thread?.title,
    params?.title,
    eventObject?.thread?.title,
    eventObject?.title,
  ]);
}

function isAssistantDeltaMethod(method) {
  return method === "item/agentMessage/delta"
    || method === "codex/event/agent_message_delta"
    || method === "codex/event/agent_message_content_delta";
}

function isAssistantCompletedMethod(method, params, eventObject) {
  if (method === "codex/event/agent_message") {
    return true;
  }
  if (method !== "item/completed" && method !== "codex/event/item_completed") {
    return false;
  }
  const item = params?.item || eventObject?.item || eventObject;
  const type = readString(item?.type).toLowerCase();
  const role = readString(item?.role).toLowerCase();
  return type === "agent_message" || role === "assistant";
}

function isFailureEnvelope(method, eventObject) {
  const eventType = readString(eventObject?.type).toLowerCase();
  return method === "codex/event/error" || eventType === "error";
}

function shouldIgnoreRetriableFailure(params, eventObject) {
  const message = extractFailureMessage(params, eventObject).toLowerCase();
  return message.includes("interrupted by another request")
    || message.includes("cancelled")
    || message.includes("canceled");
}

function isTerminalThreadStatusMethod(method) {
  return method === "thread/status/changed";
}

function isActiveThreadStatus(method, params, eventObject) {
  if (!isTerminalThreadStatusMethod(method)) {
    return false;
  }
  const status = firstNonEmptyString([params?.status, eventObject?.status]).toLowerCase();
  return status === "running" || status === "in_progress";
}

function resolveThreadStatusResult(params, eventObject) {
  const status = firstNonEmptyString([params?.status, eventObject?.status]).toLowerCase();
  if (status === "completed" || status === "done" || status === "success") {
    return "completed";
  }
  if (status === "failed" || status === "error") {
    return "failed";
  }
  return null;
}

function resolveCompletionResult(params, eventObject) {
  const status = firstNonEmptyString([
    params?.turn?.status,
    params?.status,
    eventObject?.turn?.status,
    eventObject?.status,
  ]).toLowerCase();
  if (status === "failed" || status === "error") {
    return "failed";
  }
  return "completed";
}

function completionDedupeKey({ sessionId, threadId, turnId, result, now }) {
  if (readString(turnId)) {
    return `${readString(sessionId)}:${readString(threadId)}:${readString(turnId)}:${readString(result)}`;
  }
  const timeBucket = Math.floor(now() / 30_000);
  return `${readString(sessionId)}:${readString(threadId)}:thread-status:${timeBucket}:${readString(result)}`;
}

function buildNotificationBody({ result, state, params, eventObject, previewMaxChars }) {
  if (result === "failed") {
    return truncatePreview(
      normalizePreviewText(
        state?.latestFailurePreview
        || extractFailureMessage(params, eventObject)
        || "Run failed"
      ),
      previewMaxChars
    ) || "Run failed";
  }

  const readyPreview = truncatePreview(
    normalizePreviewText(
      state?.latestAssistantPreview
      || extractAssistantCompletedText(params, eventObject)
    ),
    previewMaxChars
  );
  return readyPreview ? "Response ready" : "Response ready";
}

function extractAssistantDeltaText(params, eventObject) {
  return firstNonEmptyString([
    params?.delta,
    params?.textDelta,
    eventObject?.delta,
    eventObject?.textDelta,
    eventObject?.content,
  ]);
}

function extractAssistantCompletedText(params, eventObject) {
  const item = params?.item || eventObject?.item || eventObject;
  return firstNonEmptyString([
    item?.text,
    params?.message,
    eventObject?.message,
    eventObject?.text,
    params?.content,
  ]);
}

function extractFailureMessage(params, eventObject) {
  return firstNonEmptyString([
    params?.message,
    params?.error?.message,
    eventObject?.message,
    eventObject?.error?.message,
  ]);
}

function turnStateKey(threadId, turnId) {
  return `${readString(threadId)}::${readString(turnId)}`;
}

function truncatePreview(value, maxChars) {
  const normalized = normalizePreviewText(value);
  if (!normalized) {
    return "";
  }
  if (!Number.isFinite(maxChars) || maxChars <= 0 || normalized.length <= maxChars) {
    return normalized;
  }
  return `${normalized.slice(0, Math.max(0, maxChars - 1))}...`;
}

function normalizePreviewText(value) {
  return typeof value === "string" ? value.replace(/\s+/g, " ").trim() : "";
}

function safeParseJSON(rawValue) {
  if (typeof rawValue !== "string" || !rawValue.trim()) {
    return null;
  }
  try {
    return JSON.parse(rawValue);
  } catch {
    return null;
  }
}

function objectValue(value) {
  return value && typeof value === "object" ? value : null;
}

function readString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

function firstNonEmptyString(values) {
  for (const value of values) {
    const normalized = readString(value);
    if (normalized) {
      return normalized;
    }
  }
  return "";
}

module.exports = {
  createPushNotificationTracker,
};
