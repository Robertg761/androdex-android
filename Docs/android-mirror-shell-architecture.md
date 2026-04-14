# Android Mirror-Shell Architecture

This document describes the current Android runtime that ships from this repo.

## Summary

Android is now a paired shell for the desktop-served Androdex web app.

The active path is:

1. The desktop/server issues a pairing URL such as `https://host/pair#token=...`.
2. Android scans or pastes that URL, or receives the wrapped deep link form `androdex://pair?payload=...`.
3. Android stores the paired origin plus the last opened in-app URL.
4. Android opens the paired Androdex web app inside a WebView and keeps navigation constrained to the paired origin.

Android no longer boots the older native thread list, timeline, and transport stack from `MainActivity`.

## Active Entry Points

- `android/app/src/main/java/io/androdex/android/MainActivity.kt`
  - boots `MirrorShellViewModel`
- `android/app/src/main/java/io/androdex/android/MirrorShellViewModel.kt`
  - owns pairing-link intake, persisted state, notification/deep-link routing, and last-opened URL tracking
- `android/app/src/main/java/io/androdex/android/MirrorShellApp.kt`
  - switches between the pairing screen and the paired WebView shell
- `android/app/src/main/java/io/androdex/android/pairing/MirrorPairingScreen.kt`
  - manual pairing-link input and QR scanner entry
- `android/app/src/main/java/io/androdex/android/pairing/PairingLinkParser.kt`
  - validates accepted pairing-link formats and same-origin navigation rules
- `android/app/src/main/java/io/androdex/android/web/MirrorWebShell.kt`
  - hosts the paired web app and adds Android-specific affordances
- `android/app/src/main/java/io/androdex/android/persistence/MirrorShellStore.kt`
  - persists `pairedOrigin`, `displayLabel`, optional bootstrap URL, and `lastOpenedUrl`

## What Android Still Owns

- QR scanning and manual pairing-link intake
- local persistence for paired origin and last-opened route
- Android-safe WebView configuration
- Android-native file chooser and camera handoff for web uploads
- top-bar chrome, clear-pairing action, and back handling
- notification/deep-link reopen routing back into the paired environment

## What The Desktop/Server Owns

- authentication and pairing-token issuance
- thread, session, and orchestration state
- settings, pairing-link management, and QR presentation
- the actual Androdex app UI that Android mirrors

## Android-Specific WebView Behavior

`MirrorWebShell.kt` adds the mobile-specific behavior that keeps the paired web app usable on Android:

- same-origin allowlist enforcement for in-app navigation
- Android user agent tag for web-side render tuning
- file-picker and camera integration for attachment uploads
- sidebar open helpers and mobile thread-tap bridging
- page-load timeout and recoverable load-error messaging
- persisted reopen into the last non-`/pair` route

## Legacy Code Still In Tree

The repo still contains the older native-client and bridge-first implementation:

- `AndrodexApp.kt`
- `MainViewModel.kt`
- `data/`
- `service/`
- `transport/macnative/`
- `ui/`
- `androdex-bridge/`
- `relay/`

Those paths are useful reference and compatibility material, but they are not the current app entry described in this document.
