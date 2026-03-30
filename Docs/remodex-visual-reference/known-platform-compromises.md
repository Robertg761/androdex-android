# Known Platform Compromises

These are the places where Android cannot be a literal 1:1 copy of Remodex because the canonical app is an iPhone SwiftUI app.

The rule is not "ignore the difference." The rule is "match the perceptual result as closely as possible and keep the difference explicit."

## 1. Liquid Glass vs Android Surface Rendering

Remodex uses adaptive glass:

- `glassEffect` on supported iOS versions
- `.thinMaterial` on fallback paths

Android Compose has no direct equivalent to iOS liquid glass. The closest acceptable compromise is:

- translucent surfaces with carefully tuned blur/tint where possible
- otherwise low-contrast layered fills that preserve the same floating separation and corner geometry

We should not substitute opaque Material cards just because they are easier.

## 2. iOS Semantic Fills vs Material 3 Tokens

Remodex leans on:

- `systemBackground`
- `tertiarySystemFill`
- `placeholderText`
- `secondary` and `tertiary` foreground styles

Android Material 3 colors are not a drop-in replacement for those semantics. The compromise should be a custom Android token set that matches the visual output of those fills, not the naming of Material roles.

## 3. SF Pro Metrics vs Android Font Availability

Remodex defaults to the native iOS system font via `AppFont`, not to Geist. Android cannot use SF Pro as a normal system dependency.

Closest acceptable compromise:

- match Remodex text sizes, hierarchy, and density first
- use the closest legal/available Android font family only after size and rhythm are correct

This is why copying the current Geist-based theme is not enough.

Phase 1 note: the Android design-system pass now uses `SansSerif` plus `Monospace` in `Type.kt` so the app tracks Remodex density and hierarchy more closely than the earlier Geist-first setup. This keeps the compromise explicit until a closer licensed/system-available family is proven on Android.

## 4. SwiftUI Navigation Bars And Toolbar Items

Remodex relies on:

- `.navigationBarTitleDisplayMode(.inline)`
- principal-title layouts
- adaptive toolbar items with compact circular affordances

Compose top app bars tend to read larger and heavier by default. The compromise should preserve:

- inline title density
- compact trailing controls
- smaller toolbar affordances
- title plus subtitle stacking behavior

## 5. iOS Sheets And Detents

Remodex sheets use native iOS presentation detents and drag behavior. Android bottom sheets will never behave identically.

Closest acceptable compromise:

- match corner radius, top handle geometry, background treatment, and content padding
- keep sheet heights perceptually aligned with the Remodex detent usage

## 6. Keyboard And Safe-Area Behavior

SwiftUI and Android Compose differ on:

- keyboard lift timing
- safe-area inset defaults
- toolbar overlap
- gesture/nav bar padding

The Android version should preserve the visual resting positions from Remodex even if the implementation mechanics differ.

## 7. SF Symbols Rendering

Remodex uses Apple symbol sizing and weight behavior in many places. Android icons will need manual tuning for:

- stroke weight
- optical size
- padding inside circular buttons

Using stock Material icon sizes will not match the Remodex result.

## 8. Camera Pairing Flow

The Remodex pairing surface is driven by AVFoundation camera preview and iOS permission UX. Android camera permissions and preview layers differ structurally.

Compromise target:

- keep the same full-screen black stage
- keep the same scan-frame sizing and instruction placement
- keep the same simple permission fallback hierarchy

## 9. Alert And Confirmation UI

SwiftUI confirmation dialogs and alerts do not map 1:1 to Material dialogs. The Android port should preserve:

- text density
- action ordering where platform conventions allow
- calm low-noise presentation

It should avoid heavy, card-like dialogs unless the Remodex state actually reads that way.

## 10. Motion Curves

Only a few timings are explicit in source, and many native iOS transitions come from platform defaults. Android cannot perfectly replicate those defaults.

The acceptable compromise is:

- use short ease-in-out curves for search/composer state changes
- avoid springy or highly expressive Material motion that makes the app feel like a different product

## 11. Drawer Reveal And System Bar Rendering

Remodex gets some of its shell feel from iOS-native navigation, drawer, and system-bar blending that Compose cannot reproduce literally.

The acceptable Android compromise is:

- use a shared connected-shell host so home and thread transitions happen inside the same drawer container
- prefer a dismissible drawer reveal over a fully separate modal sheet so the current screen remains partially visible
- keep status and navigation bars visually transparent and let the app shell own the top/bottom edge treatment instead of introducing opaque platform chrome

## 12. Paged Onboarding vs Scrollable Pairing

Remodex splits its onboarding into dedicated paged welcome/features/step views before the QR pairing flow. Androdex still has to keep QR scan, saved reconnect, recovery copy, and manual payload entry reachable in one host-local Android surface.

The acceptable compromise is:

- borrow the Remodex onboarding hierarchy, spacing, and tone inside a single scrollable pairing screen
- keep the saved reconnect and manual payload actions immediately available instead of hiding them behind separate onboarding pages
- document that this is a workflow-preserving consolidation, not a claim of literal navigation parity

## 13. Sidebar Runtime Data vs Drawer-Specific Capture States

The refreshed Android drawer can now match the Remodex header, search rhythm, grouped sections, thread rows, and footer capsule much more closely. The remaining sidebar nuance is not a geometry or token mismatch; it is a reconnect-timing/runtime-state exposure problem.

The acceptable compromise is:

- keep the drawer visuals aligned with the Remodex grouped-sidebar structure
- accept a saved-reconnect loading capture where the drawer can still retain grouped thread content while the home shell behind it is rehydrating workspace and model state
- document that runtime-specific loading nuance explicitly instead of pretending Android exposes the exact same steady-state drawer overlay timing as Remodex
- treat the runtime nuance as a verification note, not as permission to fall back to a looser Material drawer design

## 14. Thread Shell Empty-Stage Height And Footer Anchoring

Remodex gets a lot of its turn-screen feel from iPhone-safe-area math: a compact inline header, a large mostly-empty conversation stage while idle, and a composer/footer stack that sits in a distinct bottom zone above the home indicator.

Android Compose cannot reproduce that geometry literally because:

- status-bar and navigation-bar inset consumption differ by device and gesture mode
- keyboard lift timing differs from SwiftUI
- the same screen may need to share width constraints across phones, tablets, and foldables

The acceptable Android compromise is:

- keep a custom compact header instead of falling back to a stock Material top bar
- center the conversation rail with an explicit max width so the empty stage still reads wide and calm on larger displays
- anchor the jump-to-latest control and footer stack to that same rail rather than to the full screen width
- accept small device-specific differences in the exact vertical emptiness above the composer, but keep the perceptual split between timeline stage and footer region
- allow later capture passes to fill in additional running or banner-bearing device references without reopening the completed shell implementation work

## Non-Compromises

These are not valid reasons to diverge from Remodex:

- "Material looks more Android-native"
- "the current Androdex cards already work"
- "the colors are close enough"
- "Compose defaults are easier"

If Android can visually match the Remodex result, we should match it.
