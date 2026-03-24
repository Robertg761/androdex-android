<p align="center">
  <img src="assets/feature-graphic-1024x500.png" alt="Relaydex banner" />
</p>

# Relaydex

[![npm version](https://img.shields.io/npm/v/relaydex)](https://www.npmjs.com/package/relaydex)
[![License: ISC](https://img.shields.io/badge/License-ISC-blue.svg)](LICENSE)
[Follow on X](https://x.com/Ranats85)

[Japanese README](README.ja.md)

Relaydex is an independent fork of [Remodex](https://github.com/Emanuele-web04/remodex) focused on one workflow:

- run local Codex on Windows
- pair the phone once with `relaydex pair`
- activate the current local workspace with `relaydex up`
- control that local Codex session from Android

Relaydex is local-first. Codex keeps running on your host computer, while the phone acts as a paired remote control client over a secure session.

## Contents

- [What It Is](#what-it-is)
- [Key Features](#key-features)
- [Current Status](#current-status)
- [Install the Bridge](#install-the-bridge)
- [Build the Android App From Source](#build-the-android-app-from-source)
- [Quick Start](#quick-start)
- [Commands](#commands)
- [Architecture](#architecture)
- [Project Structure](#project-structure)
- [Android Availability](#android-availability)
- [Feedback](#feedback)
- [Environment Variables](#environment-variables)
- [Self-Hosting](#self-hosting)
- [FAQ](#faq)
- [Security Notes](#security-notes)
- [Credits](#credits)
- [License](#license)

## What It Is

Relaydex does **not** run Codex on the phone itself.

- the host computer runs the local bridge and local Codex runtime
- the phone acts as a paired remote control client
- git and workspace actions still execute on the host machine

## Key Features

- end-to-end encrypted pairing between the Android client and the host bridge
- local-first host workflow: Codex, git, and file operations stay on your Windows machine
- QR pairing and pairing-payload paste
- open existing threads and create new ones from Android
- stream Codex output on the phone while work continues on the host
- approval prompts on Android
- reconnect from a saved pairing
- model and reasoning controls on Android

## Current Status

- Windows host bridge is published to npm as `relaydex`
- the Android app source is public in this repository
- the public Google Play release is still being prepared

If you want to try the Android client now, build it from source from the `android/` directory.

## Install the Bridge

```sh
npm install -g relaydex@latest
```

Start the pairing daemon and print a QR code:

```sh
relaydex pair
```

Then run the workspace you want Codex to use:

```sh
relaydex up
```

Then open the Android app and scan the pairing QR code.

## Build the Android App From Source

If you want to test the Android client before the public Play release:

1. Open `android/` in Android Studio
2. Let Gradle sync finish
3. Connect an Android device or start an emulator
4. Run the `app` configuration

CLI build path:

```sh
cd android
gradlew assembleDebug
```

The debug APK is typically written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Quick Start

1. Install Node.js and Codex CLI on Windows
2. Install the bridge package
3. Run `relaydex pair` once on the host and pair from Android
4. Run `relaydex up` in the local project directory you want active
5. Open or create a thread and send a message

## Commands

### `relaydex up`

Activates the current local project in the daemon and launches `codex app-server` there if needed.

### `relaydex pair`

Asks the daemon for a fresh pairing QR code and pairing payload without changing the current workspace.

### `relaydex daemon [start|stop|status]`

Manages the background daemon that keeps the stable host identity and relay presence alive.

### `relaydex reset-pairing`

Clears the saved trusted-device state so the next `relaydex pair` starts a fresh pairing flow.

### `relaydex resume`

Reopens the last active thread in the local Codex desktop app if available.

### `relaydex watch [threadId]`

Tails the rollout log for a thread in real time.

## Architecture

```text
[Android client]
        <-> paired relay WebSocket session keyed by hostId <->
[relaydex daemon on host computer]
        <-> stdin/stdout JSON-RPC <->
[codex app-server]
```

The desktop Codex app can still read persisted sessions from `~/.codex/sessions` when available.

## Project Structure

```text
remodex/
|-- phodex-bridge/                # CLI bridge package
|   |-- bin/                      # CLI entrypoints
|   `-- src/                      # Bridge runtime and handlers
|-- android/                      # Android Studio project
|   `-- app/                      # Kotlin + Compose Android client
|-- CodexMobile/                  # Upstream iOS source tree kept for protocol reference
|-- relay/                        # Relay implementation
`-- assets/                       # Public graphics
```

## Android Availability

The Android app is not yet broadly released on Google Play.

Right now there are two public paths:

1. build the Android app from source and test it yourself
2. join the waitlist for the later Play rollout

Recommended waitlist page:

- [Waitlist page in English](https://ranats.github.io/relaydex/waitlist.html)
- [Waitlist page in Japanese](https://ranats.github.io/relaydex/waitlist-ja.html)

If you want to be ready for the later Play rollout, you can also search for `relaydex-android-testers` in Google Groups and join from there.

- [Google Groups search entry point](https://groups.google.com/)

The actual Play opt-in and install step will be shared later.

## Feedback

If you tried a source build and hit a bug, pairing issue, reconnect problem, or UI confusion:

- GitHub Issues: `https://github.com/Ranats/relaydex/issues`
- X: `https://x.com/Ranats85`

Helpful details:

- device model and Android version
- whether pairing used QR, payload, or reconnect
- exact steps to reproduce
- expected result
- actual result
- screenshots or logs if available

## Environment Variables

The bridge accepts both `RELAYDEX_*` names and legacy `REMODEX_*` names.

| Variable | Description |
|----------|-------------|
| `RELAYDEX_RELAY` | Override the relay URL |
| `RELAYDEX_CODEX_ENDPOINT` | Connect to an existing Codex WebSocket instead of spawning a local runtime |
| `RELAYDEX_REFRESH_ENABLED` | Enable the macOS desktop refresh workaround explicitly |
| `RELAYDEX_REFRESH_DEBOUNCE_MS` | Adjust refresh debounce timing |
| `RELAYDEX_CODEX_BUNDLE_ID` | Override the Codex desktop bundle ID on macOS |

If you are building from source or self-hosting, set these explicitly instead of assuming hosted defaults.

## Self-Hosting

The public repository is meant to stay self-host friendly.

- you can run a local relay or your own hosted relay
- the relay is only a transport hop
- Codex still runs on your own machine

If you self-host, keep private hostnames, IPs, and credentials out of the public repository.

## FAQ

**Does this work on Windows?**  
Yes. This fork is specifically focused on the Windows host + Android workflow.

**Does this run Codex on the phone itself?**  
No. Codex runs on the host machine. The phone is only a paired remote client.

**What happens if I close the terminal running `relaydex up`?**  
The bridge stops. Start it again to create a new live session.

**How do I force a clean pairing state?**  
Run `relaydex reset-pairing`, then start the bridge again with `relaydex up`.

**Can I self-host the relay?**  
Yes. That is one of the intended public-repo paths.

## Security Notes

The mobile client and bridge use the same end-to-end encrypted session model as upstream Remodex. This fork keeps wire-level compatibility where practical, so some internal field names still say `mac` or `iphone`. Those are protocol leftovers, not actual platform restrictions.

## Credits

Relaydex is an independent fork of [Remodex](https://github.com/Emanuele-web04/remodex), originally created by Emanuele Di Pietro.

This repository is not the official Remodex app and is not affiliated with or endorsed by the upstream author.

## License

[ISC](LICENSE)
