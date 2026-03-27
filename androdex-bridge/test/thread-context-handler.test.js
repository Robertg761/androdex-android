const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");

const { handleThreadContextRequest } = require("../src/thread-context-handler");

test("thread/contextWindow/read returns the latest rollout-backed usage snapshot", async (t) => {
  const { homeDir, threadDir } = makeTemporarySessionsHome();
  const previousCodexHome = process.env.CODEX_HOME;
  process.env.CODEX_HOME = homeDir;
  t.after(() => {
    restoreCodexHome(previousCodexHome);
    fs.rmSync(homeDir, { recursive: true, force: true });
  });

  writeRolloutFile(path.join(threadDir, "rollout-2026-03-26T09-00-00-thread-a.jsonl"), {
    turnId: "turn-a",
    tokensUsed: 314,
    tokenLimit: 1_024,
  });

  const responses = [];
  const handled = handleThreadContextRequest(JSON.stringify({
    id: "context-1",
    method: "thread/contextWindow/read",
    params: {
      threadId: "thread-a",
    },
  }), (response) => {
    responses.push(JSON.parse(response));
  });

  assert.equal(handled, true);
  await tick();

  assert.equal(responses.length, 1);
  assert.equal(responses[0].id, "context-1");
  assert.deepEqual(responses[0].result, {
    threadId: "thread-a",
    usage: {
      tokensUsed: 314,
      tokenLimit: 1_024,
    },
    rolloutPath: path.join(threadDir, "rollout-2026-03-26T09-00-00-thread-a.jsonl"),
  });
});

test("thread/contextWindow/read requires a threadId", async () => {
  const responses = [];
  const handled = handleThreadContextRequest(JSON.stringify({
    id: "context-2",
    method: "thread/contextWindow/read",
    params: {},
  }), (response) => {
    responses.push(JSON.parse(response));
  });

  assert.equal(handled, true);
  await tick();

  assert.equal(responses[0].error?.data?.errorCode, "missing_thread_id");
  assert.match(responses[0].error?.message || "", /requires a threadId/);
});

function makeTemporarySessionsHome() {
  const homeDir = fs.mkdtempSync(path.join(os.tmpdir(), "thread-context-"));
  const threadDir = path.join(homeDir, "sessions", "2026", "03", "26");
  fs.mkdirSync(threadDir, { recursive: true });
  return { homeDir, threadDir };
}

function writeRolloutFile(filePath, { turnId, tokensUsed, tokenLimit }) {
  const lines = [
    JSON.stringify({
      timestamp: "2026-03-26T09:00:00.000Z",
      type: "event_msg",
      payload: {
        type: "task_started",
        turn_id: turnId,
        model_context_window: tokenLimit,
      },
    }),
    JSON.stringify({
      timestamp: "2026-03-26T09:00:01.000Z",
      type: "event_msg",
      payload: {
        type: "token_count",
        info: {
          last_token_usage: {
            total_tokens: tokensUsed,
          },
          model_context_window: tokenLimit,
        },
      },
    }),
    "",
  ];
  fs.writeFileSync(filePath, lines.join("\n"));
}

function restoreCodexHome(previousCodexHome) {
  if (previousCodexHome == null) {
    delete process.env.CODEX_HOME;
    return;
  }
  process.env.CODEX_HOME = previousCodexHome;
}

function tick() {
  return new Promise((resolve) => setTimeout(resolve, 0));
}
