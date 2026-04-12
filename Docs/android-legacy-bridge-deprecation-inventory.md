# Android Legacy Bridge Deprecation Inventory

This note records the remaining legacy bridge surfaces that still synthesize Android-facing thread or live state.

The converged Mac-native path now bypasses these surfaces for canonical auth, snapshot, replay, and mutating actions. That means they are no longer on the critical path for Android convergence and should be treated as compatibility-only until they are removed.

## Legacy Android Client Entry Points

The old Android client still drives bridge-owned RPC methods in:

- `android/app/src/main/java/io/androdex/android/data/AndrodexClient.kt`

Key bridge-owned methods still referenced there:

- `thread/list`
- `thread/read`
- `thread/resume`
- `thread/rollback`
- `thread/backgroundTerminals/clean`

These methods should remain legacy-only while the Mac-native repository path becomes the default convergence path.

## Bridge Runtime Translation Surfaces

The bridge still owns snapshot-to-Android translation and resumed-thread live semantics in:

- `androdex-bridge/src/runtime/adapter.js`
- `androdex-bridge/src/runtime/t3-read-model.js`
- `androdex-bridge/src/runtime/method-policy.js`
- `androdex-bridge/src/runtime-compat.js`
- `androdex-bridge/src/rollout/live-mirror.js`

Current bridge responsibilities in those files include:

- synthesizing `thread/list` responses from the T3 read model
- synthesizing `thread/read` responses from the T3 read model
- attaching bridge-managed live updates after `thread/resume`
- translating rollback and background-terminal cleanup into canonical commands on behalf of legacy clients
- preserving legacy thread identity and resume compatibility behavior

For convergence, those behaviors are now deprecated in favor of:

- direct `GET /api/orchestration/snapshot`
- direct `subscribeOrchestrationDomainEvents`
- direct `orchestration.replayEvents`
- direct canonical command dispatch from Android

## Legacy Desktop Refresh Helpers

The bridge also still carries desktop refresh workaround surfaces in:

- `androdex-bridge/src/codex-desktop-refresher.js`
- `androdex-bridge/src/macos-launch-agent.js`
- `androdex-bridge/src/control-panel.js`
- `desktop/`

These are legacy host convenience helpers only. The Mac-native convergence path must not depend on desktop refresh workarounds for correctness because canonical snapshot plus replay is now the source of truth.

## Deprecation Status

- Mac-native Android sync is the convergence path.
- Legacy bridge thread/list/read/resume semantics are compatibility-only.
- Bridge-managed live notifications are compatibility-only.
- Desktop refresh workarounds are explicitly outside the canonical convergence path.

Future cleanup should remove those legacy paths once the Mac-native route is the default and has baked successfully for real users.
