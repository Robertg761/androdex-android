const test = require("node:test");
const assert = require("node:assert/strict");

const {
  createCanonicalThreadId,
  decodeCanonicalThreadId,
  rewriteBridgeMessageThreadIdsForAndroid,
  rewriteBridgeMessageThreadIdsForRuntime,
} = require("../src/thread-identity");

test("createCanonicalThreadId and decodeCanonicalThreadId round-trip runtime-target-scoped ids", () => {
  const canonical = createCanonicalThreadId("t3-server", "thread-123");
  assert.equal(canonical, "androdex-thread:t3-server:thread-123");
  assert.deepEqual(decodeCanonicalThreadId(canonical), {
    runtimeTarget: "t3-server",
    backendThreadId: "thread-123",
  });
});

test("rewriteBridgeMessageThreadIdsForAndroid canonicalizes thread summaries, relationships, and notifications", () => {
  const rawResponse = JSON.stringify({
    id: "req-thread-list",
    result: {
      data: [
        {
          id: "thread-123",
          title: "Example",
          preview: "Hello",
          cwd: "/tmp",
          forkedFromThreadId: "thread-parent",
        },
      ],
      thread: {
        id: "thread-456",
        title: "Loaded thread",
        turns: [],
        parentThreadId: "thread-root",
      },
    },
  });

  const normalizedResponse = JSON.parse(
    rewriteBridgeMessageThreadIdsForAndroid(rawResponse, "codex-native")
  );
  assert.equal(normalizedResponse.result.data[0].id, "androdex-thread:codex-native:thread-123");
  assert.equal(normalizedResponse.result.data[0].forkedFromThreadId, "androdex-thread:codex-native:thread-parent");
  assert.equal(normalizedResponse.result.thread.id, "androdex-thread:codex-native:thread-456");
  assert.equal(normalizedResponse.result.thread.parentThreadId, "androdex-thread:codex-native:thread-root");

  const rawNotification = JSON.stringify({
    method: "turn/started",
    params: {
      threadId: "thread-123",
      turnId: "turn-1",
    },
  });
  const normalizedNotification = JSON.parse(
    rewriteBridgeMessageThreadIdsForAndroid(rawNotification, "codex-native")
  );
  assert.equal(normalizedNotification.params.threadId, "androdex-thread:codex-native:thread-123");
});

test("rewriteBridgeMessageThreadIdsForRuntime restores raw thread ids for the active target", () => {
  const canonicalThreadId = createCanonicalThreadId("t3-server", "thread-123");
  const rawRequest = JSON.parse(
    rewriteBridgeMessageThreadIdsForRuntime(
      JSON.stringify({
        id: "req-thread-read",
        method: "thread/read",
        params: {
          threadId: canonicalThreadId,
          forkedFromThreadId: createCanonicalThreadId("t3-server", "thread-parent"),
        },
      }),
      "t3-server",
    )
  );

  assert.equal(rawRequest.params.threadId, "thread-123");
  assert.equal(rawRequest.params.forkedFromThreadId, "thread-parent");
});

test("rewriteBridgeMessageThreadIdsForRuntime rejects thread ids from another runtime target", () => {
  assert.throws(
    () => rewriteBridgeMessageThreadIdsForRuntime(
      JSON.stringify({
        id: "req-thread-read",
        method: "thread/read",
        params: {
          threadId: createCanonicalThreadId("codex-native", "thread-123"),
        },
      }),
      "t3-server",
    ),
    /belongs to runtime target codex-native, not t3-server/i,
  );
});
