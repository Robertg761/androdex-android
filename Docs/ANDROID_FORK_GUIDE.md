# Android Fork and Publishing Guide

This fork adds Android support to Remodex and repackages the Windows + Android flow as Relaydex. If you want to publish your own version, the practical flow is:

1. Keep the upstream `LICENSE` file in your fork.
2. Keep the original copyright notice.
3. Add your own copyright notice only for the parts you changed.
4. Make it clear in your README and store listing that your build is a fork or derivative, not the official upstream mobile app.
5. Use your own Android application ID, signing key, screenshots, and release channel.

## Why this is allowed

Remodex is released under the ISC license. That license allows you to use, copy, modify, and distribute the software as long as the copyright notice and permission notice stay with the copies.

The upstream `CONTRIBUTING.md` says the author is not actively accepting contributions right now. That affects upstream pull requests, not your ability to fork and publish your own compatible build.

## Recommended repo flow

1. Fork the upstream repository.
2. Keep upstream history intact.
3. Do Android-specific work on your fork or on a separate `android` branch first.
4. If the upstream project later wants the changes, open a small, focused PR instead of trying to upstream the entire Android port at once.

## Recommended app/store flow

1. Pick a distinct app name such as `Relaydex` or another non-confusing derivative name.
2. Change the Android `applicationId` before publishing to the Play Store.
3. Add attribution in the app description and README.
4. Describe exactly what still depends on the original bridge, relay, or protocol.
5. Document whether your build is using the hosted relay or a self-hosted relay.

## Paid Android app strategy

If you want a buy-once Android app:

1. Keep the bridge and the Android app clearly branded as your fork.
2. Publish the Android app under your own Play Console account and signing key.
3. Set the Play Store listing itself as a paid app.
4. Keep npm distribution for the host bridge separate from Play distribution for the Android app.
5. Make the store listing explicit that the app is a companion remote control for a locally running Codex bridge on the user's own computer.

This fork is being prepared around that model:

- npm package name: `relaydex`
- Android application ID: `io.relaydex.android`
- Android app name: `Relaydex`

## Brand and trademark caution

The source license covers code, not third-party trademarks. If you mention OpenAI or Codex in a public listing, review the current OpenAI brand guidance before publishing:

- [OpenAI Brand Guidelines](https://openai.com/brand/)
- [OpenAI Terms of Use](https://openai.com/policies/terms-of-use/)

## Minimum checklist before release

- Confirm the fork keeps `LICENSE`.
- Confirm the store listing does not imply official upstream ownership.
- Confirm your screenshots show the Android client, not the upstream iOS app.
- Confirm your security notes match the actual relay setup you ship.
- Confirm your package name, icons, and signing identity are your own.
