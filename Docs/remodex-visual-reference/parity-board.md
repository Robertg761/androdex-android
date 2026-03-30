# Parity Board

This board is the screen-by-screen mapping from canonical Remodex state to the Androdex surface that must visually converge on it.

## Legend

- `Remodex evidence`: the upstream screen, component, or test that defines the state
- `Androdex target`: the Android file that must match the state visually
- `Board status`: whether we already have a named capture slot and enough source evidence to begin implementation

## Board

| Area | Remodex state | Remodex evidence | Androdex target | Board status | Phase 13 audit |
| --- | --- | --- | --- | --- | --- |
| Pairing | QR scanner idle / overlay / permission states | `Views/QRScannerView.swift` | `android/app/src/main/java/io/androdex/android/ui/pairing/PairingScreen.kt` | ready | `Not matched` |
| Onboarding | Welcome hero and feature pages | `Views/Onboarding/OnboardingWelcomePage.swift`, `Views/Onboarding/OnboardingFeaturesPage.swift`, `Views/Onboarding/OnboardingStepPage.swift` | `android/app/src/main/java/io/androdex/android/ui/pairing/PairingScreen.kt` | ready | `Acceptable Android compromise` |
| Home | Offline / connected / syncing empty state | `Views/Home/HomeEmptyStateView.swift` | `android/app/src/main/java/io/androdex/android/ui/home/HomeScreen.kt` | ready | `Perceptually exact` |
| Home | Trusted pair summary and completion banner | `Views/Shared/TrustedPairSummaryView.swift`, `Views/Home/ThreadCompletionBannerView.swift` | `android/app/src/main/java/io/androdex/android/ui/home/HomeScreen.kt` | ready | `Acceptable Android compromise` |
| Sidebar | Header, search, new chat, grouped threads | `Views/SidebarView.swift`, `Views/Sidebar/SidebarHeaderView.swift`, `Views/Sidebar/SidebarSearchField.swift`, `Views/Sidebar/SidebarThreadRowView.swift` | `android/app/src/main/java/io/androdex/android/ui/sidebar/SidebarContent.kt` | ready | `Perceptually exact` |
| Sidebar | Grouping, expansion, archived bucket, worktree labeling | `Views/Sidebar/SidebarThreadGrouping.swift`, `CodexMobileTests/SidebarThreadGroupingTests.swift` | `android/app/src/main/java/io/androdex/android/ui/sidebar/SidebarContent.kt` | ready | `Perceptually exact` |
| Sidebar | Initial loading overlay behavior | `Views/SidebarView.swift`, `CodexMobileTests/SidebarThreadsLoadingPresentationTests.swift` | `android/app/src/main/java/io/androdex/android/ui/sidebar/SidebarContent.kt` | ready | `Acceptable Android compromise` |
| Thread shell | Navigation title, subtitle, thread actions, git pills | `Views/Turn/TurnView.swift`, `Views/Turn/TurnToolbarContent.swift` | `android/app/src/main/java/io/androdex/android/ui/turn/ThreadTimelineScreen.kt` | ready | `Acceptable Android compromise` |
| Thread timeline | Empty state, scroll container, pinned plan accessory | `Views/Turn/TurnConversationContainerView.swift`, `Views/Turn/TurnTimelineView.swift` | `android/app/src/main/java/io/androdex/android/ui/turn/ThreadTimelineScreen.kt` | ready | `Acceptable Android compromise` |
| Thread messages | User / assistant / system / thinking / file-change / execution / review content | `Views/Turn/TurnMessageComponents.swift`, `Views/Turn/CommandExecutionViews.swift` | `android/app/src/main/java/io/androdex/android/ui/turn/ThreadTimelineScreen.kt` | ready | `Perceptually exact` |
| Composer | Floating shell, placeholder, chips, attachments, send / stop controls | `Views/Turn/TurnComposerView.swift`, `Views/Turn/ComposerBottomBar.swift`, `Views/Turn/ComposerAttachmentsPreview.swift` | `android/app/src/main/java/io/androdex/android/ui/turn/ComposerBar.kt` | ready | `Not matched` |
| Composer | File / skill / slash autocomplete panels | `Views/Turn/FileAutocompletePanel.swift`, `Views/Turn/SkillAutocompletePanel.swift`, `Views/Turn/SlashCommandAutocompletePanel.swift` | `android/app/src/main/java/io/androdex/android/ui/turn/ComposerBar.kt` | ready | `Not matched` |
| Composer | Review and subagents armed states | `Views/Turn/TurnComposerView.swift`, `CodexMobileTests/TurnComposerReviewModeTests.swift` | `android/app/src/main/java/io/androdex/android/ui/turn/ComposerBar.kt` | ready | `Not matched` |
| Composer | Queued drafts and voice recording | `Views/Turn/QueuedDraftsPanel.swift`, `Views/Turn/VoiceRecordingCapsule.swift` | `android/app/src/main/java/io/androdex/android/ui/turn/ThreadTimelineScreen.kt`, `android/app/src/main/java/io/androdex/android/ui/turn/ComposerBar.kt` | ready | `Not matched` |
| Git | Toolbar actions, branch selector, diff sheet, worktree handoff | `Views/Turn/TurnGitActionsToolbar.swift`, `Views/Turn/TurnGitBranchSelector.swift`, `Views/Turn/TurnDiffSheet.swift`, `Views/Turn/TurnWorktreeHandoffOverlay.swift` | `android/app/src/main/java/io/androdex/android/ui/turn/GitSheet.kt`, `android/app/src/main/java/io/androdex/android/ui/turn/ThreadTimelineScreen.kt` | ready | `Not matched` |
| Runtime | Status sheet and usage summary | `Views/Turn/TurnStatusSheet.swift`, `Views/Shared/UsageStatusSummaryContent.swift` | `android/app/src/main/java/io/androdex/android/ui/turn/ThreadRuntimeSheet.kt` | ready | `Not matched` |
| Project picker | New chat project chooser | `Views/SidebarView.swift`, `Views/Sidebar/SidebarThreadGrouping.swift` | `android/app/src/main/java/io/androdex/android/ui/home/ProjectPickerSheet.kt` | ready | `Not matched` |
| Settings | Settings cards, trusted Mac card, runtime defaults | `Views/SettingsView.swift` | `android/app/src/main/java/io/androdex/android/ui/settings/RuntimeSettingsSheet.kt` | ready | `Perceptually exact` |
| About | Editorial about screen and architecture diagram | `Views/AboutRemodexView.swift` | `android/app/src/main/java/io/androdex/android/ui/settings/AboutAndrodexSheet.kt` | ready | `Not matched` |
| Dialogs | Confirmation dialogs and alerts | `Views/SidebarView.swift`, `Views/Turn/TurnViewAlertModifier.swift` | thread and sidebar alert surfaces in Android | ready | `Perceptually exact` |
| Recovery | Missing thread, reconnect fallback, notification-open states | `CodexMobileTests/CodexPushNotificationRegistrationTests.swift`, app runtime flow plus push/open navigation behavior | `android/app/src/main/java/io/androdex/android/MainViewModel.kt`, pairing/home/thread recovery surfaces | runtime capture backlog only | `Not matched` |

## Phase 13 Audit Notes

Status labels follow the clone plan language:

- `Exact`: no meaningful visible drift remains
- `Perceptually exact`: Android matches the same product feel in current archived evidence
- `Acceptable Android compromise`: remaining differences are documented platform limits or runtime constraints
- `Not matched`: either the surface still drifts visibly or the archival Android evidence is still missing

The repo-wide enforcement and backlog note for these statuses now lives in [`phase13-parity-audit.md`](/Users/robert/Documents/Projects/androdex/Docs/remodex-visual-reference/phase13-parity-audit.md).

## Android Capture Anchors

The March 29, 2026 real-device pass gives us verified Android-side anchors for these board areas:

- Pairing: `android-device-captures/pairing--qr-scanner-idle--androdex.png`
- Recovery: `android-device-captures/recovery--repair-pairing-required--androdex.png`
- Sidebar shell: `android-device-captures/sidebar--closed--androdex.png`
- Sidebar open/search/grouping: `android-device-captures/sidebar--open-default--androdex.png`, `android-device-captures/sidebar--search-focused--androdex.png`, `android-device-captures/sidebar--groups-expanded--androdex.png`
- Thread/composer shell: `android-device-captures/thread--streaming--androdex.png`
- Settings: `android-device-captures/settings--root--androdex.png`

Phase 1 design-system refresh anchors:

- Pairing reconnect shell after semantic token + primitive pass: `android-device-captures/phase1/phase1-pairing-refresh.png`
- Home after semantic token + primitive pass: `android-device-captures/phase1/phase1-home-refresh.png`
- Sidebar open/search/grouped shell after semantic token + primitive pass: `android-device-captures/phase1/phase1-sidebar-open-refresh.png`
- Runtime UI dumps currently verified for the same pass: `android-device-captures/phase1/phase1-home-refresh.xml`, `android-device-captures/phase1/phase1-sidebar-open-refresh.xml`
- Pairing refresh dump available for future cleanup: `android-device-captures/phase1/phase1-pairing-refresh.xml`

Phase 2 shell refresh anchors:

- Connected home shell after the shared-shell and edge-to-edge pass: `android-device-captures/phase2/phase2-home-shell.png`
- Drawer reveal after the shared-shell pass: `android-device-captures/phase2/phase2-sidebar-open-shell.png`
- Thread shell after the shared-shell pass: `android-device-captures/phase2/phase2-thread-shell.png`
- Matching Phase 2 UI dumps: `android-device-captures/phase2/phase2-home-shell.xml`, `android-device-captures/phase2/phase2-sidebar-open-shell.xml`, `android-device-captures/phase2/phase2-thread-shell.xml`

Phase 3 pairing/onboarding/recovery implementation note:

- `android/app/src/main/java/io/androdex/android/ui/pairing/PairingScreen.kt` now mirrors the Remodex welcome/features hierarchy more closely with a centered hero, dedicated saved-reconnect summary, toned recovery messaging, and a denser manual-pairing form.
- Fresh Android capture anchors for that refresh are still pending; until they exist, the pairing row should be treated as implementation-complete but phase-gate-incomplete.

Phase 4 home implementation note:

- `android/app/src/main/java/io/androdex/android/ui/home/HomeScreen.kt` now uses a centered Remodex-style home shell: compact centered title chrome, single-column empty-state hero, in-hero connection capsule, quieter current-project module, and grouped recent-conversation rows instead of the earlier multi-card landing stack.
- `android/app/src/main/java/io/androdex/android/ui/shared/RemodexPrimitives.kt` now exposes a centered-title page-header mode so the home toolbar can match the Remodex principal-title placement without hand-tuned per-screen padding.
- `android/app/src/main/java/io/androdex/android/service/AndrodexService.kt`, `android/app/src/main/java/io/androdex/android/MainViewModel.kt`, and `android/app/src/main/java/io/androdex/android/ui/state/AndrodexFeatureState.kt` now preserve a visible home thread-list loading state long enough to verify on-device instead of racing past between connect and list hydration.
- Fresh Android capture anchors for this pass now include `android-device-captures/phase4/phase4-home-shell.png`, `android-device-captures/phase4/phase4-home-empty-no-project.png`, `android-device-captures/phase4/phase4-home-loading.png`, and their paired XML dumps.

Phase 5 sidebar implementation note:

- `android/app/src/main/java/io/androdex/android/ui/sidebar/SidebarContent.kt` now uses a shared grouped-thread collection, denser Remodex-style search/new-chat rhythm, a rounded footer status capsule, and updated section/row spacing so the drawer matches the refreshed Android reference more closely.
- `android/app/src/main/java/io/androdex/android/ui/sidebar/ThreadListPane.kt` now delegates to the same grouped-thread renderer instead of carrying a second drifting thread-row implementation.
- `android/app/src/main/java/io/androdex/android/service/AndrodexService.kt` now keeps thread-list loading visible for a short minimum window even when refreshing an already-populated drawer, which makes the live loading state perceptible instead of collapsing into a one-frame flash.
- Fresh Android capture anchors for this pass now include `android-device-captures/phase5/phase5-sidebar-closed.png`, `android-device-captures/phase5/phase5-sidebar-open-default.png`, `android-device-captures/phase5/phase5-sidebar-search-focused.png`, `android-device-captures/phase5/phase5-sidebar-groups-expanded.png`, `android-device-captures/phase5/phase5-sidebar-empty.png`, and `android-device-captures/phase5/phase5-sidebar-loading.png`; the stable XML pair still applies to the closed/default/search/grouped/empty runtime variants.

Phase 6 thread-shell implementation note:

- `android/app/src/main/java/io/androdex/android/ui/turn/ThreadTimelineScreen.kt` now uses a custom Remodex-style thread header instead of a stock `TopAppBar`, with tighter toolbar affordances, an animated run-state dot, a centered content rail, a smaller floating jump-to-latest control, and grouped shell-level banner/card treatments around the timeline footer.
- `android/app/src/main/java/io/androdex/android/ui/state/AndrodexFeatureState.kt` now exposes project-name subtitle metadata so the thread header can show thread-level context when it exists.
- Fresh Android capture anchors for this pass now include `android-device-captures/phase6/phase6-thread-idle.png` as the archived post-refresh idle reference. The matching `active` and fork-banner captures remain follow-up artifact work rather than a blocker for the completed shell implementation.

Phase 7 timeline-message implementation note:

- `android/app/src/main/java/io/androdex/android/ui/turn/ThreadTimelineScreen.kt` now replaces the heavier Material-style message treatment with Remodex-style grouped chat bubbles, lighter timestamps, embedded streaming pills, calmer system/activity shells, denser review finding cards, plan step cards, and richer message-body handling for fenced mono blocks and file-reference lines.
- `android/app/src/main/java/io/androdex/android/ui/turn/GitSheet.kt` now shares the updated diff styling tokens so in-message file-change diffs and sheet diffs no longer drift apart visually.
- `android/app/src/test/java/io/androdex/android/ui/turn/ThreadTimelineFormattingTest.kt` now covers bubble grouping, streaming activity text, and message-body parsing so later timeline work is less likely to regress quietly.
- Fresh Android capture anchors for this pass now include `android-device-captures/phase7/phase7-thread-streaming.png` and `android-device-captures/phase7/phase7-thread-mixed-content.png`, with matching XML snapshots alongside them for the archived streaming and mixed-content message states.

Phase 8 composer implementation note:

- `android/app/src/main/java/io/androdex/android/ui/turn/ComposerBar.kt` now replaces the old Material field/chip/filter-chip stack with a Remodex-style floating composer shell, plus/tune/close accessory control, compact stop and send actions, removable context chips, inline review/runtime/media controls, a base-branch input shell, and density-matched autocomplete panels.
- `android/app/src/test/java/io/androdex/android/ui/turn/ComposerBarPresentationTest.kt` now covers the accessory button state machine and the icon-vs-text send control presentation so later composer work is less likely to drift.
- Fresh Android capture anchors for composer idle, focused, armed, and autocomplete states are still pending; no attached device or local emulator was available in this workspace, so the composer row should be treated as implementation-complete but phase-gate-incomplete.

Phase 9 attachment / queued-draft / tool-input implementation note:

- `android/app/src/main/java/io/androdex/android/ui/turn/AttachmentTiles.kt` now uses tighter Remodex-style attachment tiles with updated corner geometry, a compact remove affordance, clearer loading treatment, and a quieter failed-state shell instead of the earlier stock thumbnail cards.
- `android/app/src/main/java/io/androdex/android/ui/turn/ThreadTimelineScreen.kt` now restyles queued drafts and structured tool-input requests as grouped Remodex surfaces with pill-based metadata, inline restore/pause/resume controls, per-question option rows, and denser multi-question grouping above the composer.
- `android/app/src/main/java/io/androdex/android/ui/shared/RemodexPrimitives.kt` now lets the shared Remodex input field handle secure tool-input answers without falling back to Material outlined text fields.
- `android/app/src/test/java/io/androdex/android/ui/turn/ThreadTimelineFormattingTest.kt` now covers the queued-draft metadata chips plus tool-input summary and custom-answer labels so later thread-surface work is less likely to regress quietly.
- Fresh Android capture anchors for attachment tiles, queued drafts, and structured tool-input cards are still pending; no attached device or local emulator was available in this workspace, so the Phase 9 row should be treated as implementation-complete but phase-gate-incomplete.

Phase 11 alert / overlay / shared-status implementation note:

- `android/app/src/main/java/io/androdex/android/ui/shared/AndrodexOverlays.kt` now gives approval, error, missing-thread, and thread-maintenance confirmations the same Remodex-style alert shell, icon badge treatment, action row rhythm, and monospace detail blocks instead of a mix of stock Material dialog styling.
- `android/app/src/main/java/io/androdex/android/ui/shared/ConnectionStatusUi.kt` now rebuilds the status capsule, busy/activity overlays, trusted-pair card, bridge status card, host account card, metadata rows, and status pills as one consistent status-card family with shared badge tone, denser metadata layout, and calmer inline activity surfaces.
- `android/app/src/main/java/io/androdex/android/ui/state/AndrodexFeatureState.kt` now exposes status-tone metadata so bridge, account, and trusted-pair surfaces stay visually aligned with the same success/accent/warning/error language instead of inferring tone ad hoc in each composable.
- `android/app/src/test/java/io/androdex/android/ui/shared/ConnectionStatusUiPresentationTest.kt` and `android/app/src/test/java/io/androdex/android/ui/state/AndrodexFeatureStateTest.kt` now cover the new connection-banner presentation and tone mapping so later overlay/status work is less likely to drift quietly.
- Fresh Android capture anchors for this pass now include `android-device-captures/phase11/phase11-home-connected-status.png`, `android-device-captures/phase11/phase11-settings-status-cards.png`, `android-device-captures/phase11/phase11-thread-activity-banner.png`, and `android-device-captures/phase11/phase11-thread-rollback-confirmation.png`, with matching XML snapshots alongside them for the connected status family, in-thread activity overlay, and maintenance alert treatment.

Phase 12 motion / interaction implementation note:

- `android/app/src/main/java/io/androdex/android/ui/shared/RemodexMotion.kt` now centralizes the Remodex-style ease-in-out curve, shared fade/expand/slide helpers, and a reusable pressed-state modifier so motion and touch feedback stop drifting per screen.
- `android/app/src/main/java/io/androdex/android/AndrodexApp.kt`, `android/app/src/main/java/io/androdex/android/ui/sidebar/SidebarContent.kt`, `android/app/src/main/java/io/androdex/android/ui/turn/ComposerBar.kt`, `android/app/src/main/java/io/androdex/android/ui/shared/ConnectionStatusUi.kt`, and `android/app/src/main/java/io/androdex/android/ui/turn/ThreadTimelineScreen.kt` now use that shared motion language for connected-shell transitions, sidebar search cancel, composer focus/expand/autocomplete states, busy/activity banners, queued-draft and tool-input reveals, jump-to-latest appearance, and the run/thinking/streaming pulses.
- `android/app/src/main/java/io/androdex/android/ui/shared/RemodexPrimitives.kt` and `android/app/src/main/java/io/androdex/android/ui/shared/RemodexSheets.kt` now add consistent pressed opacity/scale feedback and focused search affordances across shared rows, buttons, search, and sheet options instead of falling back to uneven Material-default interaction treatment.
- Fresh Android motion anchors now live under `android-device-captures/phase12/`, including `phase12-drawer-open-close.mp4`, `phase12-sidebar-search-focus.mp4`, `phase12-home-to-thread.mp4`, `phase12-composer-mode-panel.mp4`, `phase12-jump-to-latest.mp4`, and `phase12-settings-sheet.mp4`, plus matching still captures and XML dumps for the focused drawer, expanded thread list, thread shell, composer panel, and jump-button states.

The remaining Android capture gaps are the refreshed Phase 3 pairing states plus later-phase/runtime work: attachment/tool-input references, approval-dialog variants, notification-open success/fallback, and the missing-thread prompt family.

Phase 1 is complete for the shared design-system surfaces. Live device verification now anchors the refreshed home and sidebar captures, while the remaining runtime alert and recovery captures stay tracked under later phases and backlog work.

## Highest-Risk Visual Mismatches To Fix First

1. Composer and input-surface verification follow-up. The Android composer, attachment tiles, queued drafts, and structured tool-input cards now track the Remodex layout much more closely in code, but a later capture pass should still archive idle/focused/armed/autocomplete plus Phase 9 input-surface references and compare their overlay layering against Remodex.
2. Thread shell artifact follow-up. The Android thread header and shell spacing now track the Remodex structure much more closely in code, but a later device pass should still archive refreshed running and fork-banner references alongside the new idle capture.
3. Sidebar reconnect loading fidelity. The refreshed drawer now has real empty/loading Android anchors, but the saved-reconnect loading variant can still momentarily retain grouped drawer content while the home shell is rehydrating workspace context behind it.
