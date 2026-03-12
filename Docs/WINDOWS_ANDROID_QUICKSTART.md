# Windows + Android Quick Start

This fork supports the workflow: run a local Codex bridge on Windows, then control it from Android with Relaydex.

## 1. Install the host-side prerequisites on Windows

- Install Node.js 18+
- Install the [Codex CLI](https://github.com/openai/codex) and make sure `codex` works in `cmd.exe` or PowerShell
- Install the bridge package:

```sh
npm install -g relaydex
```

## 2. Start the bridge on the Windows machine

Open the project directory you want Codex to work on, then run:

```sh
relaydex up
```

What should happen:

- The bridge starts a local `codex app-server`
- A pairing QR code appears in the terminal
- The terminal also prints the raw pairing payload JSON below the QR

On Windows, this fork launches Codex through `cmd.exe /d /c codex app-server`, so the standard Codex CLI install works without extra wrappers.

## 3. Connect from Android

Build and install the Android app from the `android/` directory, then:

- Open the Android app
- Scan the pairing QR code
- Or paste the raw pairing payload from the Windows terminal

After pairing succeeds, the Android client can:

- list threads
- open existing threads
- create new threads
- send prompts
- receive streaming Codex output
- answer approval requests

## 4. Verify the setup

After connection:

- the Android app should show `CONNECTED`
- the thread list should load
- sending a message from Android should create or continue a local Codex thread on Windows

## Notes

- The Codex runtime stays on the Windows machine. Android is only the remote control client.
- Git and workspace actions run on the Windows host through the bridge.
- Desktop refresh remains macOS-only. This does not block the main Windows + Android remote-control flow.
- The hosted relay is still required unless you self-host a compatible relay and point `RELAYDEX_RELAY` at it.
