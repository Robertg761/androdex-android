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
| `android-device-captures/phase1/phase1-pairing-refresh.png` | Pairing / saved-reconnect shell after the semantic token pass | Captured from a cold app launch while the saved reconnect handshake was still in progress, so the refreshed pairing hero, buttons, status capsule, and recovery cards are all visible together. |
| `android-device-captures/phase1/phase1-home-refresh.png` | Connected home shell after the semantic token pass | Captured after the reconnect settled, showing the refreshed landing hero, status capsule, project card, and recent-thread styling on the live device. |
| `android-device-captures/phase1/phase1-sidebar-open-refresh.png` | Sidebar open state after the semantic token pass | Captured from the refreshed build with the drawer open so the updated header, search shell, grouped rows, and footer chrome are anchored to the same phase 1 token and primitive pass. |

## Supporting Dumps

- Route-based `*--androdex.xml` files are the canonical UI dumps for the new Android screenshots.
- The Phase 1 refresh pass also added `android-device-captures/phase1/phase1-pairing-refresh.xml`, `android-device-captures/phase1/phase1-home-refresh.xml`, and `android-device-captures/phase1/phase1-sidebar-open-refresh.xml`.
- Older `uidump-*.xml` files are retained as scratch/session dumps for pairing and thread exploration.
- `latest-pair-output.txt` contains the host-side pairing output captured during the fresh repair/pair flow.

## Notes

- Two earlier captures drifted into Google Messages and were removed from the reference set instead of being reused.
- The Android captures are counterpart references for Androdex, not canonical Remodex screenshots.
- We still do not have real-device Android captures yet for Git/runtime sheets, About, approval dialogs, or notification-open recovery variants.
