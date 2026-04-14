# Source File Map

This document is the code-focused companion to [Repo Map](./repo-map.md).

## Runtime Overview

The current Android runtime in this repo is:

1. pairing-link intake
2. paired-origin persistence
3. Android WebView shell for the desktop-served Androdex app

The older bridge/native-client stack is still present in-tree, but it is no longer the active `MainActivity` path.

## Current Android Runtime

Base path: `android/app/src/main/java/io/androdex/android/`

### App entry

- `MainActivity.kt`: current Android activity entrypoint. Boots `MirrorShellViewModel`, not the legacy `MainViewModel`.
- `MirrorShellApp.kt`: top-level runtime switch between the pairing screen and the paired WebView shell.
- `MirrorShellViewModel.kt`: pairing-link intake, persisted-state restore, deep-link handling, notification reopen routing, and clear-pairing behavior.

### Pairing

- `pairing/MirrorPairingScreen.kt`: manual pairing-link input and QR-scan entry screen.
- `pairing/PairingLinkParser.kt`: accepts `https://host/pair...` URLs and wrapped `androdex://pair?payload=...` deep links, normalizes the paired origin, and enforces same-origin navigation.

### Persistence

- `persistence/MirrorShellStore.kt`: SharedPreferences store for `pairedOrigin`, `displayLabel`, optional bootstrap URL, and `lastOpenedUrl`.

### Web shell

- `web/MirrorWebShell.kt`: hosts the paired web app inside an Android `WebView`, sets Android-specific UA markers, enforces same-origin navigation, wires file chooser/camera upload flows, exposes top-bar actions, and remembers the last opened non-pair route.

### Notification reopen helper

- `notifications/decodeNotificationOpenPayload` usage in `MirrorShellViewModel.kt`: routes notification opens back into the paired environment when possible.

## Legacy Android Paths Still In Tree

These files and packages describe the older native-client architecture and are no longer the active runtime entry:

- `AndrodexApp.kt`
- `MainViewModel.kt`
- `data/`
- `model/`
- `service/`
- `timeline/`
- `transport/`
- `ui/`

They are still useful when reading older docs or experiments, but they should be treated as legacy/reference material unless the runtime entry changes.

## Bridge And Relay Reference Code

### Bridge

Base path: `androdex-bridge/`

- `bin/cli.js`: legacy bridge CLI dispatcher
- `src/bridge.js`: bridge orchestration process
- `src/pairing/`: previous encrypted pairing and reconnect logic
- `src/workspace/`: workspace activation and host-side helpers
- `src/runtime/`: runtime-target selection and compatibility code

### Relay

Base path: `relay/`

- `server.js`: HTTP/WebSocket relay entrypoint
- `relay.js`: relay session and forwarding logic
- `push-service.js`: optional push-session helper

## Companion Desktop Repo

The canonical desktop/server backend referenced by the current Android runtime lives outside this repo:

- https://github.com/Robertg761/Androdex-Desktop
