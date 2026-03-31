# Google Play Release Pack

This folder collects the repo-managed assets and notes needed to submit Androdex to Google Play.

## What's here

- `assets/`: curated Play listing artwork and phone screenshots
- `listing/en-US/`: draft English store copy and first-release notes
- `policies/`: privacy, data safety, and permission declaration drafts based on the current app behavior
- `review/`: reviewer notes, app-access guidance, and a submission checklist

## Current app build facts

- package name: `io.androdex.android`
- current version: `0.2.0` (`versionCode 11`)
- release artifact for Play: `android/app/build/outputs/bundle/release/app-release.aab`
- target privacy policy URL: `https://androdex.xyz/privacy-policy/`

## Recommended submission flow

1. Build a signed AAB with `cd android && ./gradlew bundleRelease`.
2. Upload the AAB to an internal or closed test track first.
3. Use the copy in `listing/en-US/` for the store listing.
4. Upload the screenshots and graphics in `assets/`.
5. Fill the Play Console policy forms using the drafts in `policies/` and `review/`.
6. Confirm support contact details, privacy policy URL, and reviewer instructions before rollout.

## Important manual review items

- The app is only useful with a user-owned host bridge and local Codex runtime, so reviewer guidance matters.
- The app currently keeps `android:usesCleartextTraffic="true"` to support local or self-hosted relay scenarios. Call this out in reviewer notes instead of surprising Play review.
- If Play Console shows extra account-level testing or verification requirements for your developer account, complete those before production rollout.

## Reference links

- Android App Bundles for new apps: https://support.google.com/googleplay/android-developer/answer/2481797?hl=en
- Play app review prep hub: https://support.google.com/googleplay/android-developer/
