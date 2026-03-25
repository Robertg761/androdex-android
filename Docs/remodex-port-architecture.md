# Remodex Port Architecture

Compared on 2026-03-25 using:

- Androdex repo at `G:\Projects\Androdex`
- Remodex mirror at `E:\Temp\remodex-compare`

## Purpose

This document describes how to move Androdex closer to Remodex structurally without losing Androdex's product constraints:

- host-local Codex execution remains the core runtime model
- Android stays a paired remote client for a host machine, including Windows-safe behavior
- timeline correctness must preserve the `AGENTS.md` guardrails around turn fallback, reconnect recovery, and item-aware history merge
- work should stay incremental and non-destructive so the app remains working during the refactor

The goal is not to copy Remodex file-for-file. The goal is to port its layering:

- central service for socket/session/runtime state
- per-feature state containers instead of one oversized root view model
- item-aware timeline reducer and thread-scoped run state
- dedicated composer state/actions separate from the timeline and connection state
- smaller feature-oriented UI files instead of one large Compose entry file

## Current Androdex Shape

Today the Android app is concentrated in a few files:

- `android/app/src/main/java/io/androdex/android/MainViewModel.kt`
- `android/app/src/main/java/io/androdex/android/AndrodexApp.kt`
- `android/app/src/main/java/io/androdex/android/data/AndrodexRepository.kt`
- `android/app/src/main/java/io/androdex/android/data/AndrodexClient.kt`
- `android/app/src/main/java/io/androdex/android/model/Models.kt`

That gives us a working product, but it mixes together concerns that Remodex keeps separate:

- connection lifecycle
- reconnect policy
- thread list loading
- thread detail loading
- live event ingestion
- timeline merge logic
- composer state
- runtime settings
- approvals
- project picker state

The biggest structural problems are:

1. `MainViewModel` is both orchestration layer and feature state container.
2. `AndrodexApp.kt` contains root navigation plus large feature-specific UI blocks.
3. Timeline semantics are embedded in view-model helper methods rather than a dedicated reducer/service layer.
4. Composer state is only a few fields in root UI state, which will not scale to plan mode, queueing, mentions, attachments, or steer/stop flows.
5. Models in `Models.kt` combine transport payloads, domain state, and UI-ready message rendering types.

## Remodex Reference Shape

The most relevant Remodex structure is:

- `CodexMobile/CodexMobile/Services/CodexService.swift`
- `CodexMobile/CodexMobile/Services/CodexService+Connection.swift`
- `CodexMobile/CodexMobile/Services/CodexService+Incoming.swift`
- `CodexMobile/CodexMobile/Services/CodexService+IncomingAssistant.swift`
- `CodexMobile/CodexMobile/Services/CodexService+History.swift`
- `CodexMobile/CodexMobile/Services/CodexService+RuntimeConfig.swift`
- `CodexMobile/CodexMobile/Views/Home/ContentViewModel.swift`
- `CodexMobile/CodexMobile/Views/Turn/TurnViewModel.swift`
- `CodexMobile/CodexMobile/Views/Turn/TurnTimelineReducer.swift`
- `CodexMobile/CodexMobile/Views/Sidebar/*.swift`
- `CodexMobile/CodexMobile/Views/Turn/*.swift`

The key pattern to emulate is:

- one long-lived service owns transport, server state, and cross-thread coordination
- root/home view model handles screen orchestration, not wire decoding
- turn view model owns composer and chat-local actions
- timeline projection is a dedicated reducer/helper, not ad hoc UI mutation code
- views are split by feature area and mostly read already-prepared state

## Target Android Architecture

### Package layout

Proposed package tree under `android/app/src/main/java/io/androdex/android/`:

```text
app/
  AndrodexApplication.kt
  MainActivity.kt
  di/
    AppContainer.kt
  service/
    AndrodexService.kt
    connection/
      ConnectionCoordinator.kt
      ReconnectController.kt
    threads/
      ThreadStore.kt
      ThreadSyncCoordinator.kt
    timeline/
      TimelineEventProcessor.kt
      TimelineReducer.kt
      TimelineStore.kt
      ActiveTurnTracker.kt
    composer/
      ComposerController.kt
      ComposerQueueStore.kt
      MentionResolver.kt
    runtime/
      RuntimeConfigStore.kt
      RuntimeCapabilityStore.kt
    approvals/
      ApprovalCoordinator.kt
    workspace/
      WorkspaceCoordinator.kt
  data/
    AndrodexClient.kt
    AndrodexRepository.kt
    AndrodexPersistence.kt
    JsonHelpers.kt
    rpc/
      RpcModels.kt
      RpcDecoders.kt
  feature/
    home/
      HomeViewModel.kt
      HomeUiState.kt
    threads/
      ThreadListViewModel.kt
      ThreadListUiState.kt
    turn/
      TurnViewModel.kt
      TurnUiState.kt
      TurnComposerState.kt
      TurnTimelineUiModel.kt
    settings/
      SettingsViewModel.kt
      SettingsUiState.kt
    pairing/
      PairingViewModel.kt
      PairingUiState.kt
    workspace/
      WorkspacePickerViewModel.kt
      WorkspacePickerUiState.kt
  model/
    domain/
      ThreadModels.kt
      TimelineModels.kt
      RuntimeModels.kt
      WorkspaceModels.kt
      ApprovalModels.kt
    ui/
      TimelineRow.kt
      ComposerModels.kt
  ui/
    navigation/
      AndrodexNavGraph.kt
    pairing/
      PairingScreen.kt
      PairingComponents.kt
    home/
      HomeScreen.kt
    threads/
      ThreadListScreen.kt
      ThreadRow.kt
    turn/
      TurnScreen.kt
      TurnTimeline.kt
      TurnComposer.kt
      TurnMessageCard.kt
      TurnStatusBanner.kt
    settings/
      RuntimeSettingsSheet.kt
    workspace/
      WorkspacePickerSheet.kt
    shared/
      ApprovalDialog.kt
      BusyIndicator.kt
      StatusCapsule.kt
```

This is intentionally Android-native, but it mirrors Remodex's separation of service, feature state, timeline projection, and UI composition.

### Layer responsibilities

#### `service`

Equivalent to Remodex `CodexService` plus its extensions. This layer should own:

- socket/session lifecycle
- trusted pairing and reconnect state
- runtime capability flags
- thread list cache
- per-thread active turn state
- per-thread timeline store
- incoming event routing
- approval request lifecycle
- notification-ready run completion state later on

This layer should not know about Compose widgets.

#### `feature`

Equivalent to Remodex `ContentViewModel` and `TurnViewModel`. These classes should:

- observe slices of service state
- expose screen-specific `UiState`
- turn user actions into service calls
- own transient UI-only concerns like open sheets, inline errors, scroll intents, and text field state

This is the right place for Android equivalents of Remodex's root/home orchestration and turn-local composer logic.

#### `timeline`

This needs to become a first-class subsystem, not just helper methods on `MainViewModel`. It should own:

- item-scoped message merge
- running fallback when `turn/started` has no usable `turnId`
- late reasoning delta merge into an existing row
- turn completion transitions
- thread-local render projection
- history reconciliation after reconnect or explicit thread reload

This is the area most directly driven by `AGENTS.md`.

#### `composer`

This needs to split away from generic screen state so it can grow into:

- basic send path
- plan mode
- queued follow-up drafts
- slash/file/skill affordances
- review/subagent actions
- attachment pipeline later
- stop/steer behavior tied to active turn state

#### `ui`

Compose files should become thin renderers. `AndrodexApp.kt` should shrink into navigation and top-level composition only.

## Concrete Mapping: Remodex to Proposed Android

### Services and coordinators

| Remodex file/class | Current Androdex location | Proposed Android destination | Notes |
| --- | --- | --- | --- |
| `Services/CodexService.swift` | `data/AndrodexClient.kt`, `MainViewModel.kt` | `service/AndrodexService.kt` | Central long-lived state/service container. Move shared state ownership here first. |
| `Services/CodexService+Connection.swift` | `MainViewModel.kt`, `data/AndrodexClient.kt` | `service/connection/ConnectionCoordinator.kt`, `ReconnectController.kt` | Pull reconnect/background policy out of root VM. Preserve saved pairing and relay truth-source guardrails. |
| `Services/CodexService+Incoming.swift` | `data/AndrodexClient.kt`, `MainViewModel.handleClientUpdate()` | `service/timeline/TimelineEventProcessor.kt` plus service-level notification routing | Incoming event decode should mutate service stores, not directly mutate screen state. |
| `Services/CodexService+IncomingAssistant.swift` | `MainViewModel.applyAssistantDelta()` and completion logic | `service/timeline/TimelineEventProcessor.kt` | Needed for item-aware assistant/reasoning/file-change merging. |
| `Services/CodexService+History.swift` | `MainViewModel.openThread()` reload pattern | `service/timeline/TimelineStore.kt` | Thread history load/reconcile should merge with live state instead of replacing blindly. |
| `Services/CodexService+RuntimeConfig.swift` | `MainViewModel.loadRuntimeConfig()` | `service/runtime/RuntimeConfigStore.kt` | Keep runtime options separate from connection and thread state. |
| `Services/CodexService+ThreadProjectRouting.swift` | `MainViewModel.ensureWorkspaceActivated()` | `service/workspace/WorkspaceCoordinator.kt` | Important for cross-project routing without sidebar repo filtering. |
| `Services/CodexService+Review.swift` | not present | `service/composer/ComposerController.kt` | Later phase, but composer architecture should leave room for it now. |
| `Services/CodexService+Notifications.swift` | not present | `service/notifications/...` later | Not phase-one, but the service boundary should reserve this responsibility. |
| `Services/GitActionsService.swift` | bridge only today | `service/git/GitActionsService.kt` later | UI parity later, but separate service is the right shape. |

### View models and screen state

| Remodex file/class | Current Androdex location | Proposed Android destination | Notes |
| --- | --- | --- | --- |
| `Views/Home/ContentViewModel.swift` | `MainViewModel.kt` | `feature/home/HomeViewModel.kt` | Root app orchestration only: app foreground/background, routing, reconnect prompts, app-level banners. |
| `Views/Turn/TurnViewModel.swift` | `MainViewModel.kt` and `AndrodexApp.kt` | `feature/turn/TurnViewModel.kt` | Own composer text, queueing, send availability, stop/steer actions, and turn-local UI state. |
| `Views/Sidebar/*.swift` | thread list UI in `AndrodexApp.kt` | `feature/threads/ThreadListViewModel.kt` + `ui/threads/*` | Thread list/search/run badges can evolve without touching turn screen code. |
| `Views/SettingsView.swift` | `AndrodexApp.kt` bottom sheet | `feature/settings/SettingsViewModel.kt` + `ui/settings/RuntimeSettingsSheet.kt` | Runtime settings become their own feature slice. |
| `Views/QRScannerView.swift` and onboarding views | pairing branch in `AndrodexApp.kt` | `feature/pairing/PairingViewModel.kt` + `ui/pairing/*` | Cleaner split between pairing and connected app shell. |
| `Views/Turn/TurnComposerViewState.swift` | `AndrodexUiState.composerText` | `feature/turn/TurnComposerState.kt` | Avoid packing all composer concerns into root UI state. |

### Models

| Remodex model area | Current Androdex location | Proposed Android destination | Notes |
| --- | --- | --- | --- |
| `Models/CodexThread.swift` | `model/Models.kt` | `model/domain/ThreadModels.kt` | Keep thread summaries/domain data separate from transport JSON helpers. |
| `Models/CodexMessage.swift` | `model/Models.kt` | `model/domain/TimelineModels.kt` and `model/ui/TimelineRow.kt` | Split raw timeline entities from render-ready rows. |
| `Models/CodexModelOption.swift`, `CodexReasoningEffortOption.swift` | `model/Models.kt` | `model/domain/RuntimeModels.kt` | Straightforward extraction. |
| `Models/CodexAccessMode.swift`, `CodexServiceTier.swift` | not present | `model/domain/RuntimeModels.kt` later | Add when runtime controls grow. |
| `Models/CodexImageAttachment.swift`, skill/file mention models | not present | `model/ui/ComposerModels.kt` later | Composer package should be ready for these additions. |

### Views

| Remodex view area | Current Androdex location | Proposed Android destination | Notes |
| --- | --- | --- | --- |
| `Views/SidebarView.swift` and `Views/Sidebar/*.swift` | thread list branch in `AndrodexApp.kt` | `ui/threads/ThreadListScreen.kt`, `ThreadRow.kt`, `ThreadRunBadge.kt` | Thread list becomes independent from pairing and turn rendering. |
| `Views/Turn/TurnView.swift` | thread detail branch in `AndrodexApp.kt` | `ui/turn/TurnScreen.kt` | Root chat screen composition only. |
| `Views/Turn/TurnTimelineView.swift` | `AndrodexApp.kt` message list | `ui/turn/TurnTimeline.kt` | Read precomputed timeline rows from feature state. |
| `Views/Turn/TurnComposerView.swift` and bars | `AndrodexApp.kt` `ComposerBar` | `ui/turn/TurnComposer.kt`, `TurnComposerActions.kt` | Enables plan mode/queueing expansion without destabilizing timeline rendering. |
| shared approval/status components | inline in `AndrodexApp.kt` | `ui/shared/*` | Low-risk extraction early in the migration. |

## Proposed State Model

### 1. Long-lived service state

`AndrodexService` should own durable app/session state:

- connection phase and detail
- saved pairing metadata and fingerprint
- runtime config and capability flags
- thread summaries
- active thread id
- `activeTurnIdByThread`
- per-thread timeline state
- pending approvals
- active workspace and recent workspaces

This is the Android equivalent of the parts of Remodex `CodexService` that survive screen changes.

### 2. Per-thread timeline state

Introduce a dedicated type, for example `ThreadTimelineState`, with:

- `threadId`
- `messagesByItemKey` or ordered raw message list plus index maps
- `messageRevision`
- `activeTurnId`
- `isThreadRunning`
- `protectedRunningFallback`
- `latestTurnTerminalState`
- `stoppedTurnIds`
- `renderRows`

This is where the Remodex `ThreadTimelineState` and `TurnTimelineRenderSnapshot` ideas map well to Android.

### 3. Per-turn composer state

Introduce `TurnComposerState` under `feature/turn`:

- `input`
- `isSending`
- `isPlanModeArmed`
- `queuedDrafts`
- `pauseState`
- `mentionedFiles`
- `mentionedSkills`
- `attachments`
- `reviewSelection`
- `subagentsArmed`

Even if Androdex initially only uses `input` and `isSending`, this shape avoids a second disruptive state rewrite later.

### 4. Render projection

The UI should render `TimelineRow` or `TurnTimelineUiModel` objects produced by `TimelineReducer`, not raw transport events.

That lets us keep complex logic in one place:

- merge reasoning deltas
- dedupe late completions
- collapse placeholder thinking rows
- keep assistant rows item-scoped
- preserve file/tool/plan rows without flattening

## Migration Plan

The migration should keep the app shipping after each step.

### Phase 1: Extract without behavior change

1. Create `service/AndrodexService.kt` as the owner of current shared state previously kept in `MainViewModel`.
2. Move connection and reconnect logic into `service/connection/*`.
3. Move runtime settings loading into `service/runtime/RuntimeConfigStore.kt`.
4. Split `AndrodexApp.kt` into `ui/pairing`, `ui/threads`, `ui/turn`, and `ui/shared` files with the same behavior.
5. Keep `MainViewModel` temporarily as a façade that adapts service state into current UI state.

Expected result:

- almost no behavior change
- smaller files
- clearer ownership boundaries

### Phase 2: Introduce feature view models

1. Replace `MainViewModel` routing logic with `HomeViewModel`.
2. Add `ThreadListViewModel` for thread list/workspace picker concerns.
3. Add `TurnViewModel` for thread-local composer and turn actions.
4. Convert `MainActivity` and root composable to wire these feature models instead of one global UI state blob.

Expected result:

- root orchestration separated from turn-local state
- composer and thread list stop competing in one giant state object

### Phase 3: Build the timeline subsystem

1. Add `TimelineStore`, `TimelineEventProcessor`, and `TimelineReducer`.
2. Route incoming client updates through service/timeline code instead of `MainViewModel.handleClientUpdate()`.
3. Replace `applyAssistantDelta()` and `applyAssistantCompletion()` with item-aware reducer logic.
4. Store `activeTurnIdByThread`, protected running fallback, and terminal run state in the timeline subsystem.
5. Rework thread history loads to reconcile instead of fully replacing rows when possible.

Expected result:

- Androdex starts matching Remodex's structural handling of live turns
- `AGENTS.md` timeline guardrails have an explicit home

### Phase 4: Upgrade the composer subsystem

1. Add `TurnComposerState` and `ComposerController`.
2. Move send eligibility and send mutation out of the timeline/view layer.
3. Add queue-ready state shape even if queueing is not fully exposed yet.
4. Add plan-mode-ready payload formation points.

Expected result:

- future work for plan mode, attachments, mentions, review mode, and subagents becomes additive instead of invasive

### Phase 5: Broaden parity on top of the new structure

After the structure is in place, port higher-level parity features in this order:

1. timeline/run-state parity
2. stop/interrupt and steer-active-run
3. queued follow-up drafts
4. plan mode send path
5. mentions/autocomplete
6. git UI state
7. attachments
8. notifications/recovery UX

## Practical File-by-File Migration From Current Repo

### Replace `MainViewModel.kt`

Current responsibilities to split:

- pairing/connect/disconnect -> `feature/pairing/PairingViewModel.kt` and `service/connection/*`
- app foreground/background reconnect -> `feature/home/HomeViewModel.kt`
- thread list/create/open/close -> `feature/threads/ThreadListViewModel.kt`
- thread message send/load -> `feature/turn/TurnViewModel.kt`
- runtime settings -> `feature/settings/SettingsViewModel.kt`
- approval handling -> `service/approvals/ApprovalCoordinator.kt`
- workspace browser/activation -> `feature/workspace/WorkspacePickerViewModel.kt` and `service/workspace/*`
- client update routing -> `service/AndrodexService.kt` and `service/timeline/*`

`MainViewModel.kt` should eventually disappear. During migration it can temporarily delegate to these layers.

### Replace `AndrodexApp.kt`

Recommended extraction order:

1. `ui/shared/ApprovalDialog.kt`
2. `ui/shared/StatusCapsule.kt`
3. `ui/shared/BusyIndicator.kt`
4. `ui/settings/RuntimeSettingsSheet.kt`
5. `ui/pairing/PairingScreen.kt`
6. `ui/threads/ThreadListScreen.kt`
7. `ui/turn/TurnScreen.kt`
8. `ui/turn/TurnTimeline.kt`
9. `ui/turn/TurnComposer.kt`
10. leave `AndrodexApp.kt` as navigation/container only

This is the Android equivalent of moving toward Remodex's `Home`, `Sidebar`, and `Turn` view split.

### Reshape `Models.kt`

Split current `Models.kt` into:

- transport-agnostic domain models
- UI/render models
- client update/RPC models

Recommended first split:

- `ThreadSummary`, workspace models -> `model/domain/ThreadModels.kt` and `WorkspaceModels.kt`
- `ConversationMessage`, `PlanStep`, turn state models -> `model/domain/TimelineModels.kt`
- runtime options -> `model/domain/RuntimeModels.kt`
- `ClientUpdate` and transport-specific update types -> `data/rpc/RpcModels.kt`

This reduces the current "everything in one model file" pressure before timeline logic gets more complex.

## Risks and Guardrails

### Highest-risk area: timeline behavior regression

Structural cleanup can easily break the behaviors explicitly called out in `AGENTS.md`. The new architecture must preserve:

- running fallback when `turn/started` lacks a usable `turnId`
- Stop fallback via `thread/read` when `activeTurnIdByThread` is missing
- reconnect/background recovery that restores active-turn state
- item-scoped assistant rows instead of timeline flattening
- late reasoning merges into existing rows
- ignoring late turn-less activity after a turn is already inactive
- history reconciliation that prefers item-aware matching over turn-only matching

This means the timeline subsystem should land before any large UX parity work that depends on it.

### Medium-risk area: service over-centralization

Remodex uses one large `CodexService`, but a direct Android clone could become another monolith. The Android version should keep one central service boundary while still splitting internal responsibilities into packages:

- connection
- timeline
- composer
- runtime
- workspace
- approvals

That preserves the useful Remodex pattern without rebuilding `MainViewModel` under a different name.

### Medium-risk area: workspace routing and repo isolation

Remodex-style structural cleanup must not regress Androdex's repo/workspace behavior:

- no filtering by selected repo in sidebar/content
- preserve cross-repo open/create flow
- preserve automatic local context switch
- keep workspace activation/service routing separate from thread UI

This is why `WorkspaceCoordinator` should remain a first-class service, not hidden in thread UI code.

### Medium-risk area: Android lifecycle differences

Remodex is iOS-first. Android lifecycle handling should keep existing behavior around:

- foreground reconnect retry
- benign background disconnect suppression
- QR/deep-link pairing intake
- avoiding expensive emulator-only refactor loops for small fixes

The architecture can borrow Remodex layering without copying Apple-specific assumptions.

## Recommended First Implementation Slice

The highest-value first slice is:

1. split `AndrodexApp.kt` into feature UI files
2. introduce `AndrodexService.kt`
3. move reconnect/runtime/workspace state ownership into service subpackages
4. add `TurnViewModel.kt` and `TurnComposerState.kt`
5. add `TimelineReducer.kt` plus `ThreadTimelineState`
6. keep `MainViewModel` only as a temporary compatibility shell

That slice produces the biggest structural improvement while keeping risk contained and creating the right base for the later parity tasks already listed in `Docs/remodex-parity-checklist.md`.

## Suggested Test Plan For The Refactor

Before and during migration, expand Android unit coverage around the new boundaries:

- `TimelineReducerTest`: assistant delta/completion merge, reasoning merge, item-scoped reconciliation
- `ActiveTurnTrackerTest`: missing-turn-id fallback, reconnect rehydrate, stop fallback preconditions
- `ReconnectControllerTest`: saved pairing retry, foreground/background transitions, update-required handling
- `TurnViewModelTest`: send availability, queue placeholder state, plan mode toggles
- `WorkspaceCoordinatorTest`: cross-project activation and thread creation routing

These tests are the Android equivalent of the Remodex coverage concentrated around `TurnTimelineReducerTests`, `CodexServiceIncomingRunIndicatorTests`, `CodexServiceImmediateSyncTests`, and `TurnViewModelQueueTests`.

## Bottom Line

To get structurally closer to Remodex, Androdex should move from:

- one root view model
- one large Compose file
- ad hoc timeline mutation in UI-facing code

to:

- one central service boundary with smaller internal coordinators
- feature-specific Android view models
- a dedicated timeline reducer/store
- a dedicated composer controller/state model
- split feature-oriented Compose files

That structure is the best fit for the next parity milestones and is the safest way to satisfy the timeline and host-local guardrails already defined for this repo.
