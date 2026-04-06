# androdex

`androdex` is the macOS host bridge for the Androdex Android client.

It keeps Codex running locally on the Mac, exposes a relay-backed encrypted session for Android, and uses a launchd-managed background service so pairing and reconnect survive terminal closes.

## Install

```sh
npm install -g androdex
```

## Usage

```sh
androdex up
androdex start
androdex restart
androdex stop
androdex status
androdex reset-pairing
androdex resume
androdex watch [threadId]
```

## Command Model

- `androdex up`
  Starts or refreshes the macOS bridge service, waits for a fresh pairing QR, prints it, and binds the current working directory as the active workspace.
- `androdex start`
  Starts the launchd-managed macOS bridge service without changing the active workspace.
- `androdex restart`
  Restarts the launchd-managed macOS bridge service without changing the active workspace.
- `androdex stop`
  Stops the macOS bridge service and clears stale in-memory runtime state.
- `androdex status`
  Prints launchd status, persisted bridge status, and the stdout/stderr log paths under `~/.androdex`.
- `androdex run`
  Runs the bridge in the foreground.
- `androdex run-service`
  Internal launchd entrypoint used by the installed plist.
- `androdex reset-pairing`
  Stops the service and clears the saved trusted-device state so the next `androdex up` starts fresh.
- `androdex resume`
  Reopens the last active thread in the local Codex desktop app if available.
- `androdex watch [threadId]`
  Tails the rollout log for the selected thread in real time.

## What it does

- runs a launchd-managed macOS bridge service with label `io.androdex.bridge`
- prints a pairing QR for the current relay session
- restores the last active workspace on service start when possible
- lets the Android client browse host folders and switch workspaces remotely
- forwards JSON-RPC traffic between the host and the Android client
- handles git and workspace actions on the host machine, including branch/worktree management and reverse-patch safety checks
- keeps the local Codex desktop app aligned with phone-authored thread activity when desktop refresh is enabled

## Pairing And Trust Model

- The bridge has a durable host identity under `~/.androdex` and pairing is anchored to that identity, not just to a transient relay session.
- Initial QR pairing now registers a phone-owned recovery credential. The bridge stores only the public recovery identity needed for later verification.
- Trusted reconnect resolves a fresh live relay session for an already trusted phone instead of treating every reconnect like first-time pairing.
- Trusted recovery/rekey lets a reinstalled Android app rotate to a new phone install identity after proving possession of the existing recovery credential.
- During recovery rotation, the bridge keeps the previous recovery identity as a fallback so an interrupted rotation does not strand the device immediately.
- The bridge also replays Android `initialize` state after host-side transport restarts so thread reads do not fail with `Not initialized` just because the host session came back before the client reinitialized.

## Environment variables

`androdex` accepts `ANDRODEX_*` variables.

Useful variables:

- `ANDRODEX_RELAY`: set the relay URL explicitly and override any packaged default
- `ANDRODEX_DEFAULT_RELAY_URL`: override the built-in default relay URL when no explicit override is present
- `ANDRODEX_CODEX_ENDPOINT`: connect to an existing Codex WebSocket instead of spawning a local runtime
- `ANDRODEX_REFRESH_ENABLED`: enable or disable desktop refresh explicitly
- `ANDRODEX_REFRESH_DEBOUNCE_MS`: adjust refresh debounce timing
- `ANDRODEX_REFRESH_ROUTE_TO_THREAD`: opt into reopening the concrete `codex://threads/<id>` route during auto refresh
- `ANDRODEX_REFRESH_COMMAND`: override desktop refresh with a custom command
- `ANDRODEX_CODEX_BUNDLE_ID`: override the Codex desktop bundle ID on macOS
- `ANDRODEX_PUSH_SERVICE_URL`: optional Android push service endpoint for device registration and completion notifications

The bridge resolves relay configuration in this order:

1. `ANDRODEX_RELAY`
2. `ANDRODEX_DEFAULT_RELAY_URL`
3. `wss://relay.androdex.xyz/relay`

Common relay patterns:

```sh
# Built-in public relay
androdex up

# Local relay on your own network
ANDRODEX_RELAY=ws://192.168.x.x:8787/relay androdex up

# Public relay you control
ANDRODEX_RELAY=wss://relay.example.com/relay androdex up
```

If you change relay URLs after pairing, run `androdex reset-pairing` and pair again with `androdex up` so Android gets a fresh payload for the new relay session.

## Source builds

If you cloned the repository and want to run the bridge from source:

```sh
cd androdex-bridge
npm install
npm start
```

## Internal Structure

The bridge source is grouped by responsibility so changes are easier to place without mixing unrelated concerns:

- `src/codex/`: Codex process transport and JSON-RPC client helpers
- `src/pairing/`: QR output, trusted-device persistence, and secure bridge transport
- `src/notifications/`: push registration handling, dedupe, service client, and completion tracking
- `src/rollout/`: rollout watching and live-mirror helpers
- `src/runtime/`: runtime-provider selection and shared launch configuration
- `src/workspace/`: workspace browsing, activation, and reverse-patch workflows
- `src/`: top-level orchestration and shared helpers such as `bridge.js`, `git-handler.js`, `daemon-state.js`, and `macos-launch-agent.js`

## Release

Publish the npm package from this directory, not from the repository root:

```sh
cd androdex-bridge
npm test
npm pack --dry-run
npm version patch --no-git-tag-version
npm publish --access public
```

Verify the published version after the release:

```sh
npm view androdex version
```

If your npm account requires write-time 2FA, rerun the publish command with `--otp=<code>`.

## Manual Smoke Checklist

1. Run `androdex up` and confirm the Android app can pair successfully.
2. Run `androdex up` inside a workspace and confirm the host keeps Codex bound to that local project.
3. From Android, open an existing thread and create a new one to confirm the remote client flow still works end to end.
4. If desktop refresh is enabled, verify phone-authored thread activity refreshes the host Codex desktop without hijacking the normal thread list or workspace context.
5. Restart the launchd service or reconnect the phone and confirm the saved pairing and active workspace recover without losing host-local state.
6. Force-close the Android app from the app switcher, reopen it, and confirm trusted reconnect resolves the trusted host again instead of falling back to the repair-pairing screen or a stale live relay session id.
7. If you touch recovery logic, test a reinstall/rekey path and confirm the trusted host survives without requiring a new QR when the recovery payload is still available.

## Project status

This package is part of Androdex, a macOS host-local Codex plus Android remote-access workflow.

Credit for the upstream fork chain remains with [relaydex](https://github.com/Ranats/relaydex) and [Remodex](https://github.com/Emanuele-web04/remodex).
