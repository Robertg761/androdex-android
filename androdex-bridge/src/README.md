# Bridge Source Guide

This folder contains the macOS host bridge runtime used by the `androdex` CLI.

## Start Here

- Runtime entrypoint:
  [`bridge.js`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/bridge.js)
- Public exports:
  [`index.js`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/index.js)
- macOS service control:
  [`macos-launch-agent.js`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/macos-launch-agent.js)

## Folder Layout

- [`codex/`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/codex):
  Codex transport and JSON-RPC client helpers
- [`pairing/`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/pairing):
  QR output, trusted-device persistence, and encrypted transport
- [`notifications/`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/notifications):
  notification RPC handling, dedupe, service client, and completion tracking
- [`rollout/`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/rollout):
  rollout watch and live-mirror helpers
- [`runtime/`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/runtime):
  runtime-target selection, adapter seams, and shared launch configuration
- [`workspace/`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/workspace):
  workspace browsing, activation, and reverse-patch flows

## Top-Level Shared Files

- [`account-status.js`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/account-status.js):
  sanitized host account/auth status
- [`codex-desktop-refresher.js`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/codex-desktop-refresher.js):
  desktop refresh workaround logic
- [`daemon-state.js`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/daemon-state.js):
  persisted daemon config, pairing session, and logs metadata
- [`git-handler.js`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/git-handler.js):
  git status/branch/worktree operations
- [`runtime-compat.js`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/runtime-compat.js):
  protocol compatibility and payload sanitization
- [`session-state.js`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/session-state.js):
  last-active-thread tracking
- [`thread-context-handler.js`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/thread-context-handler.js):
  thread context/usage RPC handling

## Safe Entry Points For Debugging

- Pairing/reconnect issues:
  [`pairing/device-state.js`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/pairing/device-state.js),
  [`pairing/secure-transport.js`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/pairing/secure-transport.js)
- Workspace switching or reverse patch behavior:
  [`workspace/runtime.js`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/workspace/runtime.js),
  [`workspace/handler.js`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/workspace/handler.js)
- Push/completion behavior:
  [`notifications/handler.js`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/notifications/handler.js),
  [`notifications/tracker.js`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/notifications/tracker.js)
- Rollout/live update issues:
  [`rollout/watch.js`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/rollout/watch.js),
  [`rollout/live-mirror.js`](/Users/robert/Documents/Projects/androdex/androdex-bridge/src/rollout/live-mirror.js)

## Related Docs

- [`androdex-bridge/README.md`](/Users/robert/Documents/Projects/androdex/androdex-bridge/README.md)
- [`Docs/repo-map.md`](/Users/robert/Documents/Projects/androdex/Docs/repo-map.md)
- [`Docs/source-file-map.md`](/Users/robert/Documents/Projects/androdex/Docs/source-file-map.md)
