# Android App Guide

This directory contains the Android Studio project for the Androdex client.

## Start Here

- App module: [`android/app/`](/Users/robert/Documents/Projects/androdex/android/app)
- Main source root: [`android/app/src/main/java/io/androdex/android/`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android)
- Unit tests: [`android/app/src/test/java/io/androdex/android/`](/Users/robert/Documents/Projects/androdex/android/app/src/test/java/io/androdex/android)
- UI tests: [`android/app/src/androidTest/java/io/androdex/android/`](/Users/robert/Documents/Projects/androdex/android/app/src/androidTest/java/io/androdex/android)

## Where To Change Things

- App entry and screen orchestration:
  [`MainActivity.kt`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android/MainActivity.kt),
  [`AndrodexApp.kt`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android/AndrodexApp.kt),
  [`MainViewModel.kt`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android/MainViewModel.kt)
- Long-lived connection/runtime state:
  [`service/AndrodexService.kt`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android/service/AndrodexService.kt)
- Relay/bridge protocol and request building:
  [`data/AndrodexClient.kt`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android/data/AndrodexClient.kt)
- Persistence and saved pairing/runtime state:
  [`data/AndrodexPersistence.kt`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android/data/AndrodexPersistence.kt)
- Repository abstraction:
  [`data/AndrodexRepository.kt`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android/data/AndrodexRepository.kt)
- Timeline projection:
  [`timeline/ThreadTimelineRender.kt`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android/timeline/ThreadTimelineRender.kt)
- Pairing UI:
  [`ui/pairing/`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android/ui/pairing)
- Home/sidebar UI:
  [`ui/home/`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android/ui/home),
  [`ui/sidebar/`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android/ui/sidebar)
- Turn/timeline/composer UI:
  [`ui/turn/`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android/ui/turn)
- Shared UI primitives and chrome:
  [`ui/shared/`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android/ui/shared)
- Theme and feature-state adapters:
  [`ui/theme/`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android/ui/theme),
  [`ui/state/`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android/ui/state)
- Notifications:
  [`notifications/`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android/notifications)

## Working Style

- Prefer feature-local UI changes inside `ui/*` before adding more logic to the app root.
- Prefer service/repository/client layers for protocol, connection, and persistence changes.
- Treat [`MainViewModel.kt`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android/MainViewModel.kt),
  [`AndrodexService.kt`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android/service/AndrodexService.kt),
  and [`AndrodexClient.kt`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android/data/AndrodexClient.kt)
  as high-impact files: small edits there can affect many flows.
- For user-visible Android UI work, check the parity/reference docs in
  [`Docs/remodex-visual-reference/README.md`](/Users/robert/Documents/Projects/androdex/Docs/remodex-visual-reference/README.md).

## Pairing And Reconnect Model

- The Android app now treats the trusted host relationship as durable state, not as a disposable live relay session.
- [`data/AndrodexPersistence.kt`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android/data/AndrodexPersistence.kt)
  stores the phone identity, trusted Mac registry, and recovery payloads separately from the throwaway saved relay session.
- [`data/AndrodexClient.kt`](/Users/robert/Documents/Projects/androdex/android/app/src/main/java/io/androdex/android/data/AndrodexClient.kt)
  owns three distinct paths:
  fresh QR bootstrap, trusted reconnect to resolve a fresh live session, and trusted recovery/rekey after reinstall.
- Recovery credentials are phone-owned from first pairing onward. The bridge and relay only receive the public trust material needed to verify reconnect or rekey requests.
- Cold-start reconnect matters just as much as foreground/background reconnect. Force-stop, app-switcher removal, and process death should still come back through trusted reconnect rather than pushing the user into a fake “pair again” state.
- Legacy reconnect compatibility checks must distinguish real `macDeviceId` values from older live-session identifiers. If you touch reconnect selection or repair heuristics, test force-stop plus relaunch on a real device.

## Related Docs

- [`Docs/repo-map.md`](/Users/robert/Documents/Projects/androdex/Docs/repo-map.md)
- [`Docs/source-file-map.md`](/Users/robert/Documents/Projects/androdex/Docs/source-file-map.md)
- [`Docs/android-compose-structure.md`](/Users/robert/Documents/Projects/androdex/Docs/android-compose-structure.md)
- [`Docs/remodex-port-architecture.md`](/Users/robert/Documents/Projects/androdex/Docs/remodex-port-architecture.md)
