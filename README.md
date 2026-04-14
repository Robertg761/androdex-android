# Androdex Android

Android companion app for a paired [Androdex Desktop](https://github.com/Robertg761/Androdex-Desktop) environment.

The current Android runtime is a paired WebView shell:

- pair Android with a desktop/server `/pair` URL or QR code
- store the paired origin and last opened route locally on the phone
- load the same desktop-served Androdex web app inside an Android-tuned WebView

This repo still contains the older bridge, relay, and native-client experiments, but those are now legacy/reference paths rather than the main end-to-end Android flow.

## Current End-to-End Flow

1. Start Androdex Desktop or the headless server on your computer.
2. Generate a pairing link from the desktop app's Connections settings or from the server's headless startup output.
3. Open the Android app and scan the QR code or paste the pairing URL.
4. Android validates the pairing link, saves the paired origin, and opens the paired web app in-app.

## Current UI

Desktop pairing page:

![Desktop pairing page](Docs/assets/runtime-screenshots/desktop-pairing-page.png)

Android paired thread:

![Android paired thread](Docs/assets/runtime-screenshots/android-thread.png)

Android sidebar:

![Android sidebar](Docs/assets/runtime-screenshots/android-sidebar.png)

## Build The Android App

```sh
cd android
./gradlew assembleDebug
```

The debug APK is written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Current app build facts:

- package name: `io.androdex.android`
- version: `0.2.2`
- version code: `14`

## Project Layout

- `android/`: current Android app source
- `Docs/android-mirror-shell-architecture.md`: current Android runtime architecture
- `Docs/play-store/`: Play Store notes, review guidance, and policy drafts
- `androdex-bridge/`: legacy/compatibility macOS bridge package kept in-tree
- `relay/`: legacy/compatibility relay service kept in-tree
- `desktop/`: older control-room prototype inside this repo, not the canonical desktop repo

## Legacy Components

The repo still includes the previous bridge-first architecture:

- `androdex-bridge/README.md`: bridge CLI/service docs
- `androdex-bridge/src/README.md`: bridge source guide
- `Docs/android-sync-convergence.md`
- `Docs/android-compose-structure.md`
- `Docs/android-mac-native-test-matrix.md`

Those documents are retained for reference, but they do not describe the shipped Android runtime entry anymore.

## More Docs

- [Docs Index](Docs/README.md)
- [Android App Guide](android/README.md)
- [Privacy Policy](Docs/PRIVACY_POLICY.md)
- [Play Store Release Pack](Docs/play-store/README.md)
