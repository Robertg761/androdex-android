// FILE: runtime-compat.js
// Purpose: Shares bridge/runtime message-context helpers and relay-safe history sanitization.
// Layer: Bridge helper
// Exports: message-context extraction, token-usage watcher hints, and history sanitization helpers.

const RELAY_HISTORY_IMAGE_REFERENCE_URL = "androdex://history-image-elided";

function extractBridgeMessageContext(rawMessage) {
  const parsed = parseBridgeJSON(rawMessage);
  if (!parsed) {
    return {
      method: "",
      threadId: null,
      turnId: null,
      itemId: null,
      params: null,
    };
  }

  const method = typeof parsed.method === "string" ? parsed.method.trim() : "";
  const params = objectValue(parsed.params);
  const eventObject = envelopeEventObject(params);
  const itemObject = incomingItemObject(params, eventObject);

  return {
    method,
    threadId: resolveThreadId(method, params, eventObject, itemObject),
    turnId: resolveTurnId(params, eventObject, itemObject),
    itemId: resolveItemId(params, eventObject, itemObject),
    params,
  };
}

function shouldStartContextUsageWatcher(context) {
  if (!context?.threadId) {
    return false;
  }

  return context.method === "turn/start"
    || context.method === "turn/steer"
    || context.method === "turn/started"
    || isActiveThreadStatus(context.method, context.params, envelopeEventObject(context.params));
}

function sanitizeThreadHistoryImagesForRelay(rawMessage, requestMethod) {
  if (requestMethod !== "thread/read" && requestMethod !== "thread/resume") {
    return rawMessage;
  }

  const parsed = parseBridgeJSON(rawMessage);
  const thread = parsed?.result?.thread;
  if (!thread || typeof thread !== "object" || !Array.isArray(thread.turns)) {
    return rawMessage;
  }

  let didSanitize = false;
  const sanitizedTurns = thread.turns.map((turn) => {
    if (!turn || typeof turn !== "object" || !Array.isArray(turn.items)) {
      return turn;
    }

    let turnDidChange = false;
    const sanitizedItems = turn.items.map((item) => {
      if (!item || typeof item !== "object" || !Array.isArray(item.content)) {
        return item;
      }

      let itemDidChange = false;
      const sanitizedContent = item.content.map((contentItem) => {
        const sanitizedEntry = sanitizeInlineHistoryImageContentItem(contentItem);
        if (sanitizedEntry !== contentItem) {
          itemDidChange = true;
        }
        return sanitizedEntry;
      });

      if (!itemDidChange) {
        return item;
      }

      turnDidChange = true;
      return {
        ...item,
        content: sanitizedContent,
      };
    });

    if (!turnDidChange) {
      return turn;
    }

    didSanitize = true;
    return {
      ...turn,
      items: sanitizedItems,
    };
  });

  if (!didSanitize) {
    return rawMessage;
  }

  return JSON.stringify({
    ...parsed,
    result: {
      ...parsed.result,
      thread: {
        ...thread,
        turns: sanitizedTurns,
      },
    },
  });
}

function sanitizeInlineHistoryImageContentItem(contentItem) {
  if (!contentItem || typeof contentItem !== "object") {
    return contentItem;
  }

  const normalizedType = normalizeRelayHistoryContentType(contentItem.type);
  if (normalizedType !== "image" && normalizedType !== "localimage") {
    return contentItem;
  }

  const hasInlineUrl = isInlineHistoryImageDataURL(contentItem.url)
    || isInlineHistoryImageDataURL(contentItem.image_url)
    || isInlineHistoryImageDataURL(contentItem.path);
  if (!hasInlineUrl) {
    return contentItem;
  }

  const {
    url: _url,
    image_url: _imageUrl,
    path: _path,
    ...rest
  } = contentItem;

  return {
    ...rest,
    url: RELAY_HISTORY_IMAGE_REFERENCE_URL,
  };
}

function resolveThreadId(method, params, eventObject, itemObject) {
  const candidates = [
    params?.threadId,
    params?.thread_id,
    params?.conversationId,
    params?.conversation_id,
    params?.thread?.id,
    params?.thread?.threadId,
    params?.thread?.thread_id,
    params?.turn?.threadId,
    params?.turn?.thread_id,
    itemObject?.threadId,
    itemObject?.thread_id,
    eventObject?.threadId,
    eventObject?.thread_id,
    eventObject?.conversationId,
    eventObject?.conversation_id,
  ];

  for (const candidate of candidates) {
    const value = readString(candidate);
    if (value) {
      return value;
    }
  }

  if (method === "thread/started" || method === "thread/start") {
    return readString(params?.id) || null;
  }

  return null;
}

function resolveTurnId(params, eventObject, itemObject) {
  const candidates = [
    params?.turnId,
    params?.turn_id,
    params?.id,
    params?.turn?.id,
    params?.turn?.turnId,
    params?.turn?.turn_id,
    eventObject?.id,
    eventObject?.turnId,
    eventObject?.turn_id,
    itemObject?.turnId,
    itemObject?.turn_id,
  ];

  for (const candidate of candidates) {
    const value = readString(candidate);
    if (value) {
      return value;
    }
  }

  return null;
}

function resolveItemId(params, eventObject, itemObject) {
  const candidates = [
    params?.itemId,
    params?.item_id,
    params?.messageId,
    params?.message_id,
    itemObject?.id,
    itemObject?.itemId,
    itemObject?.item_id,
    itemObject?.messageId,
    itemObject?.message_id,
    eventObject?.itemId,
    eventObject?.item_id,
  ];

  for (const candidate of candidates) {
    const value = readString(candidate);
    if (value) {
      return value;
    }
  }

  return null;
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

function incomingItemObject(params, eventObject) {
  if (params?.item && typeof params.item === "object") {
    return params.item;
  }
  if (eventObject?.item && typeof eventObject.item === "object") {
    return eventObject.item;
  }
  if (eventObject && typeof eventObject === "object" && typeof eventObject.type === "string") {
    return eventObject;
  }
  return null;
}

function isActiveThreadStatus(method, params, eventObject) {
  if (
    method !== "thread/status/changed"
    && method !== "thread/status"
    && method !== "codex/event/thread_status_changed"
  ) {
    return false;
  }

  const statusObject = objectValue(params?.status)
    || objectValue(eventObject?.status)
    || objectValue(params?.event?.status);
  const rawStatus = readString(
    statusObject?.type
      || statusObject?.statusType
      || statusObject?.status_type
      || params?.status
      || eventObject?.status
      || params?.event?.status
  );
  const normalizedStatus = normalizeStatusToken(rawStatus);

  return normalizedStatus === "active"
    || normalizedStatus === "running"
    || normalizedStatus === "processing"
    || normalizedStatus === "inprogress"
    || normalizedStatus === "started"
    || normalizedStatus === "pending";
}

function normalizeRelayHistoryContentType(value) {
  return typeof value === "string"
    ? value.toLowerCase().replace(/[\s_-]+/g, "")
    : "";
}

function isInlineHistoryImageDataURL(value) {
  return typeof value === "string" && value.toLowerCase().startsWith("data:image");
}

function normalizeStatusToken(value) {
  return typeof value === "string"
    ? value.toLowerCase().replace(/[_-\s]+/g, "")
    : "";
}

function parseBridgeJSON(value) {
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

function objectValue(value) {
  return value && typeof value === "object" && !Array.isArray(value) ? value : null;
}

function readString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

module.exports = {
  extractBridgeMessageContext,
  sanitizeThreadHistoryImagesForRelay,
  shouldStartContextUsageWatcher,
};
