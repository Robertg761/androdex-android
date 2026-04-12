# Android Sync Convergence

This note records the current migration direction for converging Androdex with the canonical T3 Code backend.

## Decision

`Androdex - Mac` is now the canonical backend for:

- auth and pairing bootstrap
- bearer sessions and WebSocket auth
- orchestration snapshot state
- orchestration event replay and live updates
- threads, turns, approvals, checkpoints, and session lifecycle actions

`Androdex - Android` remains the native mobile client, but it should act as a remote client of that backend instead of maintaining a second bridge-owned truth for thread or session state.

## What This Changes

- New Android sync work should target the Mac server's native HTTP and WebSocket contracts.
- Android may keep native UI state, persistence, notifications, and device-specific caching.
- Android should stop adding long-term bridge-only thread hydration, replay, approval, or checkpoint semantics.
- If a relay or helper remains during migration, it should be transport-only and must not own thread lists, replay cursors, or pairing/session semantics.
- The converged pairing payload should describe how to reach the Mac server directly, with any future remote tunnel forwarding the same HTTP and WebSocket protocol unchanged.
- The rollout gate is now runtime-level backend selection: mac-native pairing payloads and saved mac-native sessions route into the converged client, while the legacy bridge remains an explicit compatibility fallback for older pairings.

## First Milestone

The first integrated milestone is allowed to be LAN or loopback first. Internet-reachable transport can come later, but only as forwarding to the same Mac-native backend.

The first goal is:

1. Pair Android to the Mac server.
2. Bootstrap a bearer session.
3. Fetch the canonical orchestration snapshot.
4. Subscribe to live orchestration domain events.
5. Recover with replay from the authoritative sequence cursor.
6. Dispatch canonical mutating commands back to the Mac server.

## Current Protocol Reference

The Mac repo now documents the canonical client contract in:

- `Androdex - Mac/docs/androdex-android-canonical-architecture.md`
- `Androdex - Mac/docs/androdex-android-client-protocol-surface.md`

Android transport work should follow those documents and the matching contract definitions in the Mac repo instead of extending the older bridge-owned adapter model.

## Important Guardrail

During migration, Android should preserve native UX and stability, but protocol ownership must move toward:

- same snapshot
- same replay
- same mutating actions

That is the definition of successful convergence.
