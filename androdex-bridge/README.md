# androdex

`androdex` is the host-side CLI bridge for the Androdex project.

It is published separately from the Android app. The daemon keeps a stable host identity alive on the host machine and lets the Android client control local Codex remotely.

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
- forwards JSON-RPC traffic between the host and the Android client
- handles git and workspace actions on the host machine

## Commands

### `androdex up`

Activates the current workspace in the daemon and launches `codex app-server` locally if needed.

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
- `ANDRODEX_REFRESH_ENABLED`: enable the macOS desktop refresh workaround explicitly
- `ANDRODEX_REFRESH_DEBOUNCE_MS`: adjust refresh debounce timing
- `ANDRODEX_REFRESH_COMMAND`: override desktop refresh with a custom command

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

## Project status

This package is part of Androdex, a local-first project focused on the host-machine-plus-Android workflow today.

Credit for the upstream fork chain remains with [relaydex](https://github.com/Ranats/relaydex) and [Remodex](https://github.com/Emanuele-web04/remodex).
