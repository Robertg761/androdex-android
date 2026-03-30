# Android Device Capture Log

This file records the verified Android-side screenshots gathered during the live device passes on March 29-30, 2026.

## Device And Method

- Device: Samsung `SM-S928W`
- Connection: host-connected `adb`
- App package: `io.androdex.android`
- Capture method: direct launch, UI-tree-guided taps, `screencap`, and `uiautomator dump`

## Verified Captures

| Android artifact | What it shows | Notes |
| --- | --- | --- |
| `android-device-captures/sidebar--closed--androdex.png` | Connected home shell with drawer closed | Captured in portrait after a fresh saved-pair reconnect. Useful as the baseline app-shell screenshot. |
| `android-device-captures/sidebar--open-default--androdex.png` | Default open drawer state | Shows search field, `New Chat`, grouped projects, and bottom connection summary. |
| `android-device-captures/sidebar--search-focused--androdex.png` | Drawer with search focused | Confirms focused-field layout and cancel affordance in the real Android build. |
| `android-device-captures/sidebar--groups-expanded--androdex.png` | Expanded grouped thread section | Captured with `HOME ASSISTANT DESKTOP WIDGET` expanded and a child thread visible. |
| `android-device-captures/settings--root--androdex.png` | Settings root | Captured from the home header action to avoid keyboard/input-method interference from the drawer search field. |
| `android-device-captures/pairing--qr-scanner-idle--androdex.png` | QR scanner state | Real camera/scanner presentation on device. |
| `android-device-captures/recovery--repair-pairing-required--androdex.png` | Pairing recovery requiring repair | Real stale-trust recovery surface observed before resetting host pairing. |
| `android-device-captures/thread--streaming--androdex.png` | Active thread shell and composer | Shows `Conversation`, git pill state, and `Stop` / `Queue` controls during an in-flight turn. |

## Phase 1 Refresh Captures

| Android artifact | What it shows | Notes |
| --- | --- | --- |
| `android-device-captures/phase1/phase1-pairing-refresh.png` | Pairing / saved-reconnect shell after the semantic token pass | Accepted as the Phase 1 visual anchor for the refreshed pairing shell. Its paired UI dump can be re-captured later without blocking the design-system phase sign-off. |
| `android-device-captures/phase1/phase1-home-refresh.png` | Connected home shell after the semantic token pass | Captured after the reconnect settled, showing the refreshed landing hero, status capsule, project card, and recent-thread styling on the live device. |
| `android-device-captures/phase1/phase1-sidebar-open-refresh.png` | Sidebar open state after the semantic token pass | Re-captured live from the verified Androdex drawer state after the earlier saved artifact drifted out of app context. This is the current Phase 1 sidebar reference for the refreshed header, search shell, grouped rows, and footer chrome. |

## Phase 2 Shell Captures

| Android artifact | What it shows | Notes |
| --- | --- | --- |
| `android-device-captures/phase2/phase2-home-shell.png` | Connected home shell after the shared-shell refactor | Confirms the flatter landing field, transparent status/navigation bars, and compact header placement below the system status area. |
| `android-device-captures/phase2/phase2-sidebar-open-shell.png` | Drawer reveal with shared home shell still visible | Captured after opening the new dismissible drawer so the reveal behavior and darker background tone behind the drawer are anchored on device. |
| `android-device-captures/phase2/phase2-thread-shell.png` | Thread shell after the shared connected-shell transition | Verifies that the same edge-to-edge shell and compact header behavior carry through when navigating from home into a thread. |
| `android-device-captures/phase2/phase2-home-thread-home-transition.mp4` | Home -> thread -> home shell transition clip | Recorded on device with `adb screenrecord` to confirm the shared-shell route animation and back-navigation return path outside of still screenshots. |

## Phase 4 Home Captures

| Android artifact | What it shows | Notes |
| --- | --- | --- |
| `android-device-captures/phase4/phase4-home-shell.png` | Refreshed connected home shell after the Phase 4 layout pass | Captured live on Samsung `SM-S928W` after installing the debug build. Confirms the centered principal title, in-hero status capsule, flatter single-column hero, quieter current-project card, and grouped recent-thread list. |
| `android-device-captures/phase4/phase4-home-empty-no-project.png` | Empty home state with no active workspace | Captured after restarting the daemon without restoring a workspace so the Android app shows the no-project empty shell instead of stale thread content. |
| `android-device-captures/phase4/phase4-home-no-project.png` | First no-workspace error pass | Preserved as a runtime-adjacent artifact because the host surfaces the “No active workspace” error dialog before the clean empty state settles. Useful for later alert-phase comparison even though the clean empty-state capture is the canonical home reference. |
| `android-device-captures/phase4/phase4-home-loading.png` | Home shell while the thread list is still loading | Captured after adding explicit thread-list loading-state plumbing so the Remodex-style loading variant exists long enough to verify on device. |
| `android-device-captures/phase4/phase4-home-shell.xml` | UI dump for the refreshed connected home shell | Paired with the screenshot above so spacing, text hierarchy, and visible row content can be checked without re-running the device flow. |
| `android-device-captures/phase4/phase4-home-empty-no-project.xml` | UI dump for the empty no-project home state | Confirms the empty-state copy, disabled primary CTA, and project-picker affordance once no active workspace is present. |
| `android-device-captures/phase4/phase4-home-no-project.xml` | UI dump for the transient no-workspace error variant | Useful for later dialog/overlay parity checks tied to the same home recovery path. |
| `android-device-captures/phase4/phase4-home-loading.xml` | UI dump for the loading home variant | Confirms that the loading row is present in the live hierarchy even though it sits near the bottom edge of the viewport in the captured frame. |

## Phase 5 Sidebar Captures

| Android artifact | What it shows | Notes |
| --- | --- | --- |
| `android-device-captures/phase5/phase5-sidebar-closed.png` | Refreshed home shell with the drawer closed after the Phase 5 sidebar pass | Captured after restoring the primary `androdex` workspace so the post-refresh closed-shell state has a matching Android reference. |
| `android-device-captures/phase5/phase5-sidebar-open-default.png` | Refreshed default open drawer state | Confirms the denser header/search stack, flatter grouped sections, and rounded footer capsule from the Phase 5 drawer implementation. |
| `android-device-captures/phase5/phase5-sidebar-search-focused.png` | Drawer with the refreshed focused search field | Confirms the focused-field width change, cancel placement, and keyboard-aware layout after the sidebar pass. |
| `android-device-captures/phase5/phase5-sidebar-groups-expanded.png` | Expanded grouped thread section after the sidebar refresh | Shows the shared grouped-thread renderer, timestamp alignment, and run-state dot placement in the updated drawer. |
| `android-device-captures/phase5/phase5-sidebar-empty.png` | Empty drawer state with no conversations available | Captured against a temporary empty Codex home so the live Android drawer could show the true no-conversations copy instead of cached grouped history. |
| `android-device-captures/phase5/phase5-sidebar-loading.png` | Drawer visible during saved-reconnect startup loading | Captured from the live Samsung device during the reconnect window where the home shell is still loading models and rehydrating workspace context behind the drawer. This is the closest stable Android-side loading reference for the sidebar runtime path. |
| `android-device-captures/phase5/phase5-sidebar-closed.xml` | UI dump for the refreshed closed-shell state | Paired with the screenshot above for header spacing and home-shell alignment checks after the drawer refresh. |
| `android-device-captures/phase5/phase5-sidebar-open-default.xml` | UI dump for the refreshed default drawer state | Useful for validating the updated group counts, row spacing, and footer metadata placement without re-running the device flow. |
| `android-device-captures/phase5/phase5-sidebar-search-focused.xml` | UI dump for the focused search drawer state | Confirms the focused search field width and cancel affordance in the live hierarchy. |
| `android-device-captures/phase5/phase5-sidebar-groups-expanded.xml` | UI dump for the expanded grouped drawer state | Confirms the updated expanded section rows, timestamps, and visible grouped-thread content. |
| `android-device-captures/phase5/phase5-sidebar-empty.xml` | UI dump for the empty drawer state | Confirms the `No conversations yet` drawer copy and the supporting empty-state message in the live hierarchy. |

## Phase 11 Alerts / Overlays / Shared Status Captures

| Android artifact | What it shows | Notes |
| --- | --- | --- |
| `android-device-captures/phase11/phase11-home-connected-status.png` | Connected home shell with the refreshed status capsule and trusted-host metadata | Captured on March 30, 2026 after reconnecting the Samsung `SM-S928W` to the host-local Androdex workspace, and used as the primary live reference for the home-side Phase 11 status family. |
| `android-device-captures/phase11/phase11-settings-status-cards.png` | Settings surface with the trusted pair, bridge, and host account cards | Confirms the shared status-card treatment in the denser settings stack after the Phase 11 redesign. |
| `android-device-captures/phase11/phase11-thread-activity-banner.png` | Real in-thread activity state during context compaction | Captured from a live thread while the host was processing a compaction request so the calmer activity banner and inline run-state surfaces are anchored on device instead of only in code. |
| `android-device-captures/phase11/phase11-thread-rollback-confirmation.png` | Thread maintenance confirmation dialog | Captured from the thread overflow menu after selecting `Roll back last turn`, providing the live Android reference for the new Remodex-style alert shell, warning badge, and button rhythm. |
| `android-device-captures/phase11/phase11-home-connected-status.xml` | UI dump for the connected home status reference | Confirms the live capsule labels, trusted-host metadata, and current-project copy from the same Phase 11 home capture. |
| `android-device-captures/phase11/phase11-settings-status-cards.xml` | UI dump for the settings status-card stack | Useful for checking the bridge/account metadata rows and pill labels without re-running the device pass. |
| `android-device-captures/phase11/phase11-thread-activity-banner.xml` | UI dump for the in-thread activity overlay state | Confirms the compaction label, thinking state, and stop affordance from the same live run-state frame. |
| `android-device-captures/phase11/phase11-thread-rollback-confirmation.xml` | UI dump for the maintenance confirmation dialog | Confirms the confirmation copy and action labels from the live Android dialog variant. |

## Phase 12 Motion / Interaction Captures

| Android artifact | What it shows | Notes |
| --- | --- | --- |
| `android-device-captures/phase12/phase12-drawer-open-close.mp4` | Home shell drawer reveal and collapse | Recorded on the attached Samsung device with `adb screenrecord` after the shared motion pass so the drawer reveal timing can be compared against Remodex instead of inferred from stills. |
| `android-device-captures/phase12/phase12-sidebar-search-focus.mp4` | Drawer search focus transition | Shows the focused search affordance, keyboard lift, and cancel reveal on live hardware after the new shared search easing landed. |
| `android-device-captures/phase12/phase12-home-to-thread.mp4` | Connected-shell home -> thread transition | Captures the shared shell slide/fade motion when leaving the landing screen and opening a conversation from the drawer. |
| `android-device-captures/phase12/phase12-composer-mode-panel.mp4` | Composer accessory panel expand / collapse | Confirms the new composer panel reveal cadence and collapse behavior above the footer input shell on device. |
| `android-device-captures/phase12/phase12-jump-to-latest.mp4` | Jump-to-latest affordance dismissal | Captures the floating jump button while away from the latest message and its disappearance after returning to the bottom of the thread. |
| `android-device-captures/phase12/phase12-settings-sheet.mp4` | Settings sheet presentation and dismissal | Recorded from the home header settings action to anchor bottom-sheet motion after the shared motion pass. |
| `android-device-captures/phase12/phase12-sidebar-open.png` | Default drawer-open state after the motion pass | Still reference used alongside the recordings to verify the drawer reveal end state. |
| `android-device-captures/phase12/phase12-sidebar-search-focused2.png` | Focused drawer search with visible cancel affordance | The clearest still anchor for the tuned focus border, cancel button, and keyboard-aware width change after the Phase 12 pass. |
| `android-device-captures/phase12/phase12-sidebar-expanded.png` | Expanded grouped thread section in the drawer | Confirms the grouped-thread end state used during the motion recordings. |
| `android-device-captures/phase12/phase12-thread-from-sidebar.png` | Thread shell during live loading / thinking activity | Useful as the still anchor for the busy/loading transition family captured during the Phase 12 thread-open recordings. |
| `android-device-captures/phase12/phase12-composer-mode-panel-2.png` | Composer accessory panel open on-device | Still frame that matches the `phase12-composer-mode-panel.mp4` clip and shows the expanded control cluster. |
| `android-device-captures/phase12/phase12-jump-state.png` | Thread state with the jump-to-latest affordance visible | Paired with the jump recording so the floating control can be checked in a static reference. |
| `android-device-captures/phase12/phase12-thread-post-record.xml` | UI dump for the baseline post-motion thread state | Confirms the footer geometry and header affordances from the same device session used for the motion clips. |
| `android-device-captures/phase12/phase12-sidebar-open.xml` | UI dump for the drawer-open state | Verifies the motion end state for the default drawer reveal. |
| `android-device-captures/phase12/phase12-sidebar-search-focused2.xml` | UI dump for the focused search drawer state | Confirms the focused `EditText`, cancel label, and keyboard-aware layout in the live hierarchy. |
| `android-device-captures/phase12/phase12-composer-mode-panel-2.xml` | UI dump for the open composer mode panel | Useful for checking the pill ordering and expanded footer height after the new motion treatment. |
| `android-device-captures/phase12/phase12-jump-state.xml` | UI dump for the visible jump-to-latest state | Confirms the floating jump button is present in the live hierarchy before dismissal. |

## Supporting Dumps

- Route-based `*--androdex.xml` files are the canonical UI dumps for the new Android screenshots.
- Verified Phase 1 refresh dumps currently include `android-device-captures/phase1/phase1-home-refresh.xml` and `android-device-captures/phase1/phase1-sidebar-open-refresh.xml`.
- Verified Phase 2 shell dumps now include `android-device-captures/phase2/phase2-home-shell.xml`, `android-device-captures/phase2/phase2-sidebar-open-shell.xml`, and `android-device-captures/phase2/phase2-thread-shell.xml`.
- Verified Phase 4 home dumps now include `android-device-captures/phase4/phase4-home-shell.xml`, `android-device-captures/phase4/phase4-home-empty-no-project.xml`, `android-device-captures/phase4/phase4-home-no-project.xml`, and `android-device-captures/phase4/phase4-home-loading.xml`.
- Verified Phase 5 sidebar dumps now include `android-device-captures/phase5/phase5-sidebar-closed.xml`, `android-device-captures/phase5/phase5-sidebar-open-default.xml`, `android-device-captures/phase5/phase5-sidebar-search-focused.xml`, `android-device-captures/phase5/phase5-sidebar-groups-expanded.xml`, and `android-device-captures/phase5/phase5-sidebar-empty.xml`.
- Verified Phase 11 alert / overlay / status dumps now include `android-device-captures/phase11/phase11-home-connected-status.xml`, `android-device-captures/phase11/phase11-settings-status-cards.xml`, `android-device-captures/phase11/phase11-thread-activity-banner.xml`, and `android-device-captures/phase11/phase11-thread-rollback-confirmation.xml`.
- Verified Phase 12 motion dumps now include `android-device-captures/phase12/phase12-sidebar-open.xml`, `android-device-captures/phase12/phase12-sidebar-search-focused2.xml`, `android-device-captures/phase12/phase12-sidebar-expanded.xml`, `android-device-captures/phase12/phase12-thread-from-sidebar.xml`, `android-device-captures/phase12/phase12-composer-mode-panel-2.xml`, `android-device-captures/phase12/phase12-jump-state.xml`, and `android-device-captures/phase12/phase12-thread-post-record.xml`.
- `android-device-captures/phase1/phase1-pairing-refresh.xml` is still a useful scratch artifact, but a fresh saved-reconnect recapture would align it better with `phase1-pairing-refresh.png`.
- Older `uidump-*.xml` files are retained as scratch/session dumps for pairing and thread exploration.
- `latest-pair-output.txt` contains the host-side pairing output captured during the fresh repair/pair flow.

## Notes

- A stale Phase 1 sidebar refresh artifact drifted out of app context and was replaced with a live Androdex recapture; earlier non-Androdex captures remain excluded from the reference set.
- The Android captures are counterpart references for Androdex, not canonical Remodex screenshots.
- The Phase 5 device pass now has real empty/loading drawer references, but the loading anchor is still a startup-time runtime variant rather than the ideal steady-state overlay card: Android can retain grouped drawer content while the saved-reconnect flow is still rehydrating workspace context behind it.
- `android-device-captures/phase5/phase5-sidebar-loading.png` is intentionally screenshot-only for now; repeated `uiautomator dump` attempts kept resolving to the settled default drawer instead of the transient saved-reconnect loading frame.
- We still do not have real-device Android captures yet for Git/runtime sheets, About, approval dialogs, or notification-open recovery variants. Those remain tracked as later-phase/runtime backlog items rather than Phase 11 blockers.
