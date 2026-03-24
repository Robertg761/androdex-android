# Androdex

Local-first Android remote client and host bridge for controlling Codex on your own computer.

[![npm version](https://img.shields.io/npm/v/androdex)](https://www.npmjs.com/package/androdex)
[![License: ISC](https://img.shields.io/badge/License-ISC-blue.svg)](LICENSE)

Androdex is a fork of [relaydex](https://github.com/Ranats/relaydex), which is itself a fork of [Remodex](https://github.com/Emanuele-web04/remodex), focused on one workflow:

- run local Codex on the host machine
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
- local-first host workflow: Codex, git, and file operations stay on your machine
- QR pairing and pairing-payload paste
- open existing threads and create new ones from Android
- stream Codex output on the phone while work continues on the host
- approval prompts on Android
- reconnect from a saved pairing
- model and reasoning controls on Android

## Current Status

- the host bridge is published to npm as `androdex`
- the Android app source lives in `android/`
- the repository assumes a local bridge and an explicitly configured relay, not a built-in hosted backend
- macOS host support can be added later, but it is not the current focus

If you want to work from this repo today, use the bridge from npm or source, then build the Android client from source.

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

If you want to test the Android client from source:

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

1. Install Node.js and Codex CLI on the host machine
2. Install the bridge package
3. Choose a relay path:
   - local-only testing: set `ANDRODEX_RELAY=ws://<your-host-ip>:8787/relay`
   - cross-network access: run your own public relay and set `ANDRODEX_RELAY=wss://<your-domain>/relay`
4. Run `androdex pair` once on the host and pair from the Android app
5. Run `androdex up` in the local project directory you want active
6. Open or create a thread and send a message

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
|-- CodexMobile/                  # Legacy iOS source tree kept temporarily during cleanup
|-- relay/                        # Relay implementation
`-- assets/                       # Public graphics
```

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

The bridge reads `ANDRODEX_*` environment variables.

| Variable | Description |
|----------|-------------|
| `ANDRODEX_RELAY` | Override the relay URL explicitly |
| `ANDRODEX_DEFAULT_RELAY_URL` | Provide the default hosted relay URL for managed builds |
| `ANDRODEX_CODEX_ENDPOINT` | Connect to an existing Codex WebSocket instead of spawning a local runtime |
| `ANDRODEX_REFRESH_ENABLED` | Enable the macOS desktop refresh workaround explicitly |
| `ANDRODEX_REFRESH_DEBOUNCE_MS` | Adjust refresh debounce timing |
| `ANDRODEX_CODEX_BUNDLE_ID` | Override the Codex desktop bundle ID on macOS |
| `ANDRODEX_REFRESH_COMMAND` | Override desktop refresh with a custom command |

The bridge resolves relay configuration in this order:

1. `ANDRODEX_RELAY`
2. `ANDRODEX_DEFAULT_RELAY_URL`

That means release builds can ship with a managed hosted relay, while self-host users can still override it explicitly. If you are building from source or self-hosting, set these explicitly. The public repo does not assume a hosted relay default.

Typical examples:

```sh
# Local network testing
ANDRODEX_RELAY=ws://192.168.x.x:8787/relay androdex pair

# Public relay you control
ANDRODEX_RELAY=wss://relay.example.com/relay androdex pair
```

## Self-Hosting

The public repository is meant to stay self-host friendly.

- you can run a local relay or your own hosted relay
- the relay is only a transport hop
- Codex still runs on your own machine
- if you want the phone to work away from your LAN, use a public `wss://` relay you control

If you self-host, keep private hostnames, IPs, and credentials out of the public repository.

For relay deployment details, see [relay/README.md](/G:/Projects/Androdex/relay/README.md).

## FAQ

**Does this work on Windows?**  
Yes. This fork is currently aimed at the host-machine-plus-Android workflow, with Windows as the main supported host setup today.

**Does this run Codex on the phone itself?**  
No. Codex runs on the host machine. The phone is only a paired remote client.

**What happens if I close the terminal running `androdex up`?**  
The daemon keeps running in the background. Run `androdex up` again in a project directory whenever you want to switch or reactivate the active workspace.

**How do I force a clean pairing state?**  
Run `androdex reset-pairing`, then start the bridge again with `androdex up`.

**Can I self-host the relay?**  
Yes. That is one of the intended public-repo paths.

## Security Notes

The Android client and bridge use an end-to-end encrypted session model derived from the upstream projects. Some internal protocol field names still say `mac` or `iphone`; those are implementation leftovers, not actual platform restrictions.

## Credits

Androdex is forked from [relaydex](https://github.com/Ranats/relaydex) by Ranats.

`relaydex` is itself derived from [Remodex](https://github.com/Emanuele-web04/remodex), originally created by Emanuele Di Pietro.

This repository is not the official relaydex or Remodex app and is not affiliated with or endorsed by the upstream authors.

## License

[ISC](LICENSE)
