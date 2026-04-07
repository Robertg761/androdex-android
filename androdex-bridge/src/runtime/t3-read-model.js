// FILE: runtime/t3-read-model.js
// Purpose: Translates the T3 orchestration read model into the existing Androdex thread/list and thread/read contract.
// Layer: CLI helper
// Exports: T3 snapshot translation helpers
// Depends on: fs, path

const fs = require("fs");
const path = require("path");

function buildT3ThreadListResult({
  snapshot = null,
  limit = 40,
  cursor = null,
} = {}) {
  const visibleThreads = listVisibleThreads(snapshot);
  const offset = Math.max(0, parseCursorOffset(cursor));
  const slicedThreads = visibleThreads.slice(offset, offset + normalizeLimit(limit));
  const nextOffset = offset + slicedThreads.length;
  return {
    data: slicedThreads.map((entry) => entry.summary),
    nextCursor: nextOffset < visibleThreads.length ? String(nextOffset) : null,
  };
}

function buildT3ThreadReadResult({
  snapshot = null,
  threadId = "",
} = {}) {
  const visibleThreads = listVisibleThreads(snapshot);
  const selectedThread = visibleThreads.find((entry) => entry.thread.id === normalizeNonEmptyString(threadId));
  if (!selectedThread) {
    throw new Error(`T3 thread not found: ${normalizeNonEmptyString(threadId) || "<unknown>"}`);
  }

  return {
    thread: {
      ...selectedThread.summary,
      turns: buildThreadTurns(selectedThread.thread),
    },
  };
}

function listVisibleThreads(snapshot) {
  const projectsById = buildProjectMap(snapshot?.projects);
  const threads = Array.isArray(snapshot?.threads) ? snapshot.threads : [];

  return threads
    .filter((thread) => thread && typeof thread === "object" && !thread.deletedAt)
    .map((thread) => {
      const project = projectsById.get(normalizeNonEmptyString(thread.projectId)) || null;
      return {
        project,
        summary: buildThreadSummary(thread, project),
        thread,
      };
    })
    .sort((left, right) => {
      const leftUpdatedAt = timestampOrZero(left.thread.updatedAt || left.thread.createdAt);
      const rightUpdatedAt = timestampOrZero(right.thread.updatedAt || right.thread.createdAt);
      return rightUpdatedAt - leftUpdatedAt;
    });
}

function buildProjectMap(projects) {
  const projectsById = new Map();
  for (const project of Array.isArray(projects) ? projects : []) {
    const projectId = normalizeNonEmptyString(project?.id);
    if (!projectId) {
      continue;
    }
    projectsById.set(projectId, project);
  }
  return projectsById;
}

function buildThreadSummary(thread, project) {
  const cwd = resolveThreadCwd(thread, project);
  const provider = normalizeNonEmptyString(thread?.modelSelection?.provider);
  const preview = buildThreadPreview(thread, project, provider, cwd);
  return {
    id: normalizeNonEmptyString(thread?.id),
    title: normalizeNonEmptyString(thread?.title) || "Conversation",
    preview,
    cwd,
    createdAt: normalizeNonEmptyString(thread?.createdAt) || null,
    updatedAt: normalizeNonEmptyString(thread?.updatedAt) || normalizeNonEmptyString(thread?.createdAt) || null,
    model: normalizeNonEmptyString(thread?.modelSelection?.model) || null,
  };
}

function buildThreadPreview(thread, project, provider, cwd) {
  const notices = [];
  if (provider && provider !== "codex") {
    notices.push(`Unsupported T3 provider: ${provider}`);
  }
  if (cwd && !pathExists(cwd)) {
    notices.push("Workspace unavailable locally");
  }
  if (thread?.archivedAt) {
    notices.push("Archived");
  }

  const basePreview = latestMessagePreview(thread);
  return [...notices, basePreview].filter(Boolean).join(" | ") || null;
}

function latestMessagePreview(thread) {
  const messages = Array.isArray(thread?.messages) ? thread.messages : [];
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const preview = composeMessageText(messages[index]);
    if (preview) {
      return preview;
    }
  }
  return "";
}

function resolveThreadCwd(thread, project) {
  return normalizeNonEmptyString(thread?.worktreePath)
    || normalizeNonEmptyString(project?.workspaceRoot)
    || null;
}

function buildThreadTurns(thread) {
  const messages = [...(Array.isArray(thread?.messages) ? thread.messages : [])]
    .sort((left, right) => {
      const leftCreated = timestampOrZero(left?.createdAt || left?.updatedAt);
      const rightCreated = timestampOrZero(right?.createdAt || right?.updatedAt);
      if (leftCreated !== rightCreated) {
        return leftCreated - rightCreated;
      }
      return normalizeNonEmptyString(left?.id).localeCompare(normalizeNonEmptyString(right?.id));
    });

  const turns = [];
  const turnsByKey = new Map();

  for (const message of messages) {
    const turnId = normalizeNonEmptyString(message?.turnId);
    const turnKey = turnId || `message:${normalizeNonEmptyString(message?.id) || turns.length + 1}`;
    let turn = turnsByKey.get(turnKey);
    if (!turn) {
      turn = {
        ...(turnId ? { id: turnId } : {}),
        createdAt: normalizeNonEmptyString(message?.createdAt) || normalizeNonEmptyString(message?.updatedAt) || null,
        updatedAt: normalizeNonEmptyString(message?.updatedAt) || normalizeNonEmptyString(message?.createdAt) || null,
        items: [],
      };
      turnsByKey.set(turnKey, turn);
      turns.push(turn);
    }

    turn.items.push(buildTurnItem(message));
  }

  const latestTurnId = normalizeNonEmptyString(thread?.latestTurn?.turnId);
  if (latestTurnId && !turnsByKey.has(latestTurnId)) {
    const latestTurn = {
      id: latestTurnId,
      createdAt: normalizeNonEmptyString(thread?.latestTurn?.requestedAt)
        || normalizeNonEmptyString(thread?.latestTurn?.startedAt)
        || normalizeNonEmptyString(thread?.latestTurn?.completedAt)
        || normalizeNonEmptyString(thread?.updatedAt)
        || null,
      updatedAt: normalizeNonEmptyString(thread?.latestTurn?.completedAt)
        || normalizeNonEmptyString(thread?.latestTurn?.startedAt)
        || normalizeNonEmptyString(thread?.latestTurn?.requestedAt)
        || normalizeNonEmptyString(thread?.updatedAt)
        || null,
      items: [],
    };
    turnsByKey.set(latestTurnId, latestTurn);
    turns.push(latestTurn);
  }

  for (const turn of turns) {
    const normalizedTurnId = normalizeNonEmptyString(turn.id);
    if (normalizedTurnId && normalizedTurnId === latestTurnId) {
      turn.status = mapLatestTurnStateToThreadStatus(thread?.latestTurn?.state);
    } else {
      turn.status = "completed";
    }
  }

  return turns;
}

function buildTurnItem(message) {
  const role = normalizeNonEmptyString(message?.role);
  const baseItem = {
    id: normalizeNonEmptyString(message?.id) || null,
    createdAt: normalizeNonEmptyString(message?.createdAt) || normalizeNonEmptyString(message?.updatedAt) || null,
    updatedAt: normalizeNonEmptyString(message?.updatedAt) || normalizeNonEmptyString(message?.createdAt) || null,
  };
  const content = buildMessageContent(message, role);

  if (role === "user") {
    return {
      ...baseItem,
      type: "user_message",
      content,
    };
  }

  if (role === "assistant") {
    return {
      ...baseItem,
      type: "assistant_message",
      content,
    };
  }

  return {
    ...baseItem,
    type: "message",
    role: role || "system",
    text: composeMessageText(message),
  };
}

function buildMessageContent(message, role) {
  const text = composeMessageText(message);
  const textType = role === "user" ? "input_text" : "output_text";
  const content = [];
  if (text) {
    content.push({
      type: textType,
      text,
    });
  }
  content.push(...buildAttachmentContentItems(message?.attachments));
  if (content.length > 0) {
    return content;
  }
  return [
    {
      type: textType,
      text: "",
    },
  ];
}

function composeMessageText(message) {
  const text = normalizeNonEmptyString(message?.text);
  const attachmentLabels = listUnsupportedAttachmentLabels(message?.attachments);
  return [text, ...attachmentLabels].filter(Boolean).join("\n").trim();
}

function listUnsupportedAttachmentLabels(attachments) {
  return (Array.isArray(attachments) ? attachments : [])
    .map((attachment) => {
      if (isRenderableImageAttachment(attachment)) {
        return "";
      }
      const attachmentName = normalizeNonEmptyString(attachment?.name);
      if (!attachmentName) {
        return "";
      }
      return `[Attachment: ${attachmentName}]`;
    })
    .filter(Boolean);
}

function buildAttachmentContentItems(attachments) {
  return (Array.isArray(attachments) ? attachments : [])
    .map((attachment) => buildAttachmentContentItem(attachment))
    .filter(Boolean);
}

function buildAttachmentContentItem(attachment) {
  if (!isRenderableImageAttachment(attachment)) {
    return null;
  }

  const source = firstNonEmptyString([
    attachment?.url,
    attachment?.image_url,
    attachment?.imageUrl,
    attachment?.path,
    attachment?.sourceUrl,
    attachment?.source_url,
  ]);
  const thumbnailBase64JPEG = firstNonEmptyString([
    attachment?.thumbnailBase64JPEG,
    attachment?.thumbnail_base64_jpeg,
  ]);
  const contentItem = {
    type: looksLikeLocalPath(source) ? "localimage" : "image",
  };

  if (source) {
    if (looksLikeLocalPath(source)) {
      contentItem.path = source;
    } else {
      contentItem.url = source;
    }
  }
  if (thumbnailBase64JPEG) {
    contentItem.thumbnailBase64JPEG = thumbnailBase64JPEG;
  }

  return Object.keys(contentItem).length > 1 ? contentItem : null;
}

function isRenderableImageAttachment(attachment) {
  if (!attachment || typeof attachment !== "object") {
    return false;
  }

  const attachmentType = firstNonEmptyString([
    attachment.type,
    attachment.kind,
  ]).toLowerCase();
  if (attachmentType === "image" || attachmentType === "localimage") {
    return true;
  }

  const mimeType = firstNonEmptyString([
    attachment.mimeType,
    attachment.mime_type,
    attachment.mediaType,
    attachment.media_type,
    attachment.contentType,
    attachment.content_type,
  ]).toLowerCase();
  if (mimeType.startsWith("image/")) {
    return true;
  }

  const source = firstNonEmptyString([
    attachment.url,
    attachment.image_url,
    attachment.imageUrl,
    attachment.path,
    attachment.sourceUrl,
    attachment.source_url,
  ]);
  if (source && looksLikeImageSource(source)) {
    return true;
  }

  const name = normalizeNonEmptyString(attachment.name);
  if (name && looksLikeImageSource(name)) {
    return true;
  }

  return !!firstNonEmptyString([
    attachment.thumbnailBase64JPEG,
    attachment.thumbnail_base64_jpeg,
  ]);
}

function looksLikeImageSource(source) {
  if (!source) {
    return false;
  }
  if (/^data:image\//i.test(source)) {
    return true;
  }
  return /\.(avif|bmp|gif|heic|heif|jpe?g|png|svg|webp)(?:[?#].*)?$/i.test(source);
}

function looksLikeLocalPath(source) {
  if (!source || /^data:/i.test(source) || /^[a-z]+:\/\//i.test(source)) {
    return false;
  }
  return source.startsWith("/")
    || source.startsWith("./")
    || source.startsWith("../")
    || /^[A-Za-z]:[\\/]/.test(source);
}

function firstNonEmptyString(values) {
  for (const value of Array.isArray(values) ? values : []) {
    const normalized = normalizeNonEmptyString(value);
    if (normalized) {
      return normalized;
    }
  }
  return "";
}

function mapLatestTurnStateToThreadStatus(state) {
  const normalizedState = normalizeNonEmptyString(state).toLowerCase();
  if (normalizedState === "running") {
    return "running";
  }
  if (normalizedState === "interrupted") {
    return "interrupted";
  }
  if (normalizedState === "error") {
    return "error";
  }
  return "completed";
}

function normalizeLimit(limit) {
  const numericLimit = Number(limit);
  if (!Number.isFinite(numericLimit) || numericLimit <= 0) {
    return 40;
  }
  return Math.max(1, Math.min(200, Math.trunc(numericLimit)));
}

function parseCursorOffset(cursor) {
  if (cursor == null) {
    return 0;
  }
  const parsed = Number(cursor);
  return Number.isFinite(parsed) && parsed >= 0 ? Math.trunc(parsed) : 0;
}

function timestampOrZero(value) {
  const parsed = Date.parse(normalizeNonEmptyString(value) || "");
  return Number.isFinite(parsed) ? parsed : 0;
}

function normalizeNonEmptyString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

function pathExists(candidatePath) {
  try {
    return fs.existsSync(path.normalize(candidatePath));
  } catch {
    return false;
  }
}

module.exports = {
  buildT3ThreadListResult,
  buildT3ThreadReadResult,
};
