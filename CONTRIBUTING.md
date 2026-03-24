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
- hosted-service integrations baked into public defaults without explicit config
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

iOS source tree:

```sh
cd CodexMobile
open CodexMobile.xcodeproj
```

## Notes

- Set `ANDRODEX_RELAY` explicitly when testing relay-based flows.
- Do not commit private relay hostnames, credentials, signing secrets, or device-specific overrides.
- If you touch docs, keep the distinction clear between the host-local Codex runtime and remote relay-backed device access.
