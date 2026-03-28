// FILE: codex-desktop-thread-sync.js
// Purpose: Refreshes app-server thread state from disk before nudging the desktop UI.
// Layer: CLI helper
// Exports: createDesktopThreadReadRefresher
// Depends on: none

function createDesktopThreadReadRefresher({
  sendCodexRequest,
  includeTurns = true,
} = {}) {
  if (typeof sendCodexRequest !== "function") {
    throw new Error("createDesktopThreadReadRefresher requires a sendCodexRequest function.");
  }

  return function refreshDesktopThreadState({ threadId }) {
    const normalizedThreadId = readString(threadId);
    if (!normalizedThreadId) {
      return Promise.resolve(null);
    }

    return sendCodexRequest("thread/read", {
      threadId: normalizedThreadId,
      includeTurns,
    });
  };
}

function readString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

module.exports = {
  createDesktopThreadReadRefresher,
};
