# Android Compose Structure

The Android app UI is now split so `AndrodexApp.kt` acts as a small router instead of a monolith.

Key pieces:

- `android/app/src/main/java/io/androdex/android/ui/state/AndrodexFeatureState.kt`
  Adapts `AndrodexUiState` into feature-focused screen models for pairing, home/sidebar, turn timeline, composer, and settings.
- `android/app/src/main/java/io/androdex/android/ui/pairing/`
  Host pairing and reconnect entry UI.
- `android/app/src/main/java/io/androdex/android/ui/home/`
  Connected home container plus project picker flow.
- `android/app/src/main/java/io/androdex/android/ui/sidebar/`
  Thread list rendering and empty-state behavior.
- `android/app/src/main/java/io/androdex/android/ui/turn/`
  Thread timeline and composer UI.
- `android/app/src/main/java/io/androdex/android/ui/shared/`
  Approval/error overlays and shared connection or busy presentation.
- `android/app/src/main/java/io/androdex/android/ui/settings/`
  Runtime settings sheet.

Why this split:

- Keeps host-local runtime and connection behavior in the existing service/view-model layer.
- Preserves item-aware timeline rendering while making the turn screen easier to modify.
- Mirrors the Remodex-style separation between home, sidebar, and turn surfaces without reintroducing repo filtering or changing reconnect behavior.
