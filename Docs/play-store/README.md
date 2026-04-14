# Google Play Release Pack

This folder collects the repo-managed assets and notes needed to submit Androdex to Google Play.

## What's here

- `assets/`: listing artwork and screenshots
- `policies/`: privacy, data-safety, and permission drafts
- `review/`: reviewer notes and submission checklist

## Current app build facts

- package name: `io.androdex.android`
- current version: `0.2.2` (`versionCode 14`)
- release artifact for Play: `android/app/build/outputs/bundle/release/app-release.aab`
- target privacy policy URL: `https://androdex.xyz/privacy-policy/`

## Current product framing

The shipped Android app is a paired shell for an Androdex desktop/server environment:

- the desktop/server issues the pairing URL
- Android scans or pastes that URL
- Android opens the paired Androdex web app inside a WebView

Reviewer guidance and screenshots should describe that flow, not the older bridge-first/native-client architecture.

Fresh phone captures for this flow now live at:

- `assets/phone-screenshots/Current-Thread.png`
- `assets/phone-screenshots/Current-Sidebar.png`

## Recommended submission flow

1. Build a signed AAB with `cd android && ./gradlew bundleRelease`.
2. Upload the AAB to an internal or closed test track first.
3. Use the copy in this folder for listing, review, and policy notes.
4. Upload current screenshots that match the pairing-link plus paired-webview flow.
5. Confirm support contact details, privacy policy URL, and reviewer instructions before rollout.

## Important manual review items

- The app is only useful with a reachable paired Androdex desktop/server environment.
- `android:usesCleartextTraffic="true"` is still enabled because developers may pair to local-network environments during testing.
- Reviewer instructions should include either a live paired environment or current screenshots/video of the pairing flow.
