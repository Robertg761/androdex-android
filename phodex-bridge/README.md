# relaydex

`relaydex` is the host-side CLI bridge for the Relaydex project.

It is intended to be published to npm as a separate package from the Android app. The bridge keeps Codex running locally on the host machine and lets a paired mobile client control it remotely.

## Install

```sh
npm install -g relaydex
```

## Usage

```sh
relaydex up
relaydex resume
relaydex watch
```

## What it does

- starts local `codex app-server`
- connects to the relay session
- prints a pairing QR code and raw pairing payload
- forwards JSON-RPC traffic between the host and the mobile client
- handles git and workspace actions on the host machine

## Project status

This package is part of Relaydex, an independent fork of Remodex focused on the Windows host + Android client workflow.

It is not the official Remodex package.
