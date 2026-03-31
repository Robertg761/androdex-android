# Permission Declaration Notes

## Declared runtime permissions

- `android.permission.CAMERA`
  - Purpose: lets the user take a photo and attach it to a message sent to the paired host workflow
  - Required: no, optional feature

- `android.permission.POST_NOTIFICATIONS`
  - Purpose: optional run-completion notifications when the notification path is configured
  - Required: no, optional feature

## Non-runtime permissions and capabilities

- `android.permission.INTERNET`
  - Purpose: relay, host bridge, pairing, and remote-control communication

- camera features marked optional
  - `android.hardware.camera`
  - `android.hardware.camera.autofocus`

## Reviewer note worth including

The app keeps `android:usesCleartextTraffic="true"` because the product supports user-chosen local or self-hosted relay paths, including local-only developer setups that may use `ws://` during LAN testing. This is not used to ship hardcoded private endpoints.
