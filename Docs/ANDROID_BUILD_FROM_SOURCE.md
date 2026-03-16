# Build Android From Source

Use this if you want to try the Android client before the official Play release is broadly available.

## Requirements

- Android Studio, or
- JDK 17 + Android SDK + Gradle wrapper support

Recommended:

- Android Studio on Windows
- a physical Android device with developer mode enabled

## Project

The Android app lives in:

```text
android/
```

Package name:

```text
io.relaydex.android
```

## Quick path with Android Studio

1. Open `android/` in Android Studio
2. Let Gradle sync finish
3. Connect your Android device or start an emulator
4. Run the `app` configuration

For release-style local testing, you can also build an APK from Android Studio and install it manually.

## Quick path with Gradle

From the repository root:

```sh
cd android
gradlew assembleDebug
```

The debug APK is typically written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## First local test flow

1. Install the APK on your Android device
2. On your Windows host, install the bridge:

```sh
npm install -g relaydex
```

3. In the local project folder you want Codex to work on, run:

```sh
relaydex up
```

4. Open the Android app
5. Scan the QR code or paste the pairing payload
6. Open or create a thread
7. Send a short message and confirm that the response streams back

## Notes

- Codex runs on your Windows host, not on the phone itself
- the Android app is only the remote client
- the default flow still depends on the compatible relay used by this project

## Feedback

If you hit a bug while testing a source build, please report it here:

- `https://github.com/Ranats/relaydex/issues/new?template=closed-test-feedback.yml`
