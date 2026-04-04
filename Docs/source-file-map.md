# Source File Map

This document is the code-focused companion to [Docs/repo-map.md](./repo-map.md).

Scope:

- explains the source files that make the product work
- covers Android app code under `android/app/src/main/java/io/androdex/android/`
- covers bridge code under `androdex-bridge/bin/` and `androdex-bridge/src/`
- covers relay runtime files under `relay/*.js`
- does not catalog tests, docs, images, Android resources, or generated/build output

## Runtime Overview

The product runtime is split into three codebases:

1. Android app: UI, pairing, reconnect, timeline rendering, runtime controls, notifications
2. Host bridge: macOS CLI/service, Codex transport, secure pairing, git/workspace handling
3. Relay: thin transport hop plus optional push-session webhook helper

## Android App

Base path: `android/app/src/main/java/io/androdex/android/`

### App entry and shared app state

- `AndrodexApp.kt`: top-level Compose router that chooses between pairing, home, sidebar, turn, and overlay surfaces from the current app state.
- `AppEnvironment.kt`: reads build-time environment/config values, including relay and optional FCM configuration.
- `ComposerCommandState.kt`: defines slash-command, review-target, and review-selection models used by the composer.
- `FreshPairingAttemptState.kt`: models the state machine for a brand-new pairing flow.
- `GitUiModels.kt`: UI-facing state models for git sheets, dialogs, buttons, and alerts.
- `MainActivity.kt`: Android activity entrypoint that creates the view model, handles app intents, and hosts the Compose app.
- `MainViewModel.kt`: main UI orchestration facade that adapts service/repository data into screen state and user actions.
- `ThreadOpenPerfLogger.kt`: debug-only performance logger for timing thread-open and thread-recovery stages.

### Attachments

- `attachment/ImageAttachmentCodec.kt`: converts raw image bytes or data URLs into app attachment objects and decodes thumbnails for display.

### Crypto and secure storage

- `crypto/CryptoUtils.kt`: pairing/session crypto helpers for identity generation, signing, verification, transcript building, and shared-secret derivation.
- `crypto/SecureStore.kt`: Android secure persistence wrapper used for sensitive pairing/session state.

### Data and protocol layer

- `data/AndrodexClient.kt`: low-level relay/bridge client that owns socket behavior, secure-session handshake logic, JSON-RPC messaging, capability negotiation, and turn/thread payload building.
- `data/AndrodexPersistence.kt`: persistent storage for saved relay sessions, trusted pairing state, thread runtime overrides, and cached timeline/history data.
- `data/AndrodexRepository.kt`: repository abstraction over client plus persistence, exposing higher-level app operations and state flows.
- `data/JsonHelpers.kt`: JSON decoding and normalization helpers for thread, timeline, workspace, and protocol payloads.
- `data/TurnRequestCompatibility.kt`: fallback/retry rules for older bridge/runtime payload formats when turn requests are rejected.

### Domain and transport models

- `model/Attachments.kt`: attachment domain models and helper methods for intake, readiness, and send eligibility.
- `model/GitModels.kt`: git result and status models returned by bridge-side git operations.
- `model/Models.kt`: core app domain models for pairing, sessions, trusted hosts, threads, runtime settings, workspaces, conversation content, and subagent state.

### Notifications

- `notifications/AndrodexAppProcessState.kt`: single-process foreground/background flag used to decide notification behavior.
- `notifications/AndrodexFirebaseMessagingService.kt`: Firebase entrypoint for incoming push tokens and notification-related events.
- `notifications/AndrodexFirebaseSupport.kt`: initializes Firebase dynamically from build config and fetches the current device token.
- `notifications/AndrodexNotificationModels.kt`: constants and payload models for completion notifications and deep-link opens.
- `notifications/AndrodexNotificationPlatform.kt`: Android notification channel creation and notification posting logic.
- `notifications/AndrodexNotificationStore.kt`: small preferences store for push tokens and notification-permission prompt state.
- `notifications/AndrodexNotificationSupport.kt`: tiny helpers for permission-aware, best-effort notification posting.
- `notifications/AndroidNotificationCoordinator.kt`: higher-level notification coordinator that decides when to register tokens and when to surface run completions.

### Onboarding

- `onboarding/FirstPairingOnboardingStore.kt`: interface plus implementations for remembering whether first-pairing onboarding has been shown.

### Long-lived service state

- `service/AndrodexService.kt`: central Android service/state owner for connection lifecycle, pairing, runtime capabilities, thread timelines, approvals, queued drafts, maintenance actions, and workspace state.

### Timeline projection

- `timeline/ThreadTimelineRender.kt`: converts conversation history into render snapshots, scroll targets, and derived agent-activity text for the timeline UI.

### Home UI

- `ui/home/HomeScreen.kt`: connected home surface showing connection/trust/account state, current project, and recent conversation entry points.
- `ui/home/ProjectPickerSheet.kt`: project/workspace picker sheet for browsing and activating host workspaces.

### Pairing UI

- `ui/pairing/FirstPairingOnboardingScreen.kt`: multi-step onboarding flow that explains the host-local bridge workflow before first pairing.
- `ui/pairing/PairingPayloadValidator.kt`: validation for pasted/manual pairing payloads before the app tries to connect.
- `ui/pairing/PairingScreen.kt`: main pairing/reconnect screen for QR flow, manual payloads, saved reconnect, and recovery states.

### Settings UI

- `ui/settings/AboutAndrodexSheet.kt`: about sheet with product summary, links, and project metadata.
- `ui/settings/RuntimeSettingsSheet.kt`: app-wide runtime defaults sheet for reasoning, speed, and related options.

### Shared UI primitives

- `ui/shared/AndrodexOverlays.kt`: modal overlays and dialogs for approvals, errors, missing threads, and maintenance confirmations.
- `ui/shared/ConnectionStatusUi.kt`: shared status cards, capsules, busy indicators, trust/account cards, and agent activity banner presentation.
- `ui/shared/LandingChrome.kt`: decorative landing-page background and section-surface chrome used by pairing/home screens.
- `ui/shared/RemodexMotion.kt`: animation specs and pressed-state helpers shared across the Compose UI.
- `ui/shared/RemodexPrimitives.kt`: reusable buttons, pills, headers, grouped surfaces, input chrome, and other shared UI building blocks.
- `ui/shared/RemodexSheets.kt`: reusable modal-sheet scaffolding, cards, inline rows, and option selectors.

### Sidebar UI

- `ui/sidebar/SidebarContent.kt`: full sidebar thread list UI, including project grouping, search, thread rows, and loading/empty states.
- `ui/sidebar/ThreadListPane.kt`: wrapper pane for embedding the thread list in larger layouts.

### UI state adapters

- `ui/state/AndrodexFeatureState.kt`: feature-oriented UI models and adapters that translate raw app/service state into screen-specific Compose state.

### Theme

- `ui/theme/Theme.kt`: design tokens, color system, geometry, motion settings, and top-level app theme wrapper.
- `ui/theme/Type.kt`: font family and typography definitions for the Remodex-styled Android UI.

### Turn screen UI

- `ui/turn/AttachmentTiles.kt`: attachment-strip and thumbnail components for composer previews and message attachments.
- `ui/turn/CodeCommentDirectiveParser.kt`: parser for `::code-comment{...}` review directives embedded in assistant output.
- `ui/turn/ComposerBar.kt`: the rich composer UI, including slash commands, mentions, attachments, runtime affordances, and send-state presentation.
- `ui/turn/ForkThreadSheet.kt`: sheet for choosing where a thread fork should land and how it should be rebound.
- `ui/turn/GitSheet.kt`: git status/action sheet with branch, diff, sync, commit, pull, push, and changed-file summaries.
- `ui/turn/ThreadRuntimeSheet.kt`: per-thread runtime override sheet for reasoning and service-tier choices.
- `ui/turn/ThreadTimelineScreen.kt`: main conversation screen that renders the timeline, queued drafts, tool inputs, menus, prompts, and live-run state.
- `ui/turn/TurnAttachmentPipeline.kt`: coroutine-based intake pipeline for turning gallery/camera inputs into sendable attachments.

## Host Bridge

Base paths:

- `androdex-bridge/bin/`
- `androdex-bridge/src/`

### CLI entrypoints

- `bin/androdex.js`: canonical npm-installed CLI entrypoint that delegates to the bridge CLI.
- `bin/cli.js`: command dispatcher for `up`, `start`, `restart`, `stop`, `status`, `run`, `run-service`, `reset-pairing`, `resume`, and `watch`.

### Bridge runtime and helpers

- `src/account-status.js`: builds sanitized account/auth status payloads to send back to Android without leaking unsafe detail.
- `src/bridge.js`: main bridge process that connects the relay, secure transport, Codex runtime, notifications, workspace runtime, git/workspace handlers, and desktop refresh logic.
- `src/codex-desktop-refresher.js`: desktop refresh workaround controller for reopening or nudging the Codex desktop app after phone-authored activity.
- `src/codex-rpc-client.js`: lightweight JSON-RPC client wrapper around the active Codex transport.
- `src/codex-transport.js`: starts Codex locally or connects to an existing endpoint, then exposes a normalized transport interface.
- `src/daemon-state.js`: persists bridge daemon config, pairing session payloads, status files, and log paths under the host’s `~/.androdex` state directory.
- `src/git-handler.js`: host-side git operations for status, diff, commit, pull, push, branch, and worktree workflows.
- `src/index.js`: public module exports for the bridge package’s CLI/runtime surface.
- `src/macos-launch-agent.js`: launchd-specific install/start/stop/status flow for the background macOS bridge service.
- `src/notifications-handler.js`: handles bridge-side notification RPCs such as token registration and completion registration requests.
- `src/push-notification-completion-dedupe.js`: dedupe logic so the same completion notification is not forwarded multiple times.
- `src/push-notification-service-client.js`: HTTP client for talking to an external push service from the bridge.
- `src/push-notification-tracker.js`: watches outbound assistant activity and turns final results into completion-notification events.
- `src/qr.js`: terminal QR rendering for pairing payloads.
- `src/rollout-live-mirror.js`: reads rollout logs and mirrors live activity back to Android as the run progresses.
- `src/rollout-watch.js`: rollout discovery/watch utilities used for `watch`, live mirrors, desktop refresh, and context usage fallback.
- `src/runtime-compat.js`: compatibility layer that normalizes older/newer RPC payloads and sanitizes history images for relay transport.
- `src/scripts/codex-refresh.applescript`: AppleScript helper used by the macOS desktop refresh path.
- `src/secure-device-state.js`: canonical bridge identity and trusted-phone persistence, including migration/recovery from older storage.
- `src/secure-transport.js`: bridge-side encrypted pairing and reconnect protocol, including QR bootstrap, trusted reconnect, encryption, and replay buffering.
- `src/session-state.js`: remembers the last active thread and can reopen it in the local Codex desktop app.
- `src/thread-context-handler.js`: handler for thread context/usage RPC requests routed from Android.
- `src/workspace-browser.js`: safe host-side directory browsing for the Android workspace picker.
- `src/workspace-handler.js`: workspace RPC handler for recent workspaces, directory listing, activation, and reverse-patch preview/apply flows.
- `src/workspace-runtime.js`: active workspace manager that binds the chosen host directory to a Codex transport and tracks recent workspaces.

## Relay

Base path: `relay/`

- `server.js`: standalone HTTP/HTTPS server wrapper that exposes the relay, health endpoints, and optional push-session routes.
- `relay.js`: core in-memory WebSocket relay that pairs one host daemon with one mobile client, handles heartbeat/liveness, and supports trusted-session resolution.
- `push-service.js`: optional push-session registry plus webhook fan-out helper used for completion notifications.

## How To Use This Map

If you are trying to place new work:

- Android UI or Android-side state logic usually belongs somewhere under `android/app/src/main/java/io/androdex/android/`
- host execution, git, launchd, pairing, or Codex transport work usually belongs under `androdex-bridge/`
- cross-network session transport belongs in `relay/`

If you are trying to debug a feature:

- pairing and reconnect: start with Android `data/AndrodexClient.kt`, bridge `secure-transport.js`, and relay `relay.js`
- thread/timeline UI: start with Android `service/AndrodexService.kt`, `timeline/ThreadTimelineRender.kt`, and `ui/turn/ThreadTimelineScreen.kt`
- host workspace or git behavior: start with bridge `workspace-runtime.js`, `workspace-handler.js`, and `git-handler.js`
- notifications: start with Android `notifications/`, bridge `notifications-handler.js` and `push-notification-tracker.js`, then relay `push-service.js`
