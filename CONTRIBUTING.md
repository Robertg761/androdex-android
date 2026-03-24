# Contributing to Androdex

Thanks for taking a look.

Androdex is maintained as a local-first fork focused on the Windows host + Android client workflow. Please keep changes aligned with that direction.

## Project direction

- Prefer local runtime, local bridge, QR pairing, and daemon workflows.
- Do not reintroduce hardcoded hosted-service assumptions or production domains.
- Keep shared logic in services/coordinators instead of duplicating it in views.
- Preserve explicit attribution to the upstream fork chain: `relaydex` and `Remodex`.

## Good contributions

- focused bug fixes
- reconnect, pairing, timeline, and runtime reliability improvements
- Android polish that preserves existing architecture
- docs fixes that remove stale hosted-service assumptions

## Changes to avoid

- large speculative refactors
- hosted-service integrations baked into defaults
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
- If you touch docs, prefer removing stale upstream or hosted-service instructions instead of layering compatibility text on top.
