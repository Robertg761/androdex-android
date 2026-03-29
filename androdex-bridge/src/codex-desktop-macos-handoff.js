// FILE: codex-desktop-macos-handoff.js
// Purpose: Provides a stronger macOS Codex handoff flow than a simple deep-link bounce.
// Layer: CLI helper
// Exports: continueOnMacDesktop
// Depends on: child_process, path, ./rollout-watch

const path = require("path");
const { execFile } = require("child_process");
const { findRolloutFileForThread, resolveSessionsRoot } = require("./rollout-watch");

const HANDOFF_TIMEOUT_MS = 20_000;
const DEFAULT_RELAUNCH_WAIT_MS = 300;
const DEFAULT_APP_BOOT_WAIT_MS = 1_200;
const DEFAULT_THREAD_MATERIALIZE_WAIT_MS = 4_000;
const DEFAULT_THREAD_MATERIALIZE_POLL_MS = 250;

function continueOnMacDesktop(
  threadId,
  {
    bundleId = "com.openai.codex",
    appPath = "/Applications/Codex.app",
    executor = execFilePromise,
    env = process.env,
    sleepFn = sleep,
    relaunchWaitMs = DEFAULT_RELAUNCH_WAIT_MS,
    appBootWaitMs = DEFAULT_APP_BOOT_WAIT_MS,
    threadMaterializeWaitMs = DEFAULT_THREAD_MATERIALIZE_WAIT_MS,
    threadMaterializePollMs = DEFAULT_THREAD_MATERIALIZE_POLL_MS,
  } = {}
) {
  const normalizedThreadId = typeof threadId === "string" ? threadId.trim() : "";
  if (!normalizedThreadId) {
    return Promise.reject(new Error("A thread id is required for macOS desktop handoff."));
  }

  const targetUrl = `codex://threads/${normalizedThreadId}`;
  return forceRelaunchCodexApp({
    bundleId,
    appPath,
    executor,
    sleepFn,
    relaunchWaitMs,
    appBootWaitMs,
  }).then(() => openWhenThreadReady(normalizedThreadId, targetUrl, {
    bundleId,
    appPath,
    executor,
    env,
    sleepFn,
    waitMs: threadMaterializeWaitMs,
    pollMs: threadMaterializePollMs,
  }));
}

async function openWhenThreadReady(
  threadId,
  targetUrl,
  { bundleId, appPath, executor, env, sleepFn, waitMs, pollMs }
) {
  await waitForThreadMaterialization(threadId, {
    env,
    sleepFn,
    timeoutMs: waitMs,
    pollMs,
  });
  await openCodexTarget(targetUrl, { bundleId, appPath, executor });
}

async function forceRelaunchCodexApp({
  bundleId,
  appPath,
  executor,
  sleepFn,
  relaunchWaitMs,
  appBootWaitMs,
}) {
  const appName = path.basename(appPath, ".app");

  try {
    await executor("pkill", ["-x", appName], { timeout: HANDOFF_TIMEOUT_MS });
  } catch (error) {
    if (error?.code !== 1) {
      throw error;
    }
  }

  await waitForAppExit(appPath, executor, sleepFn);
  await sleepFn(relaunchWaitMs);
  await openCodexApp({ bundleId, appPath, executor });
  await sleepFn(appBootWaitMs);
}

async function waitForAppExit(appPath, executor, sleepFn) {
  const deadline = Date.now() + HANDOFF_TIMEOUT_MS;
  while (Date.now() < deadline) {
    if (!(await detectRunningCodexApp(appPath, executor))) {
      return;
    }
    await sleepFn(100);
  }

  throw new Error("Timed out waiting for Codex.app to close.");
}

async function detectRunningCodexApp(appPath, executor) {
  const appName = path.basename(appPath, ".app");
  try {
    await executor("pgrep", ["-x", appName], { timeout: HANDOFF_TIMEOUT_MS });
    return true;
  } catch {
    return false;
  }
}

async function openCodexTarget(targetUrl, { bundleId, appPath, executor }) {
  try {
    await executor("open", ["-b", bundleId, targetUrl], {
      timeout: HANDOFF_TIMEOUT_MS,
      stdio: "ignore",
    });
  } catch {
    await executor("open", ["-a", appPath, targetUrl], {
      timeout: HANDOFF_TIMEOUT_MS,
      stdio: "ignore",
    });
  }
}

async function openCodexApp({ bundleId, appPath, executor }) {
  try {
    await executor("open", ["-b", bundleId], {
      timeout: HANDOFF_TIMEOUT_MS,
      stdio: "ignore",
    });
  } catch {
    await executor("open", ["-a", appPath], {
      timeout: HANDOFF_TIMEOUT_MS,
      stdio: "ignore",
    });
  }
}

async function waitForThreadMaterialization(
  threadId,
  { env, sleepFn, timeoutMs, pollMs }
) {
  if (hasDesktopRolloutForThread(threadId, { env })) {
    return true;
  }

  const deadline = Date.now() + Math.max(0, timeoutMs);
  while (Date.now() < deadline) {
    await sleepFn(pollMs);
    if (hasDesktopRolloutForThread(threadId, { env })) {
      return true;
    }
  }

  return false;
}

function hasDesktopRolloutForThread(threadId, { env }) {
  const sessionsRoot = resolveSessionsRootForEnv(env);
  return findRolloutFileForThread(sessionsRoot, threadId) != null;
}

function resolveSessionsRootForEnv(env) {
  if (env?.CODEX_HOME) {
    return path.join(env.CODEX_HOME, "sessions");
  }

  return resolveSessionsRoot();
}

function execFilePromise(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    execFile(command, args, options, (error, stdout, stderr) => {
      if (error) {
        error.stdout = stdout;
        error.stderr = stderr;
        reject(error);
        return;
      }
      resolve({ stdout, stderr });
    });
  });
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

module.exports = {
  continueOnMacDesktop,
};
