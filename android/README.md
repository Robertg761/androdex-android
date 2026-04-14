# Android App Guide

This directory contains the Android Studio project for the current Androdex Android app.

## Active Runtime

The shipped runtime is the mirror-shell flow:

- Android accepts a desktop pairing URL or QR code
- stores the paired origin and last opened route
- opens the paired desktop-served web app inside an Android WebView

See [Android Mirror-Shell Architecture](../Docs/android-mirror-shell-architecture.md) for the current end-to-end shape.

## Start Here

- Active activity entrypoint: [`MainActivity.kt`](./app/src/main/java/io/androdex/android/MainActivity.kt)
- Active view-model entrypoint: [`MirrorShellViewModel.kt`](./app/src/main/java/io/androdex/android/MirrorShellViewModel.kt)
- Active app router: [`MirrorShellApp.kt`](./app/src/main/java/io/androdex/android/MirrorShellApp.kt)
- Pairing flow: [`pairing/`](./app/src/main/java/io/androdex/android/pairing)
- Paired WebView shell: [`web/`](./app/src/main/java/io/androdex/android/web)
- Persistence for paired origin and last-opened URL: [`persistence/MirrorShellStore.kt`](./app/src/main/java/io/androdex/android/persistence/MirrorShellStore.kt)

## What To Change For The Current App

- Pairing link parsing and deep-link intake:
  [`pairing/PairingLinkParser.kt`](./app/src/main/java/io/androdex/android/pairing/PairingLinkParser.kt)
- Pairing screen copy and layout:
  [`pairing/MirrorPairingScreen.kt`](./app/src/main/java/io/androdex/android/pairing/MirrorPairingScreen.kt)
- Intent handling, reopen routing, and clear-pairing behavior:
  [`MirrorShellViewModel.kt`](./app/src/main/java/io/androdex/android/MirrorShellViewModel.kt)
- WebView configuration, same-origin navigation, file upload, and top-bar controls:
  [`web/MirrorWebShell.kt`](./app/src/main/java/io/androdex/android/web/MirrorWebShell.kt)
- Local persistence:
  [`persistence/MirrorShellStore.kt`](./app/src/main/java/io/androdex/android/persistence/MirrorShellStore.kt)

## Legacy Native Stack

The older native Android client implementation is still in the tree, but it is no longer the active app entry:

- [`AndrodexApp.kt`](./app/src/main/java/io/androdex/android/AndrodexApp.kt)
- [`MainViewModel.kt`](./app/src/main/java/io/androdex/android/MainViewModel.kt)
- [`data/`](./app/src/main/java/io/androdex/android/data)
- [`service/`](./app/src/main/java/io/androdex/android/service)
- [`transport/macnative/`](./app/src/main/java/io/androdex/android/transport/macnative)
- [`ui/`](./app/src/main/java/io/androdex/android/ui)

Keep those paths clearly labeled as legacy/reference unless the app is intentionally moving back toward a native-first runtime.

## Build

```sh
cd android
./gradlew assembleDebug
```

Current build metadata:

- package name: `io.androdex.android`
- version: `0.2.2`
- version code: `14`
