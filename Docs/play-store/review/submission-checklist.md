# Submission Checklist

## Build and signing

- Confirm the signing keystore stays local and ignored by git
- Confirm any Play service-account JSON stays local and ignored by git
- Build a signed bundle with `cd android && ./gradlew bundleRelease`
- Verify the output exists at `android/app/build/outputs/bundle/release/app-release.aab`

## Store listing

- Paste `listing/en-US/short-description.txt`
- Paste `listing/en-US/full-description.txt`
- Upload `assets/app-icon-512.png`
- Upload `assets/feature-graphic-1024x500.png`
- Upload the screenshots in `assets/phone-screenshots/` in order
- Add support contact details in Play Console

## Policy forms

- Use `policies/privacy-policy.md` to verify the public privacy policy URL
- Use `policies/data-safety.md` as the starting point for the Data safety form
- Use `policies/permissions.md` when reviewing permission-related prompts or declarations

## Review prep

- Add the notes from `review/app-access.md` into Play Console reviewer instructions
- Explain the host-bridge dependency clearly
- Call out the local/self-hosted relay use case if asked about cleartext traffic support
- Prepare a short review video if you do not have a live review environment

## Release flow

- Upload to internal testing first
- Smoke-test pairing, thread open/create, project switching, Git actions, image attachments, and notification prompts on a release build
- Verify the privacy policy link opens correctly from the app
- Roll out to production only after the internal test bundle is accepted and reviewed
