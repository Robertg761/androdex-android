# Androdex

[![npm version](https://img.shields.io/npm/v/androdex)](https://www.npmjs.com/package/androdex)
[![License: ISC](https://img.shields.io/badge/License-ISC-blue.svg)](LICENSE)

Androdex is a fork of [relaydex](https://github.com/Ranats/relaydex), which is itself a fork of [Remodex](https://github.com/Emanuele-web04/remodex), focused on one workflow:

- run local Codex on Windows
- pair the phone once with `androdex pair`
- activate the current local workspace with `androdex up`
- control that local Codex session from Android

Androdex is local-first. Codex keeps running on your host computer, while the phone acts as a paired remote control client over a secure session.

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

Androdex does **not** run Codex on the phone itself.

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

- Windows host bridge is published to npm as `androdex`
- the Android app source is public in this repository
- the public Google Play release is still being prepared

If you want to try the Android client now, build it from source from the `android/` directory.

## Install the Bridge

```sh
npm install -g androdex@latest
```

Start the pairing daemon and print a QR code:

```sh
androdex pair
```

Then run the workspace you want Codex to use:

```sh
androdex up
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
3. Run `androdex pair` once on the host and pair from Android
4. Run `androdex up` in the local project directory you want active
5. Open or create a thread and send a message

## Commands

### `androdex up`

Activates the current local project in the daemon and launches `codex app-server` there if needed.

### `androdex pair`

Asks the daemon for a fresh pairing QR code and pairing payload without changing the current workspace.

### `androdex daemon [start|stop|status]`

Manages the background daemon that keeps the stable host identity and relay presence alive.

### `androdex reset-pairing`

Clears the saved trusted-device state so the next `androdex pair` starts a fresh pairing flow.

### `androdex resume`

Reopens the last active thread in the local Codex desktop app if available.

### `androdex watch [threadId]`

Tails the rollout log for a thread in real time.

## Architecture

```text
[Android client]
        <-> paired relay WebSocket session keyed by hostId <->
[androdex daemon on host computer]
        <-> stdin/stdout JSON-RPC <->
[codex app-server]
```

The desktop Codex app can still read persisted sessions from `~/.codex/sessions` when available.

## Project Structure

```text
androdex/
|-- androdex-bridge/              # CLI bridge package
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

Right now the public path is to build the Android app from source and test it yourself.

If you want to be ready for the later Play rollout, you can also search for `androdex-android-testers` in Google Groups and join from there.

- [Google Groups search entry point](https://groups.google.com/)

The actual Play opt-in and install step will be shared later.

## Feedback

If you tried a source build and hit a bug, pairing issue, reconnect problem, or UI confusion:

- GitHub Issues: `https://github.com/Robertg761/androdex/issues`

Helpful details:

- device model and Android version
- whether pairing used QR, payload, or reconnect
- exact steps to reproduce
- expected result
- actual result
- screenshots or logs if available

## Environment Variables

The bridge accepts `ANDRODEX_*` names, the legacy `RELAYDEX_*` names, and the older `REMODEX_*` names.

| Variable | Description |
|----------|-------------|
| `ANDRODEX_RELAY` | Override the relay URL |
| `ANDRODEX_CODEX_ENDPOINT` | Connect to an existing Codex WebSocket instead of spawning a local runtime |
| `ANDRODEX_REFRESH_ENABLED` | Enable the macOS desktop refresh workaround explicitly |
| `ANDRODEX_REFRESH_DEBOUNCE_MS` | Adjust refresh debounce timing |
| `ANDRODEX_CODEX_BUNDLE_ID` | Override the Codex desktop bundle ID on macOS |

If you are building from source or self-hosting, set these explicitly. The public repo does not assume a hosted relay default.

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

**What happens if I close the terminal running `androdex up`?**  
The bridge stops. Start it again to create a new live session.

**How do I force a clean pairing state?**  
Run `androdex reset-pairing`, then start the bridge again with `androdex up`.

**Can I self-host the relay?**  
Yes. That is one of the intended public-repo paths.

## Security Notes

The mobile client and bridge use an end-to-end encrypted session model derived from the upstream projects. Some internal protocol field names still say `mac` or `iphone`; those are implementation leftovers, not actual platform restrictions.

## Credits

Androdex is forked from [relaydex](https://github.com/Ranats/relaydex) by Ranats.

`relaydex` is itself derived from [Remodex](https://github.com/Emanuele-web04/remodex), originally created by Emanuele Di Pietro.

This repository is not the official relaydex or Remodex app and is not affiliated with or endorsed by the upstream authors.

## License

[ISC](LICENSE)
