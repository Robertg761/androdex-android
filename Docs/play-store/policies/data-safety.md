# Data Safety Draft

This is a working Play Console draft based on the current Android app behavior in this repo. Validate it against the final shipped build and your actual notification backend before submitting.

## Core interpretation

- App purpose: remote control for a user-owned host bridge and host-local Codex runtime
- No ads
- No data selling
- No third-party analytics SDKs are declared in the Android app
- Transport may involve the paired host bridge, a relay, and an optional push-notification path chosen by the operator

## Likely Play Console answers

### Collected or transmitted by the app

- `User content > Messages`
  - Why: prompts, thread messages, approvals, and conversation content are transmitted to the paired host workflow
  - Required for app functionality: yes
  - User can choose to provide it: yes
  - Processed in transit to host/relay: yes

- `User content > Photos and videos`
  - Why: optional image attachments from camera or gallery can be sent to the paired host workflow
  - Required for app functionality: no
  - User can choose to provide it: yes
  - Only when attached by the user: yes

- `Device or other IDs`
  - Why: pairing/session identifiers and optional push token registration
  - Required for core pairing/reconnect behavior: pairing/session identifiers yes
  - Optional notifications path: push token only when notifications are configured

### Data not intentionally collected by this Android app

- precise location
- contacts
- calendar
- health and fitness
- payment information
- audio recordings
- web browsing

## Handling notes

- Encryption in transit: yes, application payloads are intended to be encrypted after secure pairing completes
- Data is not sold: yes
- Data sharing for advertising or analytics: no
- Data deletion request path: indirect
  - users can clear Android app storage, remove the app, reset the pairing, and remove host-side data on their own machine

## Review before submission

- Confirm whether your final notification backend introduces any extra SDK-level data collection
- Confirm whether Play Console wants pairing identifiers represented only as `Device or other IDs` or also as a narrower internal-use note in reviewer comments
- Re-check the final privacy policy URL used in the listing
