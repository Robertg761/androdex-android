# Data Safety Draft

This is a working Play Console draft based on the current Android app behavior in this repo. Validate it against the final shipped build before submitting.

## Core interpretation

- App purpose: Android shell for a paired Androdex desktop/server environment
- No ads
- No data selling
- No third-party analytics SDKs are declared in the Android app
- The app primarily stores pairing metadata locally and loads the paired first-party web app in a WebView

## Likely Play Console answers

### Collected or transmitted by the app

- `User content > Messages`
  - Why: prompts and thread content displayed and processed by the paired environment
  - Required for app functionality: yes
  - User can choose to provide it: yes

- `Photos and videos`
  - Why: optional file or camera uploads chosen by the user inside the paired Androdex UI
  - Required for app functionality: no
  - User can choose to provide it: yes

- `Device or other IDs`
  - Why: pairing-link metadata, paired-origin state, and first-party session/cookie state used by the paired environment
  - Required for app functionality: yes

### Data not intentionally collected by this Android app

- precise location
- contacts
- calendar
- health and fitness
- payment information
- audio recordings

## Handling notes

- Encryption in transit: depends on the paired environment URL the user chooses; encourage HTTPS for network-reachable environments
- Data is not sold: yes
- Data sharing for advertising or analytics: no
- Data deletion request path: indirect
  - users can clear Android app storage, remove the app, clear pairing from the app, and remove data from the paired environment they control

## Review before submission

- Confirm whether your final build still uses only the current pairing-link and WebView flow
- Confirm whether any optional notification path adds additional SDK-level data collection
- Re-check the final privacy policy URL used in the Play listing
