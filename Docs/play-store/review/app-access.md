# App Access and Review Notes

## Reviewer summary

Androdex does not use a public shared in-app account. Full functionality requires:

- a reachable Androdex desktop/server environment
- a one-time pairing URL or QR code from that environment
- pairing the Android app to that environment

The Android app is a shell for the paired desktop-served Androdex web app.

## Suggested Play Console reviewer note

Androdex is an Android companion app for a user-controlled Androdex desktop/server environment. Reviewers should use the provided pairing URL or QR code, pair the app, and then confirm that the Android app opens the paired web app in-app. If a live review environment is not available, attached screenshots and video show the current pairing screen, thread view, and sidebar flow.

## Suggested review steps

1. Open the provided Androdex desktop/server environment.
2. Generate the pairing URL or QR code from the environment.
3. Open the Android app and scan or paste the pairing link.
4. Confirm the Android app opens the paired Androdex UI.
5. Open a thread and verify that sidebar navigation works on mobile.

## If you provide a live review environment

Include:

- host availability window
- reachable host URL
- pairing URL or QR instructions
- any temporary account needed for the desktop/server environment only

## If you do not provide a live review environment

Attach a short review video plus screenshots that demonstrate:

1. the desktop pairing page
2. the paired Android thread view
3. the Android sidebar state
