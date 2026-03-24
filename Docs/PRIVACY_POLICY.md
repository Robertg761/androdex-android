# Privacy Policy for Androdex

Last updated: 2026-03-12

Androdex is a local-first Android remote client and host bridge for controlling Codex running on the user's own computer.

## Who this policy applies to

This policy applies to the Androdex Android app and the Androdex host bridge.

## What Androdex does

Androdex is a local-first remote control system:

- the Android app acts as a remote control client
- the host bridge runs on the user's own computer
- Codex runs locally on the user's own computer

## Data handled by the app

Depending on how the app is used, Androdex may handle:

- pairing information such as relay URL, session ID, device ID, and cryptographic public keys
- user-provided content such as prompts and conversation messages
- thread and conversation metadata
- approval actions and local workflow actions initiated by the user

## How data is used

Androdex uses data to:

- pair the Android client with the user's host bridge
- route messages between the Android app and the host bridge
- display threads, messages, and approval requests in the app
- maintain secure encrypted sessions
- store pairing and trusted-device information locally on the device

## Encryption and transport

Androdex uses a secure pairing and encrypted transport design between the Android app and the host bridge.

The relay may still observe limited connection metadata required to route traffic, such as session identifiers and connection timing, but application payloads are intended to be encrypted after the secure handshake completes.

## Local storage

The Android app may store limited data locally on the device, including:

- pairing details
- trusted-device records
- encrypted local persistence required for reconnect and secure operation

## Data sharing

Androdex is not designed to sell user data.

Data may be transmitted as part of core app functionality to:

- the relay service used to connect the Android app and the host bridge
- the host bridge running on the user's own computer

## Ads

Androdex does not display third-party advertising.

## Account requirement

Androdex does not require an in-app account login to function, but it does require the user to operate a compatible host bridge and local Codex environment on their own computer.

## Children

Androdex is not directed to children.

## Data retention

Data retention depends on:

- what is stored locally on the Android device
- what is stored by the user on their own host computer
- what relay infrastructure is used

Users can remove the app and clear locally stored app data from their Android device. Users can also remove or reset host-side bridge state on their own computer.

## Contact

Support:

- GitHub repository: `https://github.com/Robertg761/androdex`
- Issue tracker: `https://github.com/Robertg761/androdex/issues`
