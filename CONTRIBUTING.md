# Contributing to Androdex

Thanks for taking a look.

Androdex is maintained as a fork focused on the Windows host + Android client workflow, with Codex running locally on the host and remote access supported through relay-based connections. Please keep changes aligned with that direction.

## Project direction

- Keep Codex host-local while supporting remote access from the Android client through configurable relay infrastructure.
- Do not hardcode private production domains or service credentials into the public repo.
- Keep shared logic in services/coordinators instead of duplicating it in views.
- Preserve explicit attribution to the upstream fork chain: `relaydex` and `Remodex`.

## Good contributions

- focused bug fixes
- reconnect, pairing, timeline, and runtime reliability improvements
- Android polish that preserves existing architecture
- docs fixes that keep the host-local versus remote-access model clear

## Changes to avoid

- large speculative refactors
- hosted-service integrations that require private domains, private credentials, or repo-local secrets
- duplicate logic across views and services
- repo-specific hacks that make the public fork harder to understand

## Local setup

Bridge:

```sh
cd androdex-bridge
npm install
npm start
```

Android app:

```sh
cd android
gradlew assembleDebug
```

## Notes

- Set `ANDRODEX_RELAY` explicitly when testing local or self-hosted relay flows.
- Do not commit private relay hostnames, credentials, signing secrets, or device-specific overrides.
- If you touch docs, keep the distinction clear between the host-local Codex runtime and remote relay-backed device access.
- Keep local scratch files and one-off generated outputs under `tmp/` or `tmp-gh-artifact/`; those paths are intentionally gitignored.

## UI Parity Workflow

Remodex is the visual source of truth for Androdex UI work during the clone effort.

If a change touches user-visible Android UI, use the reference package in [`Docs/remodex-visual-reference/README.md`](/Users/robert/Documents/Projects/androdex/Docs/remodex-visual-reference/README.md) before merging.

That means:

- compare against the mapped Remodex state in [`Docs/remodex-visual-reference/parity-board.md`](/Users/robert/Documents/Projects/androdex/Docs/remodex-visual-reference/parity-board.md)
- include Android screenshots or recordings for each touched state, or explicitly call out the missing capture as backlog
- update the parity docs when a status, evidence link, or allowed compromise changes
- prefer shared Remodex primitives and tokens over raw Material defaults or one-off screen-local styling

The current enforcement summary and explicit backlog live in [`Docs/remodex-visual-reference/phase13-parity-audit.md`](/Users/robert/Documents/Projects/androdex/Docs/remodex-visual-reference/phase13-parity-audit.md).
