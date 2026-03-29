# Parity Board

This board is the screen-by-screen mapping from canonical Remodex state to the Androdex surface that must visually converge on it.

## Legend

- `Remodex evidence`: the upstream screen, component, or test that defines the state
- `Androdex target`: the Android file that must match the state visually
- `Board status`: whether we already have a named capture slot and enough source evidence to begin implementation

## Board

| Area | Remodex state | Remodex evidence | Androdex target | Board status |
| --- | --- | --- | --- | --- |
| Pairing | QR scanner idle / overlay / permission states | `Views/QRScannerView.swift` | `android/app/src/main/java/io/androdex/android/ui/pairing/PairingScreen.kt` | ready |
| Onboarding | Welcome hero and feature pages | `Views/Onboarding/OnboardingWelcomePage.swift`, `Views/Onboarding/OnboardingFeaturesPage.swift`, `Views/Onboarding/OnboardingStepPage.swift` | `android/app/src/main/java/io/androdex/android/ui/pairing/PairingScreen.kt` | ready |
| Home | Offline / connected / syncing empty state | `Views/Home/HomeEmptyStateView.swift` | `android/app/src/main/java/io/androdex/android/ui/home/HomeScreen.kt` | ready |
| Home | Trusted pair summary and completion banner | `Views/Shared/TrustedPairSummaryView.swift`, `Views/Home/ThreadCompletionBannerView.swift` | `android/app/src/main/java/io/androdex/android/ui/home/HomeScreen.kt` | ready |
| Sidebar | Header, search, new chat, grouped threads | `Views/SidebarView.swift`, `Views/Sidebar/SidebarHeaderView.swift`, `Views/Sidebar/SidebarSearchField.swift`, `Views/Sidebar/SidebarThreadRowView.swift` | `android/app/src/main/java/io/androdex/android/ui/sidebar/SidebarContent.kt` | ready |
| Sidebar | Grouping, expansion, archived bucket, worktree labeling | `Views/Sidebar/SidebarThreadGrouping.swift`, `CodexMobileTests/SidebarThreadGroupingTests.swift` | `android/app/src/main/java/io/androdex/android/ui/sidebar/SidebarContent.kt` | ready |
| Sidebar | Initial loading overlay behavior | `Views/SidebarView.swift`, `CodexMobileTests/SidebarThreadsLoadingPresentationTests.swift` | `android/app/src/main/java/io/androdex/android/ui/sidebar/SidebarContent.kt` | ready |
| Thread shell | Navigation title, subtitle, thread actions, git pills | `Views/Turn/TurnView.swift`, `Views/Turn/TurnToolbarContent.swift` | `android/app/src/main/java/io/androdex/android/ui/turn/ThreadTimelineScreen.kt` | ready |
| Thread timeline | Empty state, scroll container, pinned plan accessory | `Views/Turn/TurnConversationContainerView.swift`, `Views/Turn/TurnTimelineView.swift` | `android/app/src/main/java/io/androdex/android/ui/turn/ThreadTimelineScreen.kt` | ready |
| Thread messages | User / assistant / system / thinking / file-change / execution / review content | `Views/Turn/TurnMessageComponents.swift`, `Views/Turn/CommandExecutionViews.swift` | `android/app/src/main/java/io/androdex/android/ui/turn/ThreadTimelineScreen.kt` | ready |
| Composer | Floating shell, placeholder, chips, attachments, send / stop controls | `Views/Turn/TurnComposerView.swift`, `Views/Turn/ComposerBottomBar.swift`, `Views/Turn/ComposerAttachmentsPreview.swift` | `android/app/src/main/java/io/androdex/android/ui/turn/ComposerBar.kt` | ready |
| Composer | File / skill / slash autocomplete panels | `Views/Turn/FileAutocompletePanel.swift`, `Views/Turn/SkillAutocompletePanel.swift`, `Views/Turn/SlashCommandAutocompletePanel.swift` | `android/app/src/main/java/io/androdex/android/ui/turn/ComposerBar.kt` | ready |
| Composer | Review and subagents armed states | `Views/Turn/TurnComposerView.swift`, `CodexMobileTests/TurnComposerReviewModeTests.swift` | `android/app/src/main/java/io/androdex/android/ui/turn/ComposerBar.kt` | ready |
| Composer | Queued drafts and voice recording | `Views/Turn/QueuedDraftsPanel.swift`, `Views/Turn/VoiceRecordingCapsule.swift` | `android/app/src/main/java/io/androdex/android/ui/turn/ThreadTimelineScreen.kt`, `android/app/src/main/java/io/androdex/android/ui/turn/ComposerBar.kt` | ready |
| Git | Toolbar actions, branch selector, diff sheet, worktree handoff | `Views/Turn/TurnGitActionsToolbar.swift`, `Views/Turn/TurnGitBranchSelector.swift`, `Views/Turn/TurnDiffSheet.swift`, `Views/Turn/TurnWorktreeHandoffOverlay.swift` | `android/app/src/main/java/io/androdex/android/ui/turn/GitSheet.kt`, `android/app/src/main/java/io/androdex/android/ui/turn/ThreadTimelineScreen.kt` | ready |
| Runtime | Status sheet and usage summary | `Views/Turn/TurnStatusSheet.swift`, `Views/Shared/UsageStatusSummaryContent.swift` | `android/app/src/main/java/io/androdex/android/ui/turn/ThreadRuntimeSheet.kt` | ready |
| Project picker | New chat project chooser | `Views/SidebarView.swift`, `Views/Sidebar/SidebarThreadGrouping.swift` | `android/app/src/main/java/io/androdex/android/ui/home/ProjectPickerSheet.kt` | ready |
| Settings | Settings cards, trusted Mac card, runtime defaults | `Views/SettingsView.swift` | `android/app/src/main/java/io/androdex/android/ui/settings/RuntimeSettingsSheet.kt` | ready |
| About | Editorial about screen and architecture diagram | `Views/AboutRemodexView.swift` | `android/app/src/main/java/io/androdex/android/ui/settings/AboutAndrodexSheet.kt` | ready |
| Dialogs | Confirmation dialogs and alerts | `Views/SidebarView.swift`, `Views/Turn/TurnViewAlertModifier.swift` | thread and sidebar alert surfaces in Android | ready |
| Recovery | Missing thread, reconnect fallback, notification-open states | `CodexMobileTests/CodexPushNotificationRegistrationTests.swift`, app runtime flow plus push/open navigation behavior | `android/app/src/main/java/io/androdex/android/MainViewModel.kt`, pairing/home/thread recovery surfaces | runtime capture backlog only |

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

The remaining Android capture gaps are the refreshed Phase 3 pairing states plus later-phase/runtime work: approval-dialog variants, notification-open success/fallback, and the missing-thread prompt family.

Phase 1 is complete for the shared design-system surfaces. Live device verification now anchors the refreshed home and sidebar captures, while the remaining runtime alert and recovery captures stay tracked under later phases and backlog work.

## Highest-Risk Visual Mismatches To Fix First

1. Sidebar density. Remodex uses tighter row spacing, lighter fills, smaller type, and more native-looking list grouping than the current Compose drawer.
2. Composer shell. Remodex uses a floating glass composer with a large `28` corner radius and layered autocomplete/voice overlays.
3. Toolbar chrome. Remodex relies on compact principal-title layout plus small adaptive toolbar affordances, not a standard Material top app bar.
4. Color semantics. Remodex leans on iOS semantic fills and glass/translucency; Androdex still reads like Material 3 with iOS-colored paint on top.
