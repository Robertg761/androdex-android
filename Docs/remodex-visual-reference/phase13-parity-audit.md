# Phase 13 Final Parity Audit

This note closes the verification and parity-enforcement slice of the Remodex visual clone effort.

It does not claim every Android surface is already a literal 1:1 clone. It does make the current source of truth, allowed compromises, enforcement rules, and remaining backlog explicit so the repo no longer relies on unstated assumptions.

Last updated: `2026-03-30`

## Source Of Truth

Remodex remains the visual source of truth for any user-visible Androdex UI work.

Use this order of precedence when reviewing or changing UI:

1. [`Docs/remodex-visual-reference/capture-manifest.md`](/Users/robert/Documents/Projects/androdex/Docs/remodex-visual-reference/capture-manifest.md)
2. [`Docs/remodex-visual-reference/parity-board.md`](/Users/robert/Documents/Projects/androdex/Docs/remodex-visual-reference/parity-board.md)
3. [`Docs/remodex-visual-reference/token-sheet.md`](/Users/robert/Documents/Projects/androdex/Docs/remodex-visual-reference/token-sheet.md)
4. [`Docs/remodex-visual-reference/known-platform-compromises.md`](/Users/robert/Documents/Projects/androdex/Docs/remodex-visual-reference/known-platform-compromises.md)
5. Existing Android capture artifacts under [`Docs/remodex-visual-reference/android-device-captures/`](/Users/robert/Documents/Projects/androdex/Docs/remodex-visual-reference/android-device-captures)

If the Android implementation and the reference package disagree, the reference package wins unless the difference is documented as an allowed compromise.

## Allowed Compromises

Allowed compromises are limited to the Android/iOS differences already documented in [`Docs/remodex-visual-reference/known-platform-compromises.md`](/Users/robert/Documents/Projects/androdex/Docs/remodex-visual-reference/known-platform-compromises.md).

That means:

- no new stock Material surfaces unless they are fully restyled to read like Remodex
- no "more Android-native" restyling as a justification for drift
- no undocumented spacing, motion, sheet, alert, or typography deviations
- no silent backlog; if a state is still unverified, say so in the parity docs

## Enforcement Rules

During the clone effort, any PR that changes user-visible Android UI should:

- include Android screenshots or recordings for each touched state, or explicitly mark the missing capture as backlog
- compare the changed state against the Remodex reference package before merge
- reuse shared Remodex primitives and tokens instead of adding raw screen-local styling or Material-default components
- update the parity docs when the verification status, evidence links, or compromise list changes

The active workflow hooks for that enforcement now live in:

- [`/Users/robert/Documents/Projects/androdex/.github/pull_request_template.md`](/Users/robert/Documents/Projects/androdex/.github/pull_request_template.md)
- [`/Users/robert/Documents/Projects/androdex/CONTRIBUTING.md`](/Users/robert/Documents/Projects/androdex/CONTRIBUTING.md)

## Audit Snapshot

The current repo-wide parity snapshot is:

| Area | Audit status | Notes |
| --- | --- | --- |
| Pairing / onboarding / recovery | `Not matched` | Phase 3 UI refresh landed, but refreshed Android captures for pairing, reconnect, and recovery states are still missing. |
| Home | `Perceptually exact` | Phase 4 and Phase 11 captures cover the main connected, empty, loading, and status-card variants. |
| Sidebar / drawer | `Perceptually exact` | Phase 5 and Phase 12 captures cover the primary shell, search, grouped, empty, and interaction states. |
| Thread shell | `Acceptable Android compromise` | Idle and motion states are archived; active and fork-banner references remain useful backlog, but current differences are documented as shell/runtime compromises rather than silent drift. |
| Timeline messages | `Perceptually exact` | Phase 7 mixed-content and streaming captures cover the main bubble, system, review, and execution families. |
| Composer | `Not matched` | Composer implementation landed, but idle, focused, armed, and autocomplete archive captures are still missing. |
| Attachments / queued drafts / tool inputs | `Not matched` | Implementation and tests landed, but the Android archival references for these surfaces are still missing. |
| Sheets / modal surfaces | `Not matched` | Shared sheet styling landed, but Android capture coverage is still incomplete across project picker, runtime, git, fork, about, and settings sheet variants. |
| Alerts / overlays / shared status cards | `Perceptually exact` | Phase 11 archived live Android references cover the current confirmation and status-card family. |
| Motion / interaction | `Acceptable Android compromise` | App-owned motion is aligned; remaining drawer and sheet timing differences stay within the documented platform-owned compromise set. |
| Notification-open and missing-thread recovery flows | `Not matched` | These runtime-driven states remain explicit capture backlog rather than inferred parity. |

## Explicit Backlog

These are the remaining parity items that still need archival Android evidence or a tighter comparison pass:

- refreshed Phase 3 pairing, reconnect, and recovery captures
- composer idle, focused, armed, review, subagents, and autocomplete captures
- attachment-tile, queued-draft, and structured tool-input captures
- project picker, runtime sheet, git sheet, fork sheet, settings sheet, and about sheet captures
- approval-request dialog and notification-open recovery captures
- optional thread-shell follow-up captures for the active and fork-banner variants

## Closeout

Phase 13 is complete when read as an audit-and-enforcement phase:

- the source of truth is explicit
- allowed compromises are explicit
- PR workflow enforcement is now written into the repo
- the remaining backlog is explicit instead of silent

Project-wide visual parity is still not fully closed until the backlog above is archived and reviewed against the Remodex references.
