# Relay

This folder contains the thin WebSocket relay used by the durable Relaydex pairing flow.

In production, the default hosted relay runs on my VPS. If you want, you can inspect this code, fork it, and host the same relay yourself.

## What It Does

- accepts WebSocket connections at `/relay/{hostId}`
- pairs one host daemon with one live mobile client for a host id
- forwards secure control messages and encrypted payloads between the host and the mobile client
- logs only connection metadata and payload sizes, not plaintext prompts or responses
- exposes lightweight stats for a health endpoint

## What It Does Not Do

- it does not run Codex
- it does not execute git commands
- it does not contain your repository checkout
- it does not persist the local workspace on the server

Codex, git, and local file operations still run on the user's host computer.
The relay is intentionally blind to Remodex application contents once the secure handshake completes.

## Security Model

Relaydex uses the relay as a transport hop, not as a trusted application server.

- The pairing QR gives the mobile client the host identity public key plus a short-lived bootstrap token.
- The mobile client and bridge perform a signed handshake, derive shared AES-256-GCM keys with X25519 + HKDF-SHA256, and then encrypt application payloads end to end.
- The relay can still observe connection metadata and the plaintext secure control messages needed to establish the encrypted session.
- The relay does not receive plaintext Relaydex application payloads after the secure session is active.

## Protocol Notes

- path: `/relay/{hostId}`
- required header: `x-role: mac` or `x-role: iphone`
- close code `4000`: invalid session or role
- close code `4001`: previous Mac connection replaced
- close code `4002`: host unavailable / Mac disconnected
- close code `4003`: previous iPhone connection replaced

## Usage

`relay.js` exports:

- `setupRelay(wss)`
- `getRelayStats()`

It is meant to be attached to a `ws` `WebSocketServer` from your own HTTP server.
