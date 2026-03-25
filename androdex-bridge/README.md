# androdex

`androdex` is the host-side CLI bridge for the Androdex project.

It is published separately from the Android app. The daemon keeps a stable host identity alive on the host machine and lets the Android client control that host-local Codex runtime remotely through a relay-backed session.

## Install

```sh
npm install -g androdex
```

## Usage

```sh
androdex pair
androdex up
androdex daemon status
androdex reset-pairing
androdex resume
androdex watch
```

## What it does

- keeps a durable relay presence keyed by a stable host id
- prints a pairing QR code and raw pairing payload on demand
- activates local `codex app-server` for the current workspace
- lets the Android client browse host folders and switch workspaces remotely
- forwards JSON-RPC traffic between the host and the Android client
- handles git and workspace actions on the host machine
- keeps the local Codex desktop app aligned with phone-authored thread activity when desktop refresh is enabled

## Commands

### `androdex up`

Activates the current workspace in the daemon and launches `codex app-server` locally if needed.

Android can also switch workspaces remotely after pairing, but `androdex up` remains the quickest host-side shortcut.

### `androdex pair`

Prints a fresh pairing QR and raw pairing payload for the already-running daemon identity.

### `androdex daemon [start|stop|status]`

Manages the background daemon that owns the stable host identity and relay presence.

### `androdex reset-pairing`

Clears the saved trusted-device state so the next `androdex pair` starts a fresh pairing flow.

### `androdex resume`

Reopens the last active thread in the local Codex desktop app if available.

### `androdex watch [threadId]`

Tails the rollout log for the selected thread in real time.

## Environment variables

`androdex` accepts `ANDRODEX_*` variables.

Useful variables:

- `ANDRODEX_RELAY`: set the relay URL explicitly and override any packaged default
- `ANDRODEX_DEFAULT_RELAY_URL`: set the default relay URL for managed builds when no explicit override is present
- `ANDRODEX_CODEX_ENDPOINT`: connect to an existing Codex WebSocket instead of spawning a local runtime
- `ANDRODEX_REFRESH_ENABLED`: enable or disable desktop refresh explicitly. Windows defaults on, macOS stays opt-in
- `ANDRODEX_REFRESH_DEBOUNCE_MS`: adjust refresh debounce timing
- `ANDRODEX_REFRESH_COMMAND`: override desktop refresh with a custom command

Windows refresh notes:

- the bridge targets the installed Codex desktop executable directly instead of trusting the raw `codex://...` protocol handler
- this avoids opening the wrong Codex build if the machine has a stale or developer protocol registration
- repeated phone activity on the same already-open thread may trigger a stronger refresh or relaunch workaround so the desktop transcript catches up

The bridge resolves relay configuration in this order:

1. `ANDRODEX_RELAY`
2. `ANDRODEX_DEFAULT_RELAY_URL`

That lets release builds ship with a managed relay while still preserving self-host overrides.

Common relay patterns:

```sh
# Local relay on your own network
ANDRODEX_RELAY=ws://192.168.x.x:8787/relay androdex daemon start

# Public relay you control
ANDRODEX_RELAY=wss://relay.example.com/relay androdex daemon start
```

If you change relay URLs after pairing, run `androdex reset-pairing` and pair again so the mobile client gets a fresh payload for the new host identity and relay.

## Source builds

If you cloned the repository and want to run the bridge from source:

```sh
cd androdex-bridge
npm install
npm start
```

## Manual Smoke Checklist

Use this after bridge changes to confirm the host runtime still behaves on Windows and macOS.

1. Run `androdex pair` and confirm the relay pairing flow still succeeds.
2. Run `androdex up`, pick a workspace, and confirm the host keeps Codex bound to that local project.
3. From Android, open an existing thread and create a new one to confirm the remote client flow still works end to end.
4. If desktop refresh is enabled, verify phone-authored thread activity updates the host Codex desktop as expected.
5. Restart the daemon or reconnect the phone and confirm the saved pairing and active workspace recover without losing host-local state.

## Project status

This package is part of Androdex, a host-local Codex plus Android remote-access workflow.

Credit for the upstream fork chain remains with [relaydex](https://github.com/Ranats/relaydex) and [Remodex](https://github.com/Emanuele-web04/remodex).
