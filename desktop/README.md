# Androdex Desktop Control Room

Electron control panel for the local Androdex macOS bridge.

The app is meant to replace the repetitive host-side terminal workflow with a visual control room. It reads and updates the same local bridge state the CLI uses, so status, doctor output, runtime switching, pairing, and T3 descriptor setup stay in sync.

## Run

```bash
cd /Users/robert/Documents/Projects/androdex/desktop
npm install
npm start
```

## Current Scope

- compact host runtime switcher for `Codex` vs `T3 Code`
- live bridge/service status
- pairing QR generation
- T3 runtime-session descriptor management
- doctor recommendations and bridge log tails
- same host runtime choice is now exposed in Android `Runtime Settings` too

## Notes

- This is a host-local control surface for the existing bridge, not a separate runtime.
- It does not launch T3 for you yet; it helps you point the bridge at an already running local T3 server or trusted runtime-session descriptor.
