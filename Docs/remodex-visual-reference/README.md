# Remodex Visual Reference Set

This folder is the Phase 0 reference package for [`Docs/remodex-visual-clone-plan.md`](/Users/robert/Documents/Projects/androdex/Docs/remodex-visual-clone-plan.md).

It is meant to answer one question before any more UI cloning work happens:

What is the canonical Remodex visual source of truth, and how do we map it to Androdex screen-by-screen?

## Source Of Truth

This package was assembled from these sources on March 29, 2026:

1. Upstream Remodex source at commit `fed641cc700b13db729662138cef856ac0378e0d`
   Source repo: <https://github.com/Emanuele-web04/remodex/tree/fed641cc700b13db729662138cef856ac0378e0d>
2. Remodex App Store listing for `Remodex - Remote AI Coding`
   App page: <https://apps.apple.com/us/app/remodex-remote-ai-coding/id6760243963>
3. App Store lookup metadata showing version `1.0` released on March 26, 2026
4. Bundled Remodex assets in `Assets.xcassets` and onboarding artwork in the upstream repo
5. Live Androdex Android device captures taken on a host-connected Samsung `SM-S928W` over `adb`

## What Is In Here

- [`capture-manifest.md`](/Users/robert/Documents/Projects/androdex/Docs/remodex-visual-reference/capture-manifest.md)
  Exact route and state inventory, with screenshot/video filenames and current evidence status.
- [`parity-board.md`](/Users/robert/Documents/Projects/androdex/Docs/remodex-visual-reference/parity-board.md)
  Side-by-side board mapping each Remodex state to the Androdex surface that must match it.
- [`token-sheet.md`](/Users/robert/Documents/Projects/androdex/Docs/remodex-visual-reference/token-sheet.md)
  Code-derived visual tokens and geometry extracted from the Remodex app.
- [`known-platform-compromises.md`](/Users/robert/Documents/Projects/androdex/Docs/remodex-visual-reference/known-platform-compromises.md)
  Explicit list of places where Android cannot be a pixel-for-pixel clone of iOS.
- [`android-device-capture-log.md`](/Users/robert/Documents/Projects/androdex/Docs/remodex-visual-reference/android-device-capture-log.md)
  Verified Android-side screenshots captured from the real Androdex app to anchor the parity work in actual runtime output.
- [`android-device-captures/`](/Users/robert/Documents/Projects/androdex/Docs/remodex-visual-reference/android-device-captures)
  Raw screenshots, UI dumps, and pairing logs from the March 29, 2026 device pass.

## Important Constraint

The public sources do not expose a complete screenshot set for every required state. The upstream repo contains the real SwiftUI implementation and a small asset set, but not a full exported simulator/device gallery. The App Store metadata also does not return the screenshot array for this app.

Because of that, this reference set treats the Remodex SwiftUI source as the canonical baseline and marks any state that still needs a simulator/device capture as `capture-required` instead of inventing visual evidence.

That keeps Phase 0 honest and reusable:

- exact filenames are defined
- source view ownership is defined
- state coverage is defined
- code-derived tokens are defined
- unresolved capture work is isolated instead of mixed into implementation work

With the manifest, token sheet, and parity board now covering every Phase 0 surface, the remaining gaps are explicitly narrowed to runtime-only recovery captures. Those follow-ups stay documented, but they no longer block the visual-clone implementation phases.

## Android Device Coverage

The real-device Android capture pass does not replace the Remodex source of truth, but it does remove guesswork from the Androdex side of the parity board.

As of March 29, 2026 we now have verified Androdex runtime captures for:

- sidebar closed shell
- sidebar open default
- sidebar search focused
- grouped sidebar threads expanded
- settings root
- QR scanner
- repair-pairing-required recovery
- active thread shell with composer controls visible

Prefer the route-based `*--androdex.png` files when a canonical Android counterpart exists. Older `androdex-*.png` files are retained as exploratory scratch captures for states that do not yet have a route-based filename.
