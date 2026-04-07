# T3 Code Support Plan

This document lays out the lowest-risk path for adding host-local T3 Code companion support to Androdex without destabilizing the current Codex bridge workflow.

The key correction after reviewing the cloned T3 Code repo is this:

- T3 Code is not a single provider kind in its own architecture
- T3 Code is a standalone host-local server/orchestration runtime
- T3 Code currently exposes provider kinds behind that server, notably `codex` and `claudeAgent`
- Androdex should therefore integrate with T3 Code as a runtime target, not by pretending `t3code` is a Codex-style provider

## Goals

- preserve the current Androdex product model
- keep the Android app as a paired remote client over the existing relay-safe bridge flow
- keep Codex-native support working unchanged as the default path
- add T3 Code support in an additive, reversible way
- let a user pick up their phone and safely continue supported T3 Codex work already in progress on the host
- keep provider- and runtime-specific behavior out of Android Compose screens
- avoid false assumptions about T3 Code protocol compatibility

## Non-Goals

- replacing the current Codex-native bridge path
- changing pairing, relay routing, recovery, or encrypted reconnect behavior
- exposing raw T3 Code server concepts directly in Android before the bridge contract is stable
- supporting multiple active runtime targets on the same Androdex bridge process in the first version
- turning Androdex into a full replacement for the T3 desktop/web product
- matching every T3 desktop feature, administration surface, or provider workflow in Android
- broadening Android information architecture around T3-native concepts before the companion path is proven

## Product Constraints And Guardrails

- Keep Codex-native as the default runtime target until T3 Code support is verified and hardened.
- Do not change saved pairing, reconnect, recovery, or relay behavior as part of this work.
- Do not regress the item-aware timeline behavior, active-turn recovery, or `thread/read` fallback logic.
- Do not reintroduce repo filtering in the sidebar or break cross-repo open/create flows.
- Keep shared logic in bridge/service/coordinator layers instead of duplicating provider logic in Compose screens.
- Treat this repo as open source: do not hardcode private domains, credentials, or private host runbooks.
- Keep the distinction clear between host-local runtime execution and remote Android connectivity.

## What The T3 Code Repo Confirms

These are no longer unknowns; they are facts from the cloned repo:

- T3 Code ships a standalone `t3` server binary from `apps/server`.
- The server uses HTTP plus WebSocket RPC, not a Codex-style stdin/stdout app-server.
- The WebSocket RPC protocol uses Effect RPC envelopes, with request frames tagged as `_tag: "Request"` and responses delivered as `_tag: "Exit"` and `_tag: "Chunk"` messages keyed by `requestId`.
- The server exposes RPC methods such as `server.getConfig`, `orchestration.getSnapshot`, `orchestration.dispatchCommand`, `orchestration.replayEvents`, and streaming subscriptions.
- T3 Code's orchestration layer owns projects, threads, messages, sessions, checkpoints, activities, and replayable event history.
- T3 Code currently models provider kinds as `codex` and `claudeAgent`, not `t3code`.
- Turn execution is command-driven through orchestration commands like `thread.turn.start`, `thread.turn.interrupt`, `thread.approval.respond`, `thread.user-input.respond`, `thread.checkpoint.revert`, and `thread.session.stop`.
- Runtime events are canonicalized into a rich event stream with item ids, turn ids, request ids, approvals, user-input requests, tool lifecycle updates, plan updates, content deltas, warnings, and errors.
- T3 Code supports snapshot plus event replay for recovery.
- T3 Code supports loopback/server auth configuration and should be launched headlessly for this use case.
- T3 Code also owns server-authoritative provider settings and provider status, including enabled state, binary paths, auth/install status, and available models.
- T3 Code stores projects with `workspaceRoot` and threads with `projectId`, `branch`, and `worktreePath`, so its thread model is not just "one current cwd plus a list of threads".
- T3 Code persists provider runtime metadata including resume cursors, runtime payload, provider name, and runtime mode per thread-backed session.
- T3 Code can reject provider switches for a thread, and provider/model changes may require session restart or may be unsupported depending on adapter capabilities.

## Implications For Androdex

The initial plan assumed "add provider kind `t3code`" behind the current bridge. That framing is too shallow and would likely send the implementation in the wrong direction.

The safer framing is:

- keep the existing Androdex bridge as the single paired host process
- add a second host runtime target inside the bridge:
  - `codex-native`
  - `t3-server`
- when `t3-server` is active, let the bridge talk to T3 Code over its WebSocket RPC surface
- translate T3 snapshot plus event-stream semantics into the existing Android-facing Androdex contract
- keep the first version focused on companion continuity for supported T3 Codex threads, not full T3 product parity

This keeps pairing, relay, and Android ownership unchanged while allowing the bridge to adapt a very different host runtime shape.

## Chosen V1 Decisions

These decisions close the biggest ambiguities so the implementation can proceed without reopening product scope every phase.

### 0. V1 product boundary

V1 is a narrow companion experience for T3-backed Codex work:

- open supported existing T3 Codex threads
- continue those threads from Android
- mirror active-turn, approval, user-input, plan, and reconnect state safely
- create new supported T3 Codex threads only where the bridge can do so without leaking broader T3 product concepts into Android

V1 is not:

- a promise of full feature parity with the T3 desktop/web app
- a promise that every T3 provider, project, script, diff, terminal, or admin workflow is available in Android
- a reason to reshape Androdex navigation or product identity around T3-specific concepts

### 1. V1 runtime target

V1 adds:

- `t3-server` as a new bridge runtime target

V1 does not add:

- multiple active runtime targets in one bridge process
- Android-side runtime target switching

Runtime target selection should stay host-config driven first.

### 2. V1 T3 provider scope

V1 supports:

- T3-backed threads that use the T3 `codex` provider

For any bridge-created T3 project or thread in v1, the bridge must set Codex provider identity explicitly:

- do not rely on T3 project `defaultModelSelection` if it points at a non-Codex provider
- when creating a T3-backed thread, send an explicit Codex `modelSelection` instead of inheriting provider choice implicitly
- if the active T3 project default provider is non-Codex, treat that as metadata to surface, not as the provider to use for Androdex-created v1 threads

V1 does not support:

- T3-backed `claudeAgent` threads as a fully interactive Androdex path

If the bridge sees non-Codex T3 threads in snapshot data during v1, it should:

- represent them clearly as unsupported or limited
- avoid exposing send/interrupt/runtime actions that would silently fail
- never pretend those threads are equivalent to Codex-native or T3-backed Codex threads

This keeps the first integration aligned with Androdex's existing product expectations while leaving room for mixed-provider support later.

### 3. V1 launch and attach model

For companion continuity, the bridge should prefer attaching to an already-running host-local T3 server/state when one is explicitly configured or safely discoverable for the paired host.

The bridge may spawn `t3` itself as a fallback or managed mode when no suitable existing T3 instance is available.

For v1, "suitable existing T3 instance" must be an explicit compatibility check, not a loose best-effort attach. At minimum the bridge should verify:

- host-local reachability only
- compatible server protocol/app version for the bridge adapter
- compatible auth mode so the bridge can authenticate without weakening the host-local security model
- expected T3 state-root or `baseDir` identity for replay-cursor scoping
- bridge-required RPC and subscription methods are present

If those checks fail, the bridge should refuse attach, surface the reason in bridge metadata, and either fall back to managed launch or keep T3 unavailable instead of attaching optimistically.

Expected launch characteristics:

- explicit loopback-only binding by default, not an implicit host default
- bridge-managed auth token delivered via bootstrap envelope/`--bootstrap-fd` when the bridge controls process launch
- browser auto-open disabled
- explicit bridge-owned working directory and state config
- `autoBootstrapProjectFromCwd` disabled so the bridge's workspace-to-project resolver stays authoritative

The first implementation should treat "continue the same host-local T3 state the user already has" as more important than "always spawn a fresh bridge-owned T3 instance."

### 4. V1 workspace authority

Androdex remains the authority for:

- workspace browsing
- workspace activation
- cross-repo open/create flow

T3 is treated as a runtime bound to the currently active host workspace.

When `t3-server` is active, the bridge should:

- map the active Androdex workspace `cwd` to a corresponding T3 project
- create or resolve the T3 project lazily as needed
- keep Androdex's existing workspace picker and repo-isolation model unchanged
- allow opening supported existing T3 threads from snapshot data even when those threads were not originally created by Androdex
- switch local context explicitly when the user opens a supported T3 thread associated with a different workspace/project, instead of hiding that thread
- keep supported T3 threads discoverable even when their recorded `worktreePath` or `workspaceRoot` is no longer available locally, but surface them as limited/read-only until the bridge can resolve or remap them safely

T3 project metadata should enrich thread presentation, not replace the Android workspace browser in v1.

### 5. V1 thread and recovery authority

For T3-backed threads:

- `orchestration.getSnapshot` is the source of truth for bootstrap state
- `subscribeOrchestrationDomainEvents` is the source of truth for live updates
- `orchestration.replayEvents` is the source of truth for reconnect recovery

The bridge should persist the last applied orchestration event sequence per paired host plus runtime target plus T3 state-root scope.

The bridge's connect/reconnect flow must be race-safe:

- do not rely on `getSnapshot` alone and then begin listening afterward
- either subscribe first and reconcile against the snapshot sequence, or call `getSnapshot` and then `replayEvents(snapshotSequence)` before declaring state caught up
- treat the orchestration sequence as a runtime-target-scoped recovery cursor, not a per-thread cursor
- publish runtime-target identity and capability metadata before any Android-visible thread list, timeline, or cached-thread reconciliation is emitted on fresh connect or after a same-host target switch

The bridge should not try to own or reinterpret T3 provider `resumeCursor` state; that is internal to T3 and should be treated as opaque runtime state owned by the T3 server.

### 6. V1 access mode and plan mode mapping

For T3-backed Codex threads, the bridge maps:

- Androdex `On-Request` access mode to T3 `approval-required` runtime mode
- Androdex `Full Access` access mode to T3 `full-access` runtime mode
- Androdex plan mode to T3 `interactionMode=plan`
- default non-plan operation to T3 `interactionMode=default`

App-wide defaults seed new T3-backed thread activity, but the effective per-thread mode shown in Android should come from T3 thread/session state once a thread exists.

### 7. V1 unsupported action policy

In v1, T3-backed threads should only expose actions that are explicitly mapped and verified.

If an action has no safe T3 mapping yet, the bridge should report it as unsupported instead of approximating it.

This especially applies to:

- thread fork
- background terminal cleanup
- compaction
- rollback if checkpoint revert semantics are not yet proven equivalent enough for Android expectations

## Corrected Architecture Principles

### 1. Runtime target abstraction first, provider metadata second

The bridge should distinguish between:

- runtime target: the host process Androdex is connected to
  - `codex-native`
  - `t3-server`
- backend provider: the provider running behind that runtime target
  - for T3 today, `codex` or `claudeAgent`

Android needs both concepts eventually. A single "provider kind" field is not enough.

### 2. Bridge owns translation

Android should continue to speak an Androdex-specific contract. The bridge should absorb T3-specific details such as:

- WebSocket transport
- auth token and loopback launch config
- orchestration snapshots
- event replay
- T3 thread/project/session ids
- provider kinds and model-selection shapes
- T3 project/workspace roots, branch, and worktree metadata
- T3 provider readiness, auth, install, and settings problems

The bridge should also preserve the companion boundary:

- expose only the subset of T3 state and actions Android actually needs for safe continuation
- avoid translating every T3 desktop-specific concept into Android just because it exists in the server contracts

### 3. One active runtime target per bridge process

The bridge should expose one active runtime target at a time in the first implementation.

That keeps complexity manageable for:

- daemon config
- workspace activation
- reconnect behavior
- persisted runtime defaults
- thread runtime overrides
- timeline cache scoping
- active-turn recovery

### 4. Thread identity and history must be runtime-target-safe

Provider-scoped model settings are not enough. The bridge and Android must treat thread identity as runtime-target-specific.

The bridge should expose a canonical Androdex thread identity that is namespaced by runtime target instead of letting Android rely on raw backend thread ids directly.

That means:

- do not assume Codex thread ids mean anything to T3 Code
- do not use raw T3 or Codex thread ids by themselves as Android cache keys, selected-thread restore keys, or active-turn map keys
- define one canonical thread-key format owned by the bridge so reconnect, cache invalidation, and same-host target switching all operate on stable Androdex identities
- do not reuse cached timelines across runtime targets just because the paired host id is the same
- do not carry per-thread runtime overrides across runtime-target switches unless the target thread namespace is proven compatible
- do not assume reconnect after a runtime-target switch can safely reopen prior thread state from another target

### 5. Capability gating must be runtime-driven, not name-driven

Android should gate controls from bridge-advertised capabilities, not from assumptions like "T3 has X" or "Codex has Y".

This is especially important for:

- plan mode
- approvals
- structured user input
- rollback or checkpoint revert
- thread fork equivalents
- background terminal cleanup
- service-tier controls
- account/auth status

That gating must also distinguish between:

- runtime target capabilities
- backend provider capabilities
- thread-level availability based on current thread provider, runtime mode, interaction mode, branch, and worktree state

### 6. Headless host launch is required

T3 Code should be launched in a way that preserves the Androdex host-local model:

- loopback-only by default
- bridge-managed auth token, preferably via bootstrap envelope/`--bootstrap-fd`
- no browser auto-open
- explicit host/runtime config
- `autoBootstrapProjectFromCwd` disabled unless Androdex intentionally opts into it
- no exposure of the T3 server outside the local machine unless the user intentionally configures that

### 7. Workspace authority must stay explicit

Androdex currently has a host-side workspace browser and a single active `cwd` concept. T3 Code has a richer project model with `workspaceRoot`, `projectId`, `branch`, and `worktreePath`.

The bridge must explicitly decide which side is authoritative for:

- workspace browsing
- workspace activation
- project creation
- thread-to-workspace association
- branch/worktree association

This cannot be left implicit or inferred ad hoc from thread state.

For companion support specifically:

- existing supported T3 threads remain discoverable even when they were started outside the current Android session
- opening such a thread may trigger an explicit local context switch
- Android should not require the user to think in terms of raw T3 project ids to continue work safely

## Android State That Must Be Runtime-Target-Aware

The original plan correctly called out model and reasoning persistence. That list needs to be broader.

At minimum, the following must become runtime-target-aware, and in some cases runtime-target-plus-backend-provider-aware:

- selected model id
- selected reasoning effort
- selected access mode
- selected service tier
- per-thread runtime overrides
- timeline cache scope keys
- thread hydration assumptions tied to cached thread ids
- runtime metadata shown in settings and connection status
- any thread-bound in-memory state that survives reconnect, such as active-turn recovery state
- replay cursors or equivalent recovery watermarks used for T3 event replay
- any cached workspace/project mapping derived from T3 `projectId` or `workspaceRoot`

Queued drafts and composer plan-mode state are currently in-memory, but they still need runtime normalization when capabilities change.

## Android And Timeline Risks That Must Become Explicit Requirements

The Androdex Android app depends on richer semantics than a simple "session/turn/thread read" abstraction.

The bridge adapter for T3 Code must explicitly handle:

- turn starts where a usable turn id may be delayed or absent initially
- item-scoped assistant, reasoning, plan, command, and tool updates
- structured user-input requests
- approval requests and resolution
- replay after reconnect
- late deltas that arrive after a turn is no longer active
- thread snapshots used to recover interruptible state
- recovery from persisted replay cursors without duplicating already-applied events
- thread-level provider changes or rejected provider changes
- thread-level runtime-mode and interaction-mode changes
- activity and checkpoint events that do not map 1:1 to existing Codex-native actions

For T3 Code specifically, snapshot plus replay should be treated as first-class recovery mechanisms, not incidental conveniences.

## Current Foundation

The current branch has useful groundwork, but the framing should be corrected:

- provider selection is currently centralized under `androdex-bridge/src/runtime/`
- the bridge still resolves to Codex by default
- `t3code` is currently registered only as a planned value that fails fast
- workspace activation does not currently switch runtime targets dynamically; provider/runtime selection is still bridge config, not a workspace activation parameter

This is still useful as a safe place to centralize future runtime-target selection, but it should be renamed conceptually from "provider selection" to "runtime target selection" before the implementation grows.

## Design Decisions Already Closed For V1

The following questions are now answered for the first implementation:

### A. What exactly are we integrating?

- T3 Code server/orchestration support

### B. How will the bridge talk to T3 Code?

- prefer attaching to an existing suitable host-local T3 server/state for continuity
- otherwise spawn `t3` with loopback and auth token configuration as a managed fallback

Direct attach to an already-running non-local T3 server remains deferred.

### C. What is Android's source of truth for T3-backed threads?

- the T3 orchestration snapshot as the source of truth for thread lists, titles, sessions, and latest turn state
- and the T3 event stream plus replay as the source of truth for live updates and recovery

### D. What is the authority model for projects and workspaces?

- keep the current Androdex workspace browser as the authority
- map T3 projects onto the currently active host workspace
- preserve current workspace activation semantics in Android
- allow existing supported T3 threads to reopen and drive local context switching without reintroducing repo filtering

### E. How do we represent identity on the Android side?

Bridge metadata should distinguish:

- runtime target display name
- runtime target kind
- active backend provider kind
- active backend provider display name
- capability set

### F. How do runtime mode and interaction mode map?

- app-wide Android defaults seed new T3-backed thread creation only
- once a T3-backed thread exists, the effective mode shown in Android comes from T3 thread/session state
- Android access mode changes during reconnect do not overwrite an existing T3 thread's effective mode implicitly
- if Android changes access defaults while reconnecting, those defaults apply only to future T3-backed thread creation unless the bridge successfully performs an explicit supported mode-change command for the current thread

### G. What is the migration and invalidation strategy on runtime-target switch?

- namespace or invalidate timeline caches by runtime target
- namespace runtime defaults by runtime target, and by backend provider where the value is provider-specific
- do not reuse selected thread ids, per-thread overrides, active-turn recovery state, replay checkpoints, or project/workspace mapping caches across incompatible runtime targets
- prefer invalidation over attempted cross-target migration whenever thread identity compatibility is uncertain

### H. Do we support all T3 backend providers in v1?

- V1 supports T3-backed `codex` threads only
- non-Codex T3 threads must be detected and gated explicitly

## Deferred Decisions

These are intentionally deferred until after the v1 bridge path is stable:

- support for mixed-provider T3 threads with full Android interactivity
- support for connecting to an already-running non-local T3 server
- Android-side runtime-target switching
- replacing Androdex workspace browsing with T3-native project browsing
- broad T3 desktop-feature parity in Android

## Proposed Integration Shape

### Runtime targets

- `codex-native`
- `t3-server`

### Bridge adapters

- `CodexNativeRuntimeAdapter`
- `T3ServerRuntimeAdapter`

### Android-facing metadata

The bridge should eventually report something like:

- runtime target kind
- runtime target display name
- backend provider kind, if any
- backend provider display name, if any
- capabilities:
  - model selection
  - reasoning controls
  - access mode controls
  - service-tier controls
  - plan mode
  - approvals
  - tool/user-input requests
  - rollback or checkpoint revert
  - thread fork equivalent
  - background terminal cleanup
  - account/auth status
  - desktop refresh support
  - companion continuity for existing supported T3 threads

## Required Workstreams

### A. Runtime-target contract correction

- rename the implementation concept from "provider support" to "runtime target support"
- keep backend provider metadata as a separate concept
- update docs and naming before the bridge adapter work expands

### B. T3 protocol discovery and mapping

- document the T3 launch model
- document required auth token and host binding behavior
- document the RPC methods Androdex needs
- document the orchestration snapshot and replay model
- map T3 command and event semantics to Androdex semantics

### C. Bridge runtime-target abstraction

- extract the current Codex-native path behind a runtime-target adapter
- preserve current pairing, relay, and workspace behavior
- make the bridge own target-specific lifecycle and transport details

### D. T3 server bridge adapter

- add T3 launch and connection management
- add attach-to-existing-host-local-T3 handling for companion continuity
- call `server.getConfig` during bootstrap and on provider/config refresh boundaries
- subscribe to `subscribeServerConfig` and `subscribeServerLifecycle` as supporting metadata streams
- subscribe to T3 server lifecycle, config, and orchestration streams
- use a race-safe `orchestration.getSnapshot` plus replay bootstrap on connect
- use `orchestration.replayEvents` for recovery
- translate T3 thread/session/message/activity/checkpoint state into the current Android-facing contract
- persist and restore orchestration event sequence checkpoints safely
- define how T3 project/workspace metadata maps to Androdex `cwd` and project grouping
- gate unsupported backend providers in v1 before Android exposes thread actions
- make "open and continue existing supported T3 Codex threads" a first-class path, not just new-thread creation

### E. Android contract extension

- extend runtime metadata to include runtime target and backend provider identity
- extend capabilities beyond the current Codex-specific assumptions
- keep compatibility defaults for older bridges
- add enough metadata to explain provider readiness, install, auth, and configuration failures before the user attempts a turn
- require bootstrap ordering where runtime-target identity and capability metadata arrive before thread/timeline payloads are allowed to hydrate Android state

### F. Persistence and cache migration

- namespace runtime defaults by runtime target and backend provider where needed
- namespace timeline cache scopes by runtime target, not only paired host identity
- clear or segregate incompatible per-thread state on target switch
- keep current Codex-native users stable during migration
- store orchestration event sequence checkpoints in a way that survives reconnect and app relaunch without duplicating timeline rows

### G. Android UI gating and labeling

- show runtime target identity where it reduces confusion
- optionally show backend provider identity when relevant
- gate controls by capability
- do not expose dead buttons or misleading labels
- surface provider readiness or configuration failures clearly when the T3 server is up but no usable backend provider is available

### H. Documentation and QA

- document host requirements for T3
- document loopback/auth-token/no-browser launch expectations
- document the companion boundary clearly: what Android continues versus what still belongs to the T3 desktop/web app
- add a runtime-target switch smoke matrix

## Required Risk Mitigations

These are not optional nice-to-haves. They are the minimum controls needed to make the T3 integration safe enough to ship.

### 1. Replay and timeline correctness mitigation

- treat the T3 adapter as an event normalizer, not just a transport bridge
- maintain a durable orchestration replay cursor scoped by paired host plus runtime target plus T3 state-root identity, derived from T3 orchestration sequence state
- make replay application idempotent so reconnect cannot duplicate already-applied timeline rows
- merge snapshot bootstrap and replay in a deterministic order before Android-visible state is emitted
- preserve Androdex item-aware row reconciliation rules, including late-delta merging and turn-less interrupt fallback behavior
- add reducer-level tests that replay the same event span twice and prove the Android-facing timeline is unchanged after the second pass

### 2. Runtime-target-safe persistence mitigation

- version and namespace persisted runtime state by paired host plus runtime target plus T3 state-root identity, and by backend provider where the value is provider-specific
- do not migrate selected thread ids, per-thread overrides, active-turn state, or replay checkpoints across incompatible runtime targets
- invalidate T3-scoped replay cursors, caches, and thread assumptions when the configured T3 `baseDir`/state root changes
- prefer invalidation of incompatible state over best-effort reuse
- add explicit migration behavior for older installs so Codex-native users keep their current defaults without inheriting T3-only state
- require runtime-target identity to be known before cached runtime defaults, thread overrides, or timeline state are rehydrated after connect or same-host target switch

### 3. Workspace and project mapping mitigation

- define one canonical resolver from Androdex active `cwd` to T3 `projectId`
- derive thread presentation from explicit `worktreePath` or project `workspaceRoot`, not from ad hoc title or recent-thread heuristics
- keep Android workspace selection authoritative even when T3 reports multiple projects
- prove cross-repo open/create flows still switch local context correctly when the backing thread is T3-backed
- define an orphaned-thread state for supported T3 threads whose `worktreePath` or `workspaceRoot` no longer resolves locally, and gate send/interrupt/workspace-sensitive actions until the mapping is repaired

### 4. Runtime-mode and provider authority mitigation

- treat T3 thread/session state as authoritative for existing T3-backed thread mode display
- only change runtime mode, interaction mode, or model/provider through explicit supported bridge commands
- gate non-Codex T3 threads before any send, interrupt, rollback, or fork-like action is exposed
- surface provider rejection, restart-required behavior, and readiness failures in bridge metadata before the user attempts a turn

### 5. Multi-client continuity mitigation

- assume desktop T3 and Android may observe or mutate the same supported thread over time
- make "open existing thread and continue safely" a release-blocking path, not a nice-to-have
- ensure reconnect and replay tolerate thread changes made outside Androdex by the desktop T3 client
- avoid optimistic Android-only assumptions about thread ownership, latest-turn authorship, or locally-created session history
- define stale-action handling for approval, user-input, interrupt, and similar thread mutations so an action already resolved on desktop refreshes cleanly and fails without ambiguous UI state

### 6. Adapter observability mitigation

- emit structured bridge logs for runtime target, backend provider, and canonical thread identity where relevant
- log T3 state-root identity, snapshot sequence, replay start/end sequence, and duplicate-suppression outcomes during bootstrap and reconnect
- log suitability-check rejection reasons for existing-instance attach attempts
- log action gating reasons and stale-action rejection reasons for unsupported, orphaned, or already-resolved thread mutations
- keep observability host-local and open-source-safe: no private domains, credentials, prompt contents, or sensitive payload dumps in normal logs

### 7. Codex-native regression mitigation

- keep a parity test suite for the extracted Codex-native adapter before enabling T3-backed runtime selection
- avoid changing Android screens first; keep T3-specific branching in bridge and service layers
- require the same-host `codex-native -> t3-server -> codex-native` path to pass before T3 is considered releasable

## Corrected Rollout

### Phase 0: Foundation cleanup

- keep Codex-native as the default target
- keep the current fast-fail behavior for unimplemented T3 support
- rename internal design language from provider selection to runtime-target selection

### Phase 1: T3 facts and bridge contract

- confirm T3 launch and auth model
- confirm existing-instance attach and continuity model for host-local T3
- confirm the exact subset of RPC and stream methods required
- define the suitability predicate for attaching to an existing T3 instance
- define the canonical Androdex thread identity format and namespace rules
- write the T3-to-Androdex mapping document
- define runtime target metadata and capability reporting
- define replay, snapshot-merge, and item-reconciliation invariants for the adapter
- define stale-action handling and bridge observability requirements for the adapter

Deliverables:

- T3 runtime notes
- runtime-target adapter contract
- bridge metadata contract
- timeline normalization and replay invariants document
- canonical thread identity and stale-action rules

### Phase 2: Extract Codex-native runtime adapter

- put the current Codex-native path behind a runtime-target adapter
- keep user-visible behavior unchanged
- preserve all existing timeline and reconnect behavior

Status update:

- completed
- landed runtime-target config and adapter seams in the bridge
- kept the Codex-native path behavior-preserving while moving send/transport orchestration behind the adapter boundary

Deliverables:

- `CodexNativeRuntimeAdapter`
- behavior-preserving tests

### Phase 3: Bridge metadata and cache scoping

- extend bridge status and Android runtime payloads with runtime target identity
- add backend provider identity fields for T3-backed sessions
- update timeline cache scope keys and persisted runtime defaults to be runtime-target-safe
- define invalidation rules for thread ids, overrides, active-turn state, and replay checkpoints on runtime-target change
- enforce metadata-first bootstrap ordering so Android never hydrates thread/timeline state before the active runtime target is known

Status update:

- completed
- landed bridge and Android runtime-target metadata
- landed runtime-target-safe timeline cache scoping and per-thread runtime-override scoping
- landed runtime-target-change invalidation in Android service state
- metadata-first bootstrap ordering is in place for runtime-target identity before Android-visible thread hydration
- landed canonical runtime-target-scoped Androdex thread identity at the bridge boundary so Android no longer depends on raw backend thread ids on the wire

Deliverables:

- runtime-target metadata contract
- persistence and cache migration
- compatibility handling for older bridges
- runtime-target invalidation matrix

### Phase 4: T3 server adapter implementation

- ship a read-only adapter milestone first: connect, bootstrap, replay, capability reporting, thread discovery, timeline rendering, reconnect recovery, and workspace mapping before enabling mutating thread actions
- attach to an existing suitable host-local T3 server when available
- otherwise launch bridge-managed local T3 server
- authenticate bridge-to-T3 communication
- bootstrap from `orchestration.getSnapshot` plus race-safe replay reconciliation
- recover from `orchestration.replayEvents`
- translate T3 orchestration commands and canonical runtime events into Androdex updates
- implement idempotent replay application and duplicate suppression
- implement explicit workspace-to-project resolution
- detect orphaned supported T3 threads whose local workspace mapping no longer resolves, keep them discoverable, and gate actions until repaired
- enable mutating actions only after the read-only milestone proves timeline correctness and reconnect safety
- implement authoritative thread mode/provider labeling and unsupported-thread gating
- implement stale-action detection and clean refresh behavior when desktop has already resolved an approval, user-input request, or interruptible turn
- implement structured adapter logging for attach, bootstrap, replay, gating, and stale-action outcomes
- prove continuity for supported threads created outside Androdex

Status update:

- in progress
- landed the read-only T3 attach milestone foundation:
  - explicit `t3-server` runtime target support
  - loopback-only suitability checks for protocol/auth/state-root/method presence
  - readiness-gated workspace activation
  - bridge-managed read-only gating for mutating T3 actions
  - initial `orchestration.getSnapshot` bootstrap
  - local synthesis of `thread/list`, `thread/read`, and `thread/resume` from the T3 snapshot
  - Effect RPC transport alignment for real T3 request/exit/chunk envelopes
  - buffered `subscribeOrchestrationDomainEvents` handling with gap recovery through `orchestration.replayEvents`
  - state-root-scoped replay cursor persistence for the bridge-side T3 read model
  - bridge-managed live notifications for resumed supported T3 Codex threads, now covering turn lifecycle, assistant message completion, title refresh, plan updates, reasoning/task activity cards, and tool execution activity cards
  - reconnect recovery across T3 transport restarts now re-enters through snapshot plus replay using the persisted state-root-scoped watermark, without duplicating already-delivered resumed-thread notifications
  - resumed-thread activity notifications now suppress duplicate plan/task/tool emissions when replay or live overlap re-delivers the same T3 activity identity, and reconnect coverage now proves only newly recovered activity completions surface after restart
  - `thread/list`, `thread/read`, and `thread/resume` now expose explicit bridge capability metadata for supported, unsupported-provider, and unresolved-workspace T3 threads instead of relying on preview text alone
- not landed yet:
  - broader Android-visible live thread/timeline push semantics beyond the current resumed-thread plan/task/tool/title/assistant subset
  - full duplicate suppression and replay idempotency coverage across broader event shapes outside the current resumed-thread title/assistant/plan/task/tool surface
  - workspace/project remapping and orphaned-thread action gating beyond the new capability metadata surface
  - mutating T3 actions
  - structured adapter logging

Deliverables:

- `T3ServerRuntimeAdapter`
- read-only adapter checkpoint signoff before mutating actions
- T3-specific integration tests
- replay-idempotency coverage

### Phase 5: Android UI and action gating

- surface runtime target identity
- gate unsupported actions cleanly
- ensure T3-backed threads do not expose Codex-native-only actions unless implemented safely

Status update:

- in progress
- landed Android-side consumption of bridge capability metadata for unsupported-provider and unresolved-workspace T3 threads:
  - composer input, send, stop, plan/subagent toggles, and runtime access now disable from per-thread capability metadata instead of assuming every thread is safely mutable
  - pending tool-input cards now surface capability gating reasons and disable submission when the bridge marks tool-input responses unsupported
  - Android service actions now hard-reject blocked `send`, `review`, `interrupt`, `approval`, tool-input response, and rollback requests using the same per-thread capability reasons shown in the UI
- not landed yet:
  - stale-action reconciliation after desktop resolves an approval, user-input request, or interruptible turn first
  - broader Android-visible live thread/timeline push semantics beyond the current resumed-thread turn/assistant/title/plan/task/tool subset
  - structured logging for attach, bootstrap, replay, and action-gating outcomes

Deliverables:

- capability-gated UI
- provider/runtime-aware labels
- presentation tests

### Phase 6: End-to-end hardening

- clean host startup
- reconnect and relaunch recovery
- runtime-target switching on the same paired host
- thread recovery after snapshot plus replay
- continue threads already in progress in the user's existing T3 environment
- workspace switching and cross-repo flows
- timeline correctness under delayed and late events
- existing Codex-native parity after the adapter extraction

Deliverables:

- smoke checklist
- rollback plan
- updated troubleshooting docs
- release-blocking regression checklist

## Testing And Validation Matrix

At minimum we need coverage for:

### Unit and integration tests

- runtime-target config parsing
- Codex-native adapter parity
- T3 launch and auth-token handling
- T3 existing-instance attach behavior
- T3 suitability-check rejection behavior for incompatible existing instances
- canonical thread identity generation and runtime-target namespacing
- T3 snapshot bootstrap
- T3 replay-based recovery
- replay idempotency when the same event range is applied twice
- snapshot-plus-replay merge ordering
- runtime-target-aware persistence migration
- runtime-target-aware timeline cache scoping
- metadata-first bootstrap ordering on fresh connect and same-host runtime-target switch
- capability-gated UI state
- replay checkpoint persistence and duplicate-event suppression
- provider-readiness and provider-misconfiguration handling
- workspace/project mapping behavior for T3-backed threads
- orphaned supported T3 thread handling when local workspace paths no longer resolve
- cross-repo open/create behavior for T3-backed threads
- continuity for supported T3 Codex threads created outside Androdex
- runtime-mode and interaction-mode mapping behavior
- T3 provider-switch rejection behavior
- explicit gating behavior for non-Codex T3 threads in v1
- stale-action handling when desktop has already resolved an approval, user-input request, or interrupt target
- structured logging coverage for replay bootstrap, attach refusal, and action-gating reasons

### Manual smoke coverage

- pair with a fresh install
- reconnect from saved pairing
- recover after background/foreground and app relaunch
- switch the same paired host from `codex-native` to `t3-server`
- switch it back again without re-pairing
- attach to a host-local T3 instance the user was already using before opening Androdex
- verify the bridge refuses an incompatible existing T3 instance cleanly and either falls back safely or reports T3 unavailable
- verify the read-only milestone can connect, render, replay, and recover without any mutating actions enabled
- connect to T3 when one backend provider is installed/authenticated and another is unavailable
- verify T3-backed Codex works when T3 also reports unsupported non-Codex threads
- open existing T3-backed threads
- open an existing supported T3 Codex thread created from the desktop app and continue it from Android
- open a supported T3 thread whose recorded workspace no longer exists locally and verify it stays discoverable but action-gated
- create a new T3-backed thread
- send a turn and stream output
- interrupt an active turn
- handle approval and user-input requests
- resolve an approval, user-input request, or running turn from desktop first, then verify Android stale actions refresh and fail cleanly
- revert checkpoints or gate rollback cleanly if the mapping is not ready
- verify plan mode behavior when supported and unsupported
- verify workspace switching and cross-repo flows
- verify T3 project/workspace roots map correctly into Androdex thread/project presentation
- verify thread behavior when T3 backend provider differs across threads, if that is supported
- verify timeline ordering and late-delta merging
- verify reconnect does not duplicate timeline rows after replay
- verify stop/interrupt still works when the latest running turn must be recovered from thread state
- verify Android tolerates thread updates that happened from the desktop T3 client while the phone was disconnected
- verify no stale Codex-native thread cache bleeds into T3-backed threads
- verify no stale T3 thread state bleeds back into Codex-native mode
- verify structured logs are sufficient to explain attach refusal, replay ranges, duplicate suppression, action gating, and stale-action rejection without leaking sensitive payload data

### Regression coverage for existing Codex-native users

- no change to default runtime target selection
- no change to pairing or reconnect reliability
- no loss of current saved Codex-native defaults after migration
- no regressions in existing fork, rollback, compaction, or background-terminal cleanup flows on the Codex-native path

## Rollback Strategy

If T3 support causes instability:

- keep Codex-native as the default runtime target
- allow disabling T3 support by bridge config or env without affecting pairing state
- keep Android resilient to missing runtime-target metadata
- prefer capability gating over partial emulation
- invalidate incompatible T3-scoped caches rather than trying to salvage them unsafely

## Risks And Watchouts

- Do not model T3 Code as a single provider kind; in its own contracts it is a server/orchestration runtime.
- Do not assume T3 thread ids, checkpoints, or sessions are compatible with Codex-native ids or recovery logic.
- Do not let Android or bridge persistence depend on raw backend thread ids when a canonical runtime-target-scoped thread identity is required.
- Do not reuse timeline caches across runtime targets for the same paired host.
- Do not persist access mode or service-tier settings in global shared keys across runtime targets.
- Do not assume T3 rollback semantics match current Codex-native rollback semantics.
- Do not assume T3 has a safe equivalent for fork, compaction, or background terminal cleanup.
- Do not assume T3 project/workspace authority naturally matches the current Androdex workspace browser model.
- Do not attach to an existing T3 instance until protocol/version/auth/state-root suitability checks pass.
- Do not assume one T3 server implies one backend provider across all threads.
- Do not assume Android's app-wide access mode maps directly onto T3's per-thread `runtimeMode` without bridge translation.
- Do not treat snapshot plus replay as sufficient unless replay checkpoints are persisted and duplicate events are suppressed.
- Do not let Android hydrate cached thread or runtime state before the active runtime target identity is established.
- Do not hide supported T3 threads just because their original local workspace path is currently missing; gate them explicitly instead.
- Do not enable mutating T3 actions before the read-only adapter milestone proves bootstrap, replay, and reconnect correctness.
- Do not assume Android-originated actions are the only mutations; desktop may have already resolved the same approval, user-input request, or running turn.
- Do not expose T3 support in Android until runtime-target metadata, cache scoping, and capability gating are ready.
- Do not launch T3 in a way that opens a browser or binds broadly by default.
- Do not quietly expand the scope from "safe companion for supported T3 Codex threads" into "full T3 mobile product" without a separate product decision.

## Definition Of Done

T3 support is complete when all of the following are true:

- the bridge can run either `codex-native` or `t3-server` locally
- Codex-native remains the default and existing users are not broken
- Android receives runtime target identity and capability metadata from the bridge
- backend provider identity is available when relevant for T3-backed threads
- unsupported T3 backend providers are surfaced and gated cleanly in v1
- canonical runtime-target-scoped Androdex thread identity is used for cache scoping and thread restore
- runtime defaults, thread overrides, and timeline caches are runtime-target-safe
- replay is idempotent and reconnect does not duplicate timeline rows
- unsupported features are gated cleanly
- pairing, reconnect, timeline behavior, and workspace switching still work
- T3-backed reconnect uses snapshot plus replay safely
- same-host switching between `codex-native` and `t3-server` does not bleed thread or mode state across targets
- stale Android actions against desktop-resolved state fail cleanly and trigger fresh state reconciliation
- users can open and continue supported existing T3 Codex threads from Android without Androdex pretending to replace the whole T3 desktop product
- read-only adapter milestone passed before mutating T3 actions were enabled
- structured bridge logs are sufficient to diagnose attach, bootstrap, replay, gating, and stale-action failures
- docs and troubleshooting guidance are updated
- automated and manual smoke coverage pass for both runtime targets

## Implementation Progress

Completed so far:

- runtime-target framing and adapter extraction in the bridge
- Codex-native adapter migration without changing the default host path
- bridge and Android runtime-target metadata plumbing
- runtime-target-safe persistence for timeline caches and thread runtime overrides
- runtime-target switch invalidation for selected thread, pending turn state, and cached thread data
- explicit T3 attach suitability checks with loopback-only endpoint enforcement
- readiness-gated T3 workspace activation so incompatible T3 instances never appear active
- initial read-only T3 snapshot bootstrap via `server.getConfig` plus `orchestration.getSnapshot`
- snapshot-backed synthesis for `thread/list`, `thread/read`, and `thread/resume`
- bridge-side T3 protocol transport now speaks the real Effect RPC request/exit/chunk envelope instead of a JSON-RPC-style placeholder
- T3 live cache updates now come from `subscribeOrchestrationDomainEvents`, with replay-gap recovery through `orchestration.replayEvents`
- T3 replay cursors are now persisted by runtime target plus state-root scope inside bridge daemon config
- resumed supported T3 Codex threads now receive bridge-managed live turn/assistant/title notifications from the synchronized T3 cache
- resumed supported T3 Codex threads now also receive bridge-managed live plan updates plus task/tool activity notifications mapped onto Android's existing item protocol
- resumed-thread live notifications now re-check current Codex/workspace eligibility and handle active-turn replacement without leaving Android pinned to a stale turn id
- Android-visible thread ids are now canonicalized by the bridge as runtime-target-scoped Androdex thread identities, while host-runtime forwarding still resolves back to raw backend thread ids for compatibility
- T3 transport restarts now recover through snapshot plus replay from the persisted state-root-scoped watermark, while suppressing duplicate live notifications for already-delivered resumed-thread events
- resumed-thread plan/task/tool notifications now suppress duplicate emissions when the same T3 activity id is re-delivered, and reconnect regression coverage now proves old activity notifications stay suppressed while newly replayed completions still surface with stable item identity
- T3 summaries, reads, and resume responses now carry explicit bridge capability metadata for companion support state, workspace availability, live-update eligibility, and read-only action gating so Android no longer has to infer unsupported/orphaned state from preview text alone
- Android now consumes that per-thread capability metadata in the timeline UI and service layer, disabling blocked composer/tool-input/rollback flows and surfacing the bridge-provided reason before mutating requests are attempted

Still in progress:

- broader Android-visible live thread/timeline push semantics on top of the synchronized T3 bridge cache, beyond the current resumed-thread turn/assistant/title/plan/task/tool subset
- broader replay checkpoint persistence, duplicate suppression, and idempotent merge coverage outside the currently hardened resumed-thread title/assistant/plan/task/tool subset
- stale-action reconciliation for desktop-resolved approvals, user-input requests, and interrupts
- structured logging for attach, bootstrap, replay, and action-gating outcomes

Not started yet:

- T3 mutating command mapping
- end-to-end smoke hardening for T3 reconnect and cross-repo continuity

## Immediate Next Steps

1. Implement stale-action reconciliation for approvals, tool-input requests, and interrupts that desktop T3 may already have resolved before Android responds.
2. Expand duplicate suppression and replay-idempotency coverage beyond the current resumed-thread title/assistant/plan/task/tool subset.
3. Write the adapter invariants doc for snapshot merge order, replay checkpoints, duplicate suppression, metadata-first bootstrap ordering, stale-action handling, and item-aware timeline reconciliation.
4. Define the structured logging fields needed to debug attach refusal, replay progression, duplicate suppression, gating, and stale-action outcomes without leaking sensitive payload data.
5. Write a capability matrix for `codex-native` vs `t3-server` and explicitly mark the v1 T3 scope as companion support for Codex-backed threads only.
