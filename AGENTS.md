# AGENTS.md (Host-Local Runtime)

Keep this file and `CLAUDE.md` aligned.

This repo keeps Codex running on the user's host machine, but device access may happen over non-local relay connections. Do not hardcode private production domains, hosted credentials, or private deployment runbooks into the public repo.

## Core guardrails

- Prefer host-local runtime, bridge, QR pairing, daemon workflows, and relay-compatible remote access.
- Keep repo isolation by thread/project metadata and local `cwd`.
- Do not reintroduce filtering by selected repo in sidebar/content.
- Keep cross-repo open/create flow with automatic local context switch.
- Preserve single responsibility: shared logic belongs in services/coordinators, not duplicated in views.
- Treat this repo as open source: avoid junk code, placeholder hacks, noisy one-off workarounds, and low-signal docs.
- If you touch docs, keep the distinction clear between host-local Codex execution and remote device connectivity.

## Mobile runtime + timeline guardrails

- `turn/started` may not include a usable `turnId`: keep the per-thread running fallback.
- If Stop is tapped and `activeTurnIdByThread` is missing, resolve via `thread/read` before interrupting.
- On reconnect/background recover, rehydrate active turn state so Stop remains visible.
- Suppress benign background disconnect noise and retry on foreground.
- Keep assistant rows item-scoped to avoid timeline flattening/reordering.
- Merge late reasoning deltas into existing rows; do not spawn fake extra "Thinking..." rows.
- Ignore late turn-less activity events when the turn is already inactive.
- Preserve item-aware history reconciliation instead of falling back to `turnId`-only matching.

## Connection guardrails

- Prefer saved relay pairing and connection state as the source of truth.
- Avoid hardcoded remote domains; use explicit config or safe public defaults only where intended.
- Keep pairing/auth UX stable: do not clear saved relay info too early during reconnect flows.
- Preserve reconnect behavior across relaunch when the host session is still valid.

## Build guardrails

- Do not run Android builds/tests unless the user explicitly asks.
- For small mobile fixes, prefer inspection and targeted edits over emulator runs by default.

## Quick runbook

```bash
cd androdex-bridge
npm start
```
