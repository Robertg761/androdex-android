// FILE: runtime-compat.test.js
// Purpose: Verifies bridge-side request normalization and context extraction for richer mobile payloads.
// Layer: Unit test
// Exports: node:test suite
// Depends on: node:test, node:assert/strict, ../src/runtime-compat

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  extractBridgeMessageContext,
  normalizeRuntimeCompatibleRequest,
  shouldStartContextUsageWatcher,
} = require("../src/runtime-compat");

test("normalizeRuntimeCompatibleRequest promotes snake_case interrupt and runtime override fields", () => {
  const normalized = JSON.parse(normalizeRuntimeCompatibleRequest(JSON.stringify({
    id: "request-1",
    method: "turn/steer",
    params: {
      thread_id: "thread-1",
      expected_turn_id: "turn-1",
      collaboration_mode: {
        mode: "plan",
      },
      reasoning_effort: "medium",
    },
  })));

  assert.equal(normalized.params.threadId, "thread-1");
  assert.equal(normalized.params.expectedTurnId, "turn-1");
  assert.deepEqual(normalized.params.collaborationMode, { mode: "plan" });
  assert.equal(normalized.params.effort, "medium");
});

test("extractBridgeMessageContext reads nested event and item identifiers", () => {
  const context = extractBridgeMessageContext(JSON.stringify({
    method: "codex/event/item_completed",
    params: {
      event: {
        thread_id: "thread-event",
        item: {
          id: "item-1",
          turn_id: "turn-event",
        },
      },
    },
  }));

  assert.equal(context.method, "codex/event/item_completed");
  assert.equal(context.threadId, "thread-event");
  assert.equal(context.turnId, "turn-event");
  assert.equal(context.itemId, "item-1");
});

test("shouldStartContextUsageWatcher treats active thread status as a running turn signal", () => {
  const context = extractBridgeMessageContext(JSON.stringify({
    method: "thread/status/changed",
    params: {
      threadId: "thread-running",
      status: {
        type: "running",
      },
    },
  }));

  assert.equal(shouldStartContextUsageWatcher(context), true);
});
