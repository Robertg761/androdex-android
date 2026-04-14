# Repo Map

This document is the high-signal map of the Androdex Android repository.

## Top-Level Map

```text
Androdex - Android/
|-- android/                  # Android app source and build output
|-- androdex-bridge/          # Legacy/compatibility macOS bridge package
|-- relay/                    # Legacy/compatibility relay service
|-- desktop/                  # Older in-repo control-room prototype
|-- Docs/                     # Product docs, policy notes, screenshots, and historical references
|-- assets/                   # Shared brand and marketing graphics
|-- site/                     # Static site and privacy-policy pages
|-- README.md                 # Current product overview for this repo
|-- CONTRIBUTING.md           # Contribution guardrails
|-- SELF_HOSTING_MODEL.md     # Public-repo and self-hosting boundaries
`-- AGENTS.md                 # Repo-specific agent instructions
```

## What Matters Most Today

The active shipped path in this repo is:

1. `android/`
2. `Docs/android-mirror-shell-architecture.md`

That is the current Android pairing-and-WebView shell.

The bridge and relay code remain in-tree, but they are legacy/reference paths rather than the primary Android runtime.

## Android App

Path: `android/`

### Current entrypoints

- `app/src/main/java/io/androdex/android/MainActivity.kt`
- `app/src/main/java/io/androdex/android/MirrorShellViewModel.kt`
- `app/src/main/java/io/androdex/android/MirrorShellApp.kt`
- `app/src/main/java/io/androdex/android/pairing/`
- `app/src/main/java/io/androdex/android/web/`
- `app/src/main/java/io/androdex/android/persistence/MirrorShellStore.kt`

### Current runtime summary

- Android pairs to a desktop/server pairing URL
- Android stores the paired origin and last-opened route
- Android loads the paired web app in a same-origin WebView shell

### Legacy Android code still present

- `app/src/main/java/io/androdex/android/AndrodexApp.kt`
- `app/src/main/java/io/androdex/android/MainViewModel.kt`
- `app/src/main/java/io/androdex/android/data/`
- `app/src/main/java/io/androdex/android/service/`
- `app/src/main/java/io/androdex/android/transport/`
- `app/src/main/java/io/androdex/android/ui/`

Those packages document previous native-client experiments and should not be mistaken for the current app entry without checking `MainActivity`.

## Bridge

Path: `androdex-bridge/`

The bridge package is still useful reference material for the earlier host-local bridge architecture, but it is no longer the default Android product path documented in the root README.

Use:

- `androdex-bridge/README.md` for bridge CLI docs
- `androdex-bridge/src/README.md` for bridge source orientation

## Relay

Path: `relay/`

The relay service remains in-tree as compatibility/reference infrastructure from the previous bridge-first architecture.

## Docs

Path: `Docs/`

Most useful current docs:

- `Docs/android-mirror-shell-architecture.md`
- `Docs/PRIVACY_POLICY.md`
- `Docs/play-store/README.md`
- `Docs/source-file-map.md`

Historical/reference docs are still kept here as well, including older convergence and native-client notes.

## Companion Desktop Repo

The canonical desktop/server backend now lives in the separate Androdex Desktop repo:

- https://github.com/Robertg761/Androdex-Desktop
