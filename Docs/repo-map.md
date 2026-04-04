# Repo Map

This document is the high-signal map of the Androdex repository.
It is meant to answer two questions quickly:

- where does a piece of functionality live?
- which folders are core product code versus docs, assets, or local/generated output?

This map focuses on meaningful repo structure, not dependency trees like `node_modules/`.

If you want the code-level, file-by-file companion for the Android app, host bridge, and relay runtime, see [Docs/source-file-map.md](./source-file-map.md).

## Top-Level Map

```text
androdex/
|-- android/                  # Android app source, tests, resources, and local Android build output
|-- androdex-bridge/          # Published macOS host bridge CLI and service
|-- relay/                    # Standalone WebSocket relay and optional push helper
|-- Docs/                     # Architecture notes, parity docs, store/review docs, local plans
|-- assets/                   # Shared brand and marketing graphics
|-- site/                     # Static marketing/privacy site
|-- tools/                    # Small operational scripts
|-- tmp/                      # Local scratch outputs and one-off working files
|-- tmp-gh-artifact/          # Downloaded GitHub artifact scratch area
|-- README.md                 # Main product and setup documentation
|-- CONTRIBUTING.md           # Contribution rules and project direction
|-- SELF_HOSTING_MODEL.md     # Public repo and self-hosting boundaries
|-- AGENTS.md                 # Repo-specific agent instructions
`-- LICENSE                   # Project license
```

## What Matters Most

If you only need the runtime shape, the repo has three product code centers:

1. `android/`: Android remote client
2. `androdex-bridge/`: host-local bridge and CLI
3. `relay/`: relay transport for cross-network connectivity

Everything else is support material, assets, static site code, or local scratch space.

## Root Files

- `README.md`: primary setup, architecture, and usage guide
- `CONTRIBUTING.md`: contribution guardrails and UI parity workflow
- `SELF_HOSTING_MODEL.md`: explains what is public, self-hosted, and intentionally not bundled
- `AGENTS.md`: repo-specific working instructions for coding agents
- `.github/workflows/`: CI and release automation

## Android App

Path: `android/`

This is the Android Studio project. The actual app lives in `android/app/`.

### Android project layout

```text
android/
|-- app/
|   |-- src/main/java/io/androdex/android/   # Kotlin app source
|   |-- src/main/res/                        # Android resources and launcher assets
|   |-- src/test/                            # JVM/unit tests
|   `-- src/androidTest/                     # Instrumented/UI tests
|-- build.gradle.kts                         # Root Android build config
|-- settings.gradle.kts                      # Single-module app project
|-- gradle/                                  # Gradle wrapper support
|-- .gradle/                                 # Local Gradle cache
|-- .kotlin/                                 # Local Kotlin cache
`-- app/build/                               # Local build output
```

### Android source layout

Path: `android/app/src/main/java/io/androdex/android/`

- `MainActivity.kt`: Android activity entrypoint
- `AndrodexApp.kt`: top-level Compose app/router
- `MainViewModel.kt`: main UI orchestration facade
- `AppEnvironment.kt`: app wiring/bootstrap helpers
- `service/`: long-lived Android service/state owner
- `data/`: bridge protocol client, repository, persistence, JSON helpers
- `model/`: domain and transport models
- `timeline/`: timeline rendering/projection helpers
- `notifications/`: Android notification and Firebase support
- `attachment/`: image attachment encoding/handling
- `crypto/`: secure storage and cryptography helpers
- `onboarding/`: first-pairing onboarding state
- `ui/`: feature-oriented Compose UI packages

### Android UI packages

Path: `android/app/src/main/java/io/androdex/android/ui/`

- `home/`: connected home shell and project picker
- `pairing/`: QR/payload pairing and onboarding entry
- `sidebar/`: thread list and sidebar presentation
- `turn/`: timeline, composer, git sheet, runtime sheet, fork sheet, attachments
- `settings/`: runtime/about settings sheets
- `shared/`: shared overlays, motion, primitives, status UI
- `state/`: feature state adapters for Compose surfaces
- `theme/`: typography and theme setup

### Android tests

Path: `android/app/src/test/java/io/androdex/android/`

Test coverage is organized around behavior instead of just file mirrors:

- app/view-model state transitions
- transport and secure session behavior
- pairing persistence and protocol compatibility
- notifications
- feature presentation logic
- timeline and composer formatting

Instrumented UI coverage lives in `android/app/src/androidTest/`.

## Host Bridge

Path: `androdex-bridge/`

This is the published `androdex` npm package. It runs on macOS, manages the host-local bridge service, and speaks to Codex plus the Android app.

### Bridge layout

```text
androdex-bridge/
|-- bin/                        # CLI entrypoints
|-- src/                        # Runtime, protocol, handlers, launch agent logic
|-- test/                       # Node test suite
|-- package.json                # Published npm package manifest
`-- README.md                   # Bridge-specific usage and release notes
```

### Bridge source roles

Path: `androdex-bridge/src/`

- `index.js`: CLI/runtime entry coordination
- `bridge.js`: main bridge server/orchestration logic
- `codex/`: Codex transport and RPC helpers
- `workspace/`: workspace browsing, activation, and patch/revert operations
- `pairing/`: QR output, trusted device persistence, and secure transport
- `notifications/`: push registration, dedupe, service client, and completion tracking
- `rollout/`: rollout watching and live mirroring
- `git-handler.js`: git, branch, and worktree actions
- `session-state.js`: active session bookkeeping
- `thread-context-handler.js`: thread-scoped context helpers
- `codex-desktop-refresher.js`: desktop refresh workaround logic
- `macos-launch-agent.js`: launchd installation/control
- `daemon-state.js`: daemon status persistence
- `runtime-compat.js`: compatibility checks with host runtime features
- `account-status.js`: account/status helpers
- `scripts/codex-refresh.applescript`: macOS refresh helper

## Relay

Path: `relay/`

This is the standalone relay service. It is intentionally thin: transport plus optional push-helper hosting.

### Relay layout

- `server.js`: standalone HTTP/WebSocket server entrypoint
- `relay.js`: core relay session setup and forwarding
- `push-service.js`: optional push-session helper
- `test/`: relay and push-service tests
- `Dockerfile`: container build for relay hosting
- `README.md`: relay setup, security, and self-hosting guide

## Docs

Path: `Docs/`

This folder contains working documentation, not runtime code.

### Main doc clusters

- `Docs/android-compose-structure.md`: current Android UI/package split
- `Docs/remodex-port-architecture.md`: target architecture notes from the Remodex port
- `Docs/remodex-parity-checklist.md`: parity tracking
- `Docs/remodex-visual-reference/`: screenshots, audits, parity board, capture manifests
- `Docs/play-store/`: Play submission assets, review notes, policies
- `Docs/local/`: local-only planning notes
- `Docs/PRIVACY_POLICY.md`: documentation copy for privacy-related surfaces
- `Docs/README.md`: lightweight index into the most useful docs

## Static Site

Path: `site/`

Small static website for the product and privacy policy.

- `index.html`: marketing/waitlist landing page
- `styles.css`: site styling
- `app.js`: waitlist form submission behavior
- `privacy-policy/index.html`: static privacy policy page
- `app-icon-512.png`: site icon asset

## Assets

Path: `assets/`

Brand/marketing graphics used for docs, site, listings, or repository presentation.

- logos
- banner/OG graphics
- Play feature graphics
- icon working files under `assets/icon-work/`

## Tools

Path: `tools/`

Small operational scripts, not product runtime code.

- `assign-promo-code.ps1`
- `waitlist-apps-script.gs`

## Local And Generated Areas

These paths are useful, but they are not part of the product architecture:

- `tmp/`: local scratch output and one-off generated files
- `tmp-gh-artifact/`: downloaded CI artifacts
- `android/.gradle/`: Gradle cache
- `android/.kotlin/`: Kotlin cache
- `android/app/build/`: Android build output
- `androdex-bridge/node_modules/`: local Node dependencies
- `relay/node_modules/`: local Node dependencies

Two root-level items are especially easy to misread when scanning the repo:

- `main-8X_hBwW2.js`: looks like a generated/minified bundle, not Androdex source
- `.DS_Store`: macOS metadata file

Those do not define repo architecture and should be treated as incidental local/generated content.

## Quick Mental Model

When you are deciding where code belongs:

- Android app behavior/UI belongs in `android/`
- host machine runtime, Codex integration, git, pairing, and launchd behavior belong in `androdex-bridge/`
- relay transport concerns belong in `relay/`
- architecture notes, parity docs, and release/support docs belong in `Docs/`
- marketing/privacy site changes belong in `site/`
- brand graphics belong in `assets/`
- local experiments and generated output belong in `tmp/` or ignored build/cache paths
