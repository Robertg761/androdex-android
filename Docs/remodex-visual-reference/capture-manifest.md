# Capture Manifest

This is the canonical screenshot and short-video inventory for the Remodex visual clone effort.

Status values:

- `asset-backed`: a public image or bundled asset exists now
- `preview-backed`: a dedicated SwiftUI preview exists for the exact state family
- `code-backed`: the state is confirmed by upstream SwiftUI/views/tests, but still needs an explicit simulator/device capture
- `capture-required`: the state is known to exist but needs a dedicated capture pass to become canonical

Phase 0 is considered complete once every required state has:

- a stable artifact name
- a canonical upstream source owner
- an evidence status that makes remaining uncertainty explicit

## Naming Convention

- Screenshots: `route--state.png`
- Short videos: `route--state.mov`
- If a light/dark split is needed later, suffix with `--light` or `--dark`

## Inventory

| Route | State | Screenshot / Video | Status | Remodex source of truth |
| --- | --- | --- | --- | --- |
| `pairing` | QR scanner idle | `pairing--qr-scanner-idle.png` | `code-backed` | `Views/QRScannerView.swift` |
| `pairing` | Camera permission denied | `pairing--camera-permission.png` | `code-backed` | `Views/QRScannerView.swift` |
| `pairing` | Bridge update / incompatible QR | `pairing--bridge-update.png` | `preview-backed` | `Views/QRScannerView.swift` |
| `pairing` | Back button visible | `pairing--scanner-with-back.png` | `code-backed` | `Views/QRScannerView.swift` |
| `onboarding` | Welcome hero | `onboarding--welcome.png` | `asset-backed` | `Views/Onboarding/OnboardingWelcomePage.swift`, `Assets.xcassets/three.imageset` |
| `onboarding` | Features page | `onboarding--features.png` | `code-backed` | `Views/Onboarding/OnboardingFeaturesPage.swift` |
| `onboarding` | Step page | `onboarding--step.png` | `code-backed` | `Views/Onboarding/OnboardingStepPage.swift` |
| `home` | Saved reconnect / offline empty state | `home--offline-empty.png` | `code-backed` | `Views/Home/HomeEmptyStateView.swift` |
| `home` | Connected empty state | `home--connected-empty.png` | `code-backed` | `Views/Home/HomeEmptyStateView.swift` |
| `home` | Connecting / syncing empty state | `home--connecting-empty.png` | `code-backed` | `Views/Home/HomeEmptyStateView.swift` |
| `home` | Trusted pair summary visible | `home--trusted-pair-summary.png` | `code-backed` | `Views/Home/HomeEmptyStateView.swift`, `Views/Shared/TrustedPairSummaryView.swift` |
| `home` | Notification completion banner | `home--completion-banner.png` | `code-backed` | `Views/Home/ThreadCompletionBannerView.swift` |
| `sidebar` | Closed drawer reference | `sidebar--closed.png` | `code-backed` | `Views/SidebarView.swift`, root navigation shell |
| `sidebar` | Open drawer default | `sidebar--open-default.png` | `code-backed` | `Views/SidebarView.swift` |
| `sidebar` | Search focused | `sidebar--search-focused.png` | `code-backed` | `Views/Sidebar/SidebarSearchField.swift` |
| `sidebar` | Search with matches | `sidebar--search-results.png` | `code-backed` | `Views/Sidebar/SidebarSearchField.swift`, `Views/Sidebar/SidebarThreadListView.swift` |
| `sidebar` | Grouped threads expanded | `sidebar--groups-expanded.png` | `code-backed` | `Views/Sidebar/SidebarThreadGrouping.swift`, `CodexMobileTests/SidebarThreadGroupingTests.swift` |
| `sidebar` | Grouped threads collapsed | `sidebar--groups-collapsed.png` | `code-backed` | `Views/Sidebar/SidebarThreadGrouping.swift`, `CodexMobileTests/SidebarThreadGroupingTests.swift` |
| `sidebar` | Empty sidebar | `sidebar--empty.png` | `code-backed` | `Views/Sidebar/SidebarThreadListView.swift` |
| `sidebar` | Initial loading overlay | `sidebar--loading.png` | `code-backed` | `Views/SidebarView.swift`, `CodexMobileTests/SidebarThreadsLoadingPresentationTests.swift` |
| `sidebar` | Archived section visible | `sidebar--archived-group.png` | `code-backed` | `Views/Sidebar/SidebarThreadGrouping.swift`, `CodexMobileTests/SidebarThreadGroupingTests.swift` |
| `thread` | Idle thread | `thread--idle.png` | `code-backed` | `Views/Turn/TurnView.swift`, `Views/Turn/TurnTimelineView.swift` |
| `thread` | Long user message | `thread--long-user-message.png` | `code-backed` | `Views/Turn/TurnMessageComponents.swift` |
| `thread` | Long assistant message | `thread--long-assistant-message.png` | `code-backed` | `Views/Turn/TurnMessageComponents.swift` |
| `thread` | Grouped assistant messages | `thread--grouped-assistant-messages.png` | `code-backed` | `Views/Turn/TurnTimelineReducer.swift`, `Views/Turn/TurnTimelineView.swift` |
| `thread` | Streaming reply | `thread--streaming.png` | `code-backed` | `Views/Turn/TurnView.swift`, `Views/Turn/TerminalRunningIndicator.swift` |
| `thread` | Thinking/system content | `thread--thinking-system.png` | `preview-backed` | `Views/Turn/TurnMessageComponents.swift`, `Views/Turn/ThinkingDisclosureParser.swift` |
| `thread` | File-change recap | `thread--file-change.png` | `code-backed` | `Views/Turn/TurnMessageComponents.swift`, `Views/Turn/TurnFileChangeSummaryParser.swift` |
| `thread` | Command execution content | `thread--command-execution.png` | `preview-backed` | `Views/Turn/CommandExecutionViews.swift` |
| `thread` | Plan content pinned above composer | `thread--plan-pinned.png` | `code-backed` | `Views/Turn/TurnConversationContainerView.swift`, `Views/Turn/PlanAccessoryCard.swift` |
| `thread` | Review findings card | `thread--review-findings.png` | `code-backed` | `Views/Turn/TurnMessageComponents.swift`, `Views/Turn/CodeCommentDirectiveParser.swift` |
| `thread` | Connection recovery card | `thread--connection-recovery.png` | `code-backed` | `Views/Turn/ConnectionRecoveryCard.swift` |
| `composer` | Idle composer | `composer--idle.png` | `code-backed` | `Views/Turn/TurnComposerView.swift` |
| `composer` | Attachments and chips active | `composer--chips-and-attachments.png` | `code-backed` | `Views/Turn/TurnComposerView.swift`, `Views/Turn/ComposerAttachmentsPreview.swift`, `Views/Turn/FileMentionChip.swift` |
| `composer` | File autocomplete | `composer--file-autocomplete.png` | `code-backed` | `Views/Turn/FileAutocompletePanel.swift` |
| `composer` | Skill autocomplete | `composer--skill-autocomplete.png` | `code-backed` | `Views/Turn/SkillAutocompletePanel.swift` |
| `composer` | Slash command autocomplete | `composer--slash-autocomplete.png` | `code-backed` | `Views/Turn/SlashCommandAutocompletePanel.swift` |
| `composer` | Review mode armed | `composer--review-mode.png` | `code-backed` | `Views/Turn/TurnComposerView.swift`, `CodexMobileTests/TurnComposerReviewModeTests.swift` |
| `composer` | Subagents armed | `composer--subagents-mode.png` | `code-backed` | `Views/Turn/TurnComposerView.swift`, `CodexMobileTests/TurnComposerReviewModeTests.swift` |
| `composer` | Voice recording capsule | `composer--voice-recording.png` | `preview-backed` | `Views/Turn/VoiceRecordingCapsule.swift`, `Views/Turn/TurnComposerView.swift` |
| `composer` | Queued drafts | `composer--queued-drafts.png` | `preview-backed` | `Views/Turn/QueuedDraftsPanel.swift`, `Views/Turn/TurnComposerView.swift` |
| `sheet` | Git actions toolbar menu | `sheet--git-actions.png` | `code-backed` | `Views/Turn/TurnGitActionsToolbar.swift` |
| `sheet` | Git branch selector | `sheet--git-branch-selector.png` | `preview-backed` | `Views/Turn/TurnGitBranchSelector.swift` |
| `sheet` | Runtime status sheet | `sheet--runtime-status.png` | `code-backed` | `Views/Turn/TurnStatusSheet.swift` |
| `sheet` | Diff sheet | `sheet--diff.png` | `code-backed` | `Views/Turn/TurnDiffSheet.swift` |
| `sheet` | Worktree handoff overlay | `sheet--worktree-handoff.png` | `code-backed` | `Views/Turn/TurnWorktreeHandoffOverlay.swift` |
| `sheet` | Fork destination flow | `sheet--fork-destination.png` | `code-backed` | `Views/Turn/TurnView.swift`, `CodexMobileTests/TurnComposerReviewModeTests.swift` |
| `project-picker` | Start new chat chooser | `project-picker--start-new-chat.png` | `code-backed` | `Views/SidebarView.swift`, `Views/Sidebar/SidebarThreadGrouping.swift` |
| `settings` | Settings root | `settings--root.png` | `code-backed` | `Views/SettingsView.swift` |
| `settings` | Trusted Mac edit flow | `settings--trusted-mac.png` | `code-backed` | `Views/SettingsView.swift` |
| `about` | About header | `about--header.png` | `code-backed` | `Views/AboutRemodexView.swift` |
| `about` | About architecture section | `about--architecture.png` | `code-backed` | `Views/AboutRemodexView.swift` |
| `dialog` | Action failed alert | `dialog--action-failed.png` | `code-backed` | `Views/SidebarView.swift`, `Views/Turn/TurnViewAlertModifier.swift` |
| `dialog` | Delete thread confirmation | `dialog--delete-thread.png` | `code-backed` | `Views/SidebarView.swift` |
| `dialog` | Approval request | `dialog--approval-request.png` | `code-backed` | `Views/Turn/StructuredUserInputCardView.swift`, turn approval flow |
| `recovery` | Missing-thread recovery | `recovery--missing-thread.png` | `capture-required` | `CodexMobileTests/CodexPushNotificationRegistrationTests.swift`, notification-open recovery prompt flow |
| `recovery` | Notification open success | `recovery--notification-open-success.mov` | `capture-required` | `CodexMobileTests/CodexPushNotificationRegistrationTests.swift`, deferred push/open routing flow |
| `recovery` | Notification open missing-thread fallback | `recovery--notification-open-fallback.mov` | `capture-required` | `CodexMobileTests/CodexPushNotificationRegistrationTests.swift`, deferred push/open routing flow |

## Notes

- `asset-backed` does not mean the asset is a full canonical UI screenshot. It means we have at least one directly bundled public visual source for that state family.
- `preview-backed` means the upstream repo includes a dedicated SwiftUI preview for the state family, which is stronger evidence than prose-only source inspection but still not a device capture.
- The missing recovery and open-from-notification captures are real states in Androdex and the clone plan, but they need a deliberate capture run because they are driven by runtime navigation, not static assets.
- App Store lookup metadata confirmed on March 29, 2026 that `screenshotUrls`, `ipadScreenshotUrls`, and `appletvScreenshotUrls` are empty for app id `6760243963`, so public App Store captures cannot be treated as a source of record.

## Verified Androdex Device Counterparts

These are real Android-side captures gathered on March 29, 2026. They are not canonical Remodex screenshots, but they do anchor the Androdex side of the parity work in actual runtime output instead of guesswork.

| Remodex state family | Android artifact | Notes |
| --- | --- | --- |
| `pairing` QR scanner idle | `android-device-captures/pairing--qr-scanner-idle--androdex.png` | Real device scanner state. |
| `sidebar` closed drawer reference | `android-device-captures/sidebar--closed--androdex.png` | Captured as the connected home shell with the drawer closed. |
| `sidebar` open drawer default | `android-device-captures/sidebar--open-default--androdex.png` | Real grouped-project drawer state. |
| `sidebar` search focused | `android-device-captures/sidebar--search-focused--androdex.png` | Real focused search field and cancel affordance. |
| `sidebar` grouped threads expanded | `android-device-captures/sidebar--groups-expanded--androdex.png` | Real expanded group with a child thread row visible. |
| `thread` streaming shell / active composer | `android-device-captures/thread--streaming--androdex.png` | Active turn with `Stop` and `Queue` visible. |
| `settings` root | `android-device-captures/settings--root--androdex.png` | Captured from the header settings action. |
| `recovery` stale-pair repair state | `android-device-captures/recovery--repair-pairing-required--androdex.png` | Supplemental Android recovery reference observed during the repair flow. |
