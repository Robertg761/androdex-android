# Privacy Policy for Androdex

Last updated: 2026-04-14

Androdex is an Android companion app for a user-controlled Androdex desktop/server environment.

## Who this policy applies to

This policy applies to the Android app in this repository.

The Android app pairs to a desktop/server environment that the user controls. That paired environment may have its own local data retention and operational settings.

## What the Android app does

The current Android app:

- accepts a desktop pairing URL or QR code
- stores the paired origin and last opened route locally on the device
- opens the paired Androdex web app inside an Android WebView
- provides Android-native helpers such as QR scanning, file picking, camera handoff, and reopen routing

## Data handled by the app

Depending on how the app is used, the Android app may handle:

- pairing links and paired-origin metadata
- a display label for the paired environment
- the last opened in-app URL for that paired environment
- WebView cookies and first-party web storage used by the paired Androdex environment
- user-provided prompts, messages, and uploaded files that the paired environment processes
- optional camera or gallery selections when the user chooses to upload an attachment
- notification-open metadata when the paired environment is configured to reopen the app to a specific route

## How data is used

The Android app uses data to:

- establish or restore a trusted connection to the paired desktop/server environment
- reopen the correct paired route after app restarts or notification opens
- display the paired Androdex web app in-app
- support first-party file upload and camera capture flows inside that paired web app

## Local storage

The Android app stores limited local data on the device, including:

- paired origin
- optional display label
- optional bootstrap pairing URL
- last opened paired URL
- WebView cookies and site data for the paired first-party environment

## Data sharing

Androdex is not designed to sell user data.

Data may be transmitted to:

- the paired Androdex desktop/server environment the user chose
- any infrastructure that environment uses, such as its own network host, reverse proxy, or notification backend

## Ads

Androdex does not display third-party advertising.

## Account requirement

The Android app does not require a separate in-app Androdex account. Full functionality depends on access to a compatible Androdex desktop/server environment that the user controls or has been given access to.

## Children

Androdex is not directed to children.

## Data retention

Retention depends on:

- what the Android app stores locally on the device
- what the paired desktop/server environment stores

Users can remove the app, clear Android app storage, or clear the current pairing from the app. Users can also remove or reset data in their own paired environment.

## Contact

Support:

- GitHub repository: `https://github.com/Robertg761/androdex`
- Issue tracker: `https://github.com/Robertg761/androdex/issues`
