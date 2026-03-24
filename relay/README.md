# Relay

This folder contains the thin WebSocket relay used by the durable Androdex pairing flow.

This repository does not assume a hosted relay you do not control. If you use this relay, run it on infrastructure you control and point the bridge at it explicitly.

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
The relay is intentionally blind to Androdex application contents once the secure handshake completes.

## Security Model

Androdex uses the relay as a transport hop, not as a trusted application server.

- The pairing QR gives the mobile client the host identity public key plus a short-lived bootstrap token.
- The mobile client and bridge perform a signed handshake, derive shared AES-256-GCM keys with X25519 + HKDF-SHA256, and then encrypt application payloads end to end.
- The relay can still observe connection metadata and the plaintext secure control messages needed to establish the encrypted session.
- The relay does not receive plaintext Androdex application payloads after the secure session is active.

## Protocol Notes

- path: `/relay/{hostId}`
- required header: `x-role: mac` or `x-role: iphone`
- close code `4000`: invalid session or role
- close code `4001`: previous Mac connection replaced
- close code `4002`: host unavailable / Mac disconnected
- close code `4003`: previous iPhone connection replaced

## Standalone Server

If you want a relay that works across different networks, run this folder on a VPS or other internet-reachable host and point the bridge at its public URL.

Install dependencies:

```sh
cd relay
npm install
```

Start plain WebSocket relay on port `8787`:

```sh
npm start
```

Docker build and run:

```sh
docker build -t androdex-relay ./relay
docker run --rm -p 8787:8787 androdex-relay
```

Environment variables:

- `ANDRODEX_RELAY_HOST`: bind host, default `0.0.0.0`
- `ANDRODEX_RELAY_PORT`: bind port, default `8787`
- `ANDRODEX_RELAY_TLS_CERT`: PEM certificate path to enable HTTPS/WSS
- `ANDRODEX_RELAY_TLS_KEY`: PEM private key path to enable HTTPS/WSS

Example with TLS enabled:

```sh
ANDRODEX_RELAY_TLS_CERT=/etc/letsencrypt/live/example/fullchain.pem \
ANDRODEX_RELAY_TLS_KEY=/etc/letsencrypt/live/example/privkey.pem \
ANDRODEX_RELAY_PORT=443 \
npm start
```

If you terminate TLS at a reverse proxy instead, run the relay behind Nginx, Caddy, Traefik, or a cloud load balancer and publish it as:

```text
wss://your-relay.example/relay
```

After that, set the bridge relay explicitly before pairing:

```sh
ANDRODEX_RELAY=wss://your-relay.example/relay androdex pair
```

Recommended production shape:

1. run the relay container privately on the server
2. terminate TLS at a reverse proxy such as Caddy or Nginx
3. publish a hostname you control
4. point the bridge at `wss://<your-domain>/relay`

That keeps the relay generic while letting the phone connect from any network.

Health check:

- `GET /healthz`

## Library Usage

`relay.js` exports:

- `setupRelay(wss)`
- `getRelayStats()`

It is meant to be attached to a `ws` `WebSocketServer` from your own HTTP server.
