# Android Device Capture Log

This file records the verified Android-side screenshots gathered during the live device pass on March 29, 2026.

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

## Supporting Dumps

- Route-based `*--androdex.xml` files are the canonical UI dumps for the new Android screenshots.
- Verified Phase 1 refresh dumps currently include `android-device-captures/phase1/phase1-home-refresh.xml` and `android-device-captures/phase1/phase1-sidebar-open-refresh.xml`.
- Verified Phase 2 shell dumps now include `android-device-captures/phase2/phase2-home-shell.xml`, `android-device-captures/phase2/phase2-sidebar-open-shell.xml`, and `android-device-captures/phase2/phase2-thread-shell.xml`.
- `android-device-captures/phase1/phase1-pairing-refresh.xml` is still a useful scratch artifact, but a fresh saved-reconnect recapture would align it better with `phase1-pairing-refresh.png`.
- Older `uidump-*.xml` files are retained as scratch/session dumps for pairing and thread exploration.
- `latest-pair-output.txt` contains the host-side pairing output captured during the fresh repair/pair flow.

## Notes

- A stale Phase 1 sidebar refresh artifact drifted out of app context and was replaced with a live Androdex recapture; earlier non-Androdex captures remain excluded from the reference set.
- The Android captures are counterpart references for Androdex, not canonical Remodex screenshots.
- We still do not have real-device Android captures yet for Git/runtime sheets, About, approval dialogs, or notification-open recovery variants. Those remain tracked as later-phase/runtime backlog items rather than Phase 1 blockers.
