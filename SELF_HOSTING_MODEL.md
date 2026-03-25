# Public Repo and Self-Hosting

This file explains what the public Androdex repository is for, what it includes, and what it does not include.

If you cloned Androdex from GitHub, the intended path is to run Codex on your own host machine and connect to it remotely through relay infrastructure you control or configure.

## What the Public Repo Includes

The public repository includes:

- the host bridge
- the Android app source code
- the public relay code
- relay configuration and self-hosting documentation

The public repository is meant to be usable without private hosted dependencies baked into the source tree.

## What the Public Repo Does Not Include

The public repository does not include:

- private production relay URLs
- private Google Play defaults
- private npm publish-time defaults
- private notification credentials
- private Android push service endpoints or FCM secrets
- private deployment secrets

If you are running from source, assume you should provide your own relay setup or use the defaults exposed by the public bridge package.

## The Self-Hosting Path

If you use the public repo, expect one of these flows:

1. local LAN pairing on your own machine
2. a self-hosted relay on your own VPS, passed in through `ANDRODEX_RELAY`

That means:

- Codex still runs on your host machine
- git commands still run on your host machine
- the Android phone is still a paired remote client
- the relay is only the transport layer

The practical split is:

- local-only workflow: use a local relay URL on your LAN for testing
- cross-network workflow: run a public `wss://` relay on infrastructure you control
- managed builds: inject `ANDRODEX_DEFAULT_RELAY_URL` at build or release time, while still honoring `ANDRODEX_RELAY`

For relay details, read [relay/README.md](G:\Projects\Androdex\relay\README.md).

## Why the Repo Stays Generic

The public repo stays generic on purpose.

That keeps the self-host path honest:

- people can inspect the transport and pairing code
- people can run Codex on their own host
- people can self-host their own relay
- people are not silently tied to someone else's hosted infrastructure

## What to Keep Private

If you fork or self-host Androdex, keep these things out of the public repo:

- your deployed hostname
- your VPS IP addresses
- any APNs or notification credentials
- any Android push service credentials or FCM server keys
- any private build overrides
- any publish-time package defaults

If you ship managed builds for end users, prefer injecting the hosted relay URL at build or release time through `ANDRODEX_DEFAULT_RELAY_URL`, while continuing to honor explicit self-host overrides through `ANDRODEX_RELAY`.

If you offer Android completion notifications, inject the bridge-facing push endpoint through `ANDRODEX_PUSH_SERVICE_URL` in your own environment or release pipeline. Keep the hosted service, credentials, and routing secrets out of the public repo.

Those belong in your own environment, private config, or release pipeline.

## Public Relay Checklist

If you want pairing to work when the phone is not on the same network as the host, your relay needs:

- a public DNS name you control
- TLS, so the app can use `wss://`
- ports `80` and `443` reachable from the internet if you use ACME/Let's Encrypt on the box
- the bridge configured with that relay URL before pairing

At the end, the bridge should pair against a URL shaped like:

```text
wss://your-relay.example/relay
```
