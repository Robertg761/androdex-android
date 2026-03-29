# Androdex

Android remote client and host bridge for controlling Codex running on your own computer.

[![npm version](https://img.shields.io/npm/v/androdex)](https://www.npmjs.com/package/androdex)
[![License: ISC](https://img.shields.io/badge/License-ISC-blue.svg)](LICENSE)

Androdex is a fork of [relaydex](https://github.com/Ranats/relaydex), which is itself a fork of [Remodex](https://github.com/Emanuele-web04/remodex), focused on one workflow:

- run local Codex on the host machine
- pair the phone once with `androdex pair`
- optionally activate the current local workspace with `androdex up`
- control that local Codex session from Android

Androdex keeps Codex running on your host computer, while the phone connects as a paired remote control client over a secure session that can work across networks through a relay.

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
- [Remote Access](#remote-access)
- [FAQ](#faq)
- [Security Notes](#security-notes)
- [Credits](#credits)
- [License](#license)

## What It Is

Androdex does **not** run Codex on the phone itself.

- the host computer runs the bridge and local Codex runtime
- the phone acts as a paired remote control client
- git and workspace actions still execute on the host machine

## Key Features

- end-to-end encrypted pairing between the Android client and the host bridge
- remote-capable access to a host-local Codex runtime through a relay
- QR pairing and pairing-payload paste
- open existing threads and create new ones from Android
- stream Codex output on the phone while work continues on the host
- item-scoped timeline reconciliation with per-thread running, ready, and failed state on Android
- reconnect-safe rollout mirroring for desktop-started runs reopened on Android
- Stop-button recovery that falls back to `thread/read` when a live turn has not published a usable `turnId`
- queued follow-up prompts on Android with per-thread drafts, pause/resume, restore-to-composer, and idle-time auto flush
- Android composer plan mode with Remodex-style `collaborationMode` turn payloads and queued-send preservation
- Android composer `@file`, `$skill`, and `/subagents` affordances with autocomplete chips and structured skill turn payloads
- Android `/review` composer flow with review target selection and host-side review start routing
- Android photo attachments from camera and gallery with Remodex-style intake limits, preview tiles, loading and failure states, queued-draft restore, and in-memory cleanup after send or removal
- Android-native git workflows for host-side status, diff summary, commit, push, pull, branch switch/create, and managed worktree actions with Remodex-style safety prompts
- approval prompts on Android
- reconnect from a saved pairing
- model, access mode, service-tier, and per-thread runtime controls on Android
- native thread fork actions on Android when the host bridge/runtime supports them
- native thread maintenance actions on Android for context compaction, rollback, and background-terminal cleanup when the host bridge/runtime supports them
- optional bridge-side Android push registration and run-completion forwarding

## Current Status

- the host bridge is published to npm as `androdex`
- the Android app source lives in `android/`
- the repository is built around a host-local Codex runtime plus relay-backed remote access

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

Then open the Android app, scan the pairing QR code, and switch projects from the app when needed.

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
2. Install the bridge package on the host machine
3. The bridge uses `wss://relay.androdex.xyz/relay` by default. Override it only if you want a different relay path:
   - local-only testing: set `ANDRODEX_RELAY=ws://<your-host-ip>:8787/relay`
   - self-hosted cross-network access: set `ANDRODEX_RELAY=wss://<your-domain>/relay`
4. Run `androdex pair` once on the host and pair from the Android app
5. Optionally run `androdex up` in a local project directory if you want to seed the first active workspace from the host
6. Open the Android app, choose a project, then open or create a thread and send a message

## Manual Smoke Checklist

Use this after host-side changes to make sure Windows and macOS still behave the same way where they should.

1. Run `androdex pair` and confirm the phone can pair successfully.
2. Run `androdex up`, choose a workspace, and confirm the host-local Codex session opens in that project.
3. From Android, open an existing thread and create a new thread to confirm remote control is still working.
4. If desktop refresh is enabled, verify phone-authored activity brings the host Codex desktop to the right thread.
5. While a run is active on Android, confirm `Stop`, plan mode, queued follow-ups, restore-to-composer, and queue pause/resume all behave correctly and that queued drafts flush in order once the run goes idle.
6. On an idle thread with history, confirm Android thread maintenance actions can compact context, roll back the last turn, and clean background terminals when the connected host advertises support. `thread/shellCommand` is still intentionally not exposed on Android.
7. Attach photos from the camera and gallery, confirm the 4-image limit, verify loading and failure tiles behave correctly, and confirm restored queued drafts keep their previews.
8. Restart the daemon or reconnect the phone and confirm the saved pairing, active workspace, and active-run stop state recover cleanly.

## Commands

### `androdex up`

Activates the current local project in the daemon and launches `codex app-server` there if needed.

You can still use this as the fastest host-side shortcut, but Android can also switch projects remotely after pairing.

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

On Windows, the bridge also includes a desktop refresh workaround for phone-authored activity. It targets the installed Codex desktop executable directly instead of falling back to the raw `codex://...` protocol handler, because a misregistered protocol handler can open the wrong Codex build and break live thread sync.

## Project Structure

```text
androdex/
|-- androdex-bridge/              # CLI bridge package
|   |-- bin/                      # CLI entrypoints
|   `-- src/                      # Bridge runtime and handlers
|-- android/                      # Android Studio project
|   `-- app/                      # Kotlin + Compose Android client
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
| `ANDRODEX_DEFAULT_RELAY_URL` | Override the built-in hosted relay fallback without replacing `ANDRODEX_RELAY` priority |
| `ANDRODEX_CODEX_ENDPOINT` | Connect to an existing Codex WebSocket instead of spawning a local runtime |
| `ANDRODEX_REFRESH_ENABLED` | Enable or disable desktop refresh explicitly. Windows and macOS default on |
| `ANDRODEX_REFRESH_DEBOUNCE_MS` | Adjust refresh debounce timing |
| `ANDRODEX_CODEX_BUNDLE_ID` | Override the Codex desktop bundle ID on macOS |
| `ANDRODEX_REFRESH_COMMAND` | Override desktop refresh with a custom command |
| `ANDRODEX_PUSH_SERVICE_URL` | Optional Android push service endpoint for device registration and completion notifications |
| `ANDRODEX_ENABLE_PUSH_SERVICE` | Enable the public relay-side push helper that forwards completions to a webhook you control |
| `ANDRODEX_PUSH_WEBHOOK_URL` | Webhook target used by the relay-side push helper for completion fan-out |
| `ANDRODEX_PUSH_WEBHOOK_TOKEN` | Optional bearer token for the relay-side push webhook |
| `ANDRODEX_PUSH_WEBHOOK_PATH` | Optional relay-side push webhook path override |
| `ANDRODEX_PUSH_WEBHOOK_TIMEOUT_MS` | Timeout for relay-side push webhook delivery |
| `ANDRODEX_PUSH_STATE_FILE` | Override the relay-side persisted push-state file |
| `ANDRODEX_PUSH_PREVIEW_MAX_CHARS` | Maximum stored preview length used when building completion notification payloads |
| `ANDRODEX_FCM_APPLICATION_ID` | Optional Android FCM app ID used for direct token registration in source builds |
| `ANDRODEX_FCM_PROJECT_ID` | Optional Android FCM project ID used for direct token registration in source builds |
| `ANDRODEX_FCM_API_KEY` | Optional Android FCM Web API key used for direct token registration in source builds |
| `ANDRODEX_FCM_GCM_SENDER_ID` | Optional Android FCM sender ID used for direct token registration in source builds |

The bridge resolves relay configuration in this order:

1. `ANDRODEX_RELAY`
2. `ANDRODEX_DEFAULT_RELAY_URL`
3. `wss://relay.androdex.xyz/relay`

That means the public hosted relay works out of the box, while self-host users can still override it explicitly.

Typical examples:

```sh
# Built-in public relay
androdex pair

# Local network testing
ANDRODEX_RELAY=ws://192.168.x.x:8787/relay androdex pair

# Public relay you control
ANDRODEX_RELAY=wss://relay.example.com/relay androdex pair
```

### Android Push Environment

If you want completion notifications while the Android app is backgrounded or temporarily disconnected, configure a push service the bridge can call after the phone registers.

- Androdex keeps this bridge-side and platform-neutral: the bridge only forwards `notifications/push/register` requests and completion events to your configured service.
- The public relay can optionally host a generic push helper that stores Android registrations and forwards completion payloads to a webhook you control.
- Use Android/FCM-oriented identifiers and routing in that webhook-backed service. The public repo does not ship private FCM credentials, hosted endpoints, or deployment runbooks.
- Set `ANDRODEX_PUSH_SERVICE_URL` on the host before pairing or reconnecting the phone so registration requests have a destination.
- If you want the bundled relay helper, set `ANDRODEX_ENABLE_PUSH_SERVICE=true` and `ANDRODEX_PUSH_WEBHOOK_URL=https://...` on the host before starting `relay/server.js` or `cd relay && npm start`.
- If you build the Android app from source and want device-token registration, provide your own FCM config through `ANDRODEX_FCM_APPLICATION_ID`, `ANDRODEX_FCM_PROJECT_ID`, `ANDRODEX_FCM_API_KEY`, and `ANDRODEX_FCM_GCM_SENDER_ID`.
- Android now mirrors Remodex-style recovery behavior: completion taps reopen the targeted thread when it can be recovered, keep the target pending across reconnect/background restore, and show a missing-thread prompt instead of silently dropping the tap.
- `ANDRODEX_PUSH_PREVIEW_MAX_CHARS` only affects the bridge-side completion preview cache; it does not change Android UI rendering.
- Apple-specific launchd or APNs host setup from Remodex is intentionally not part of Androdex.
- The bundled relay helper is intentionally generic and still expects you to point it at your own notification webhook.

Example:

```sh
ANDRODEX_RELAY=wss://relay.example.com/relay \
ANDRODEX_PUSH_SERVICE_URL=https://push.example.com \
androdex up
```

For the bundled relay helper:

```sh
cd relay
ANDRODEX_ENABLE_PUSH_SERVICE=true \
ANDRODEX_PUSH_WEBHOOK_URL=https://your-notification-endpoint.example/webhook \
npm start
```

## Remote Access

Androdex is designed so Codex stays on the host machine while the Android client can connect from the same network or across the internet through a relay.

- you can use a local relay for LAN testing
- you can use a public `wss://` relay for cross-network access
- the bridge defaults to `wss://relay.androdex.xyz/relay`
- the relay is only a transport hop
- Codex still runs on your own machine
- if you want to use a different relay, set `ANDRODEX_RELAY` before pairing

Keep private hostnames, IPs, and credentials out of the public repository.

For relay deployment details, see [relay/README.md](/G:/Projects/Androdex/relay/README.md).

## FAQ

**Does this work on Windows?**  
Yes. This fork is currently aimed at the host-machine-plus-Android workflow, with Windows as the main supported host setup today.

**How does desktop sync work on Windows?**  
The bridge watches phone-authored thread activity and nudges the installed Codex desktop app onto the same thread. For repeated phone activity on an already-open thread, the Windows path may use a stronger refresh or relaunch workaround so the desktop transcript catches up without reopening the wrong Electron build through a stale `codex://` registration.

**Does this run Codex on the phone itself?**  
No. Codex runs on the host machine. The phone is only a paired remote client.

**What happens if I close the terminal running `androdex up`?**  
The daemon keeps running in the background. You can reactivate a project later with `androdex up`, or switch projects from the Android app once you reconnect.

**How do I force a clean pairing state?**  
Run `androdex reset-pairing`, then pair again with `androdex pair`.

**Can I self-host the relay?**  
Yes. That is one of the intended public-repo paths.

## Security Notes

The Android client and bridge use an end-to-end encrypted session model derived from the upstream projects. Some host-side protocol field names still say `mac`; those are implementation leftovers, not platform restrictions.

## Credits

Androdex is forked from [relaydex](https://github.com/Ranats/relaydex) by Ranats.

`relaydex` is itself derived from [Remodex](https://github.com/Emanuele-web04/remodex), originally created by Emanuele Di Pietro.

This repository is not the official relaydex or Remodex app and is not affiliated with or endorsed by the upstream authors.

## License

[ISC](LICENSE)
