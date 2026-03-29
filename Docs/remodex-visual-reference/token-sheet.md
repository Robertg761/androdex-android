# Token Sheet

This token sheet records the code-derived visual values that repeatedly show up in Remodex. It is not a full design system export, but it is enough to stop guessing.

## Typography

Source: `CodexMobile/CodexMobile/Models/AppFont.swift`

### Family strategy

- Default prose font: iOS system font
- Optional prose override: Geist
- Optional mono-for-everything override: Geist Mono or JetBrains Mono
- Default code font: JetBrains Mono fallback chain, with Geist Mono preferred when selected

### Semantic scale

| Token | Size | Weight |
| --- | --- | --- |
| `body` | `15` | regular |
| `callout` | `14.5` | regular |
| `subheadline` | `14` | regular |
| `footnote` | `12` | regular |
| `caption` | `11` | regular |
| `caption2` | `10` | regular |
| `headline` | `15.5` | bold by default |
| `title2` | `20` | bold by default |
| `title3` | `18` | medium by default |
| `mono(body)` | `15` | regular |
| `mono(callout)` | `14.5` | regular |
| `mono(subheadline)` | `14` | regular |
| `mono(caption)` | `11` | regular |
| `mono(caption2)` | `10` | regular |

### Letter spacing

Source scan: `Views/Onboarding/OnboardingStepPage.swift`, `Views/Turn/StructuredUserInputCardView.swift`

- Default Remodex body, sidebar, toolbar, and composer text uses platform-default tracking unless a view opts in explicitly.
- Onboarding step eyebrow uses `.kerning(1.5)`.
- Structured-input section headers use `.kerning(0.4)`.

## Corner Radii

These values recur across the app and should be treated as canonical geometry until a screenshot proves otherwise.

| Surface | Radius |
| --- | --- |
| Sidebar header logo | `8` |
| Sidebar selected row / search field | `14` |
| File-change and review cards | `16` |
| Home / reconnect button shell | `18` |
| Trusted pair summary | `18` |
| QR scanner frame | `20` |
| Home empty-state logo block | `22` |
| Composer shell | `28` |
| Status card / large glass cards | `28` |

## Spacing Rhythm

| Pattern | Value |
| --- | --- |
| Sidebar outer horizontal padding | `16` |
| Sidebar row internal horizontal padding | `12` |
| Sidebar row vertical padding | `12` for parent rows, `4` for subagent rows |
| Sidebar search leading / trailing / vertical padding | `10 / 16 / 8` |
| Onboarding bottom safe-area content padding | `28` |
| Home empty-state status capsule padding | `14 x 7` |
| Home empty-state primary button padding | `20 x 14` |
| Composer outer padding | horizontal `12`, top `4`, bottom `4` |
| Composer text area padding | horizontal `16`, bottom `14` |
| Status card padding | `16` |
| About page horizontal padding | `20` |

## Core Surface Treatments

### Glass and translucency

Source: `Views/Shared/AdaptiveGlassModifier.swift`

- Preferred on supported iOS: `glassEffect`
- Fallback on older iOS: `.thinMaterial`
- Large floating surfaces typically use `.adaptiveGlass(.regular, in: RoundedRectangle(cornerRadius: 28))`

### Semantic fills

These are repeatedly used in Remodex and matter more than the current Androdex Material palette.

| Use | Remodex treatment |
| --- | --- |
| Primary background | `Color(.systemBackground)` |
| Sidebar selected row | `Color(.tertiarySystemFill).opacity(0.8)` |
| Search field fill | `Color(.tertiarySystemFill).opacity(0.8)` |
| Trusted pair summary fill | `Color(.tertiarySystemFill).opacity(0.55)` |
| Low-contrast stroke | `Color.primary.opacity(0.06)` |
| Slightly stronger hairline | `Color.primary.opacity(0.08)` |
| Secondary text | `.secondary` |
| Tertiary / muted state | `.tertiary` or `Color(.tertiaryLabel)` |

## Accent Colors

### Named assets

| Token | Light | Dark |
| --- | --- | --- |
| `plan` | display-P3 `r 0.430 g 0.740 b 0.983` | sRGB `#0097FF` |
| `command` | sRGB `r 1.000 g 0.832 b 0.473` | same hue at `alpha 0.797` |

### Inline semantic accents

| Use | Color |
| --- | --- |
| Connected dot | green |
| Connecting / syncing dot | orange |
| Offline dot | tertiary label |
| File path link | blue |
| Diff additions | green |
| Diff deletions | red |
| Archived badge text | orange |
| Review priority chips | red, orange, yellow, blue by severity |

## Component Measurements

### Onboarding welcome

Source: `Views/Onboarding/OnboardingWelcomePage.swift`

- App logo: `72 x 72`
- Logo radius: `18`
- Hero content vertical stack spacing: `24`
- Headline size: `32`, bold
- Subtitle spacing inside title block: `8`

### Home empty state

Source: `Views/Home/HomeEmptyStateView.swift`

- Logo: `88 x 88`
- Logo radius: `22`
- Status dot: `6 x 6`
- Status capsule stroke: `1`
- Primary button radius: `18`
- Content max width: `280`

### Sidebar header and rows

Sources: `Views/Sidebar/SidebarHeaderView.swift`, `Views/Sidebar/SidebarThreadRowView.swift`

- Header logo: `26 x 26`
- Header spacing between logo and title: `10`
- Title-leading indicator slot: `16`
- Run badge clear fallback: `10 x 10`
- Expansion toggle frame: `18 x 18`

### Composer

Source: `Views/Turn/TurnComposerView.swift`

- Main shell radius: `28`
- Internal stack spacing above composer: `6`
- Placeholder font size: `14`
- Default dynamic text height floor: `32`
- Autocomplete / voice overlay offset: `-8`
- Outer shell padding: horizontal `12`, top `4`, bottom `4`
- Input lane padding: horizontal `16`, bottom `14`, top `accessoryState.topInputPadding + 4`
- Autocomplete inset shell: `4`
- File autocomplete row height: `38`
- Skill autocomplete row height: `50`
- Slash-command autocomplete row height: `50`
- Autocomplete max visible rows: `6`

### Bubbles and chips

Sources: `Views/Turn/TurnMessageComponents.swift`, `Views/Turn/FileMentionChip.swift`, `Views/Turn/QueuedDraftsPanel.swift`

- User message bubble does not use a hard coded percentage width cap; it is constrained by a leading `Spacer(minLength: 60)`.
- User bubble padding: horizontal `16`, vertical `12`
- User bubble radius: `24`
- Mention/action chip padding: horizontal `8`, vertical `4`
- Mention/action chip radius: `8`
- Chip row spacing: `6`
- Mention chip remove affordance: `14 x 14`
- Queued-draft restore pill height: `24`
- Queued-draft steer pill height: `24`

### Sheets and overlays

Sources: `Views/Turn/TurnStatusSheet.swift`, `Views/Turn/TurnToolbarContent.swift`, `Views/Turn/TurnGitBranchSelector.swift`, `Views/SettingsView.swift`

- Status sheet detents: `.fraction(0.4)`, `.medium`, `.large`
- Thread path sheet detents: `.fraction(0.4)`, `.medium`
- Branch picker popover size: min `300 x 260`, ideal `360 x 360`, max `400 x 480`
- Status card radius inside sheet: `28`
- Several modal surfaces rely on the system drag indicator via `.presentationDragIndicator(.visible)` rather than a custom handle, so the canonical Phase 0 measurement is "system drag indicator, no custom geometry override."

### Toolbar diff pill

Source: `Views/Turn/TurnToolbarContent.swift`

- Minimum pill width: `50`
- Minimum pill height: `28`

## Animation Cues

These are the few concrete motion values visible in source:

| State | Timing |
| --- | --- |
| Sidebar search cancel visibility | `.easeInOut(duration: 0.2)` |
| Composer focus / layout changes | `.easeInOut(duration: 0.18)` |
| Voice copy / bridge update micro-state | `.easeInOut(duration: 0.2)` |
| Home empty-state busy status pulse | `.easeInOut(duration: 0.8)` repeating |

## What This Means For Androdex

- `Geist` is not the canonical Remodex default. The canonical baseline is the iOS system font metrics in `AppFont`.
- The Remodex look is driven more by semantic fills, glass, and compact geometry than by saturated brand color.
- The current Android `MaterialTheme` layer is too generic to express these values cleanly. Phase 1 should promote these into explicit Remodex-style tokens rather than continue using stock Material names.

## Phase 1 Android Mapping

Last updated: `2026-03-29`

- `android/app/src/main/java/io/androdex/android/ui/theme/Theme.kt` now exposes semantic Android-side tokens for app background, grouped background, raised/secondary surfaces, selected row fill, separator and hairline, text tiers, semantic accents, input/search fills, sheet tinting, overlay dimming, and status dots.
- `android/app/src/main/java/io/androdex/android/ui/theme/Theme.kt` also centralizes recurring geometry for the Remodex spacing scale, radii, page padding, sidebar rhythm, chip height, button height, icon button sizing, sheet handle size, and max content width.
- `android/app/src/main/java/io/androdex/android/ui/theme/Type.kt` now prioritizes Remodex sizing and density over the prior Geist-first setup. The Android compromise is `SansSerif` plus `Monospace`, sized to the extracted Remodex `AppFont` scale.
- Shared primitives now live in `android/app/src/main/java/io/androdex/android/ui/shared/RemodexPrimitives.kt` so sidebar rows, search shells, grouped surfaces, compact headers, pills, buttons, inputs, dividers, and alert shells can opt into the same token source instead of re-styling Material defaults independently.
- The phase 1 refresh surfaces now consume those shared geometry tokens directly in home, pairing, sidebar, shared-status, and overlay code instead of carrying duplicated per-screen padding and spacing literals.
