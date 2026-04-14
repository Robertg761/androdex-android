# Bridge Source Guide

This folder contains the older macOS bridge runtime used by the `androdex` CLI package.

The bridge code is still useful reference and compatibility material, but it is no longer the primary Android runtime path documented in the root README.

## Start Here

- Runtime entrypoint: [`bridge.js`](./bridge.js)
- Public exports: [`index.js`](./index.js)
- macOS service control: [`macos-launch-agent.js`](./macos-launch-agent.js)

## Folder Layout

- [`codex/`](./codex): Codex transport and JSON-RPC client helpers
- [`pairing/`](./pairing): QR output, trusted-device persistence, and encrypted transport
- [`notifications/`](./notifications): notification handling and completion tracking
- [`rollout/`](./rollout): rollout watch and live-mirror helpers
- [`runtime/`](./runtime): runtime-target selection and shared launch configuration
- [`workspace/`](./workspace): workspace browsing, activation, and reverse-patch flows

## Top-Level Shared Files

- [`account-status.js`](./account-status.js)
- [`codex-desktop-refresher.js`](./codex-desktop-refresher.js)
- [`daemon-state.js`](./daemon-state.js)
- [`git-handler.js`](./git-handler.js)
- [`runtime-compat.js`](./runtime-compat.js)
- [`session-state.js`](./session-state.js)
- [`thread-context-handler.js`](./thread-context-handler.js)

## Related Docs

- [Bridge README](../README.md)
- [Repo Map](../../Docs/repo-map.md)
- [Source File Map](../../Docs/source-file-map.md)
