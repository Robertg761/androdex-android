# Release Checklist

This checklist covers the two deliverables of this fork:

- npm package: `relaydex`
- paid Android app: `Relaydex`

## 1. Before npm publish

- Confirm the package name is still available on npm.
- Review [`phodex-bridge/package.json`](../phodex-bridge/package.json).
- Review [`phodex-bridge/README.md`](../phodex-bridge/README.md).
- Confirm [`phodex-bridge/LICENSE`](../phodex-bridge/LICENSE) is included.
- Run:

```sh
cd phodex-bridge
npm test
npm pack
```

- Inspect the tarball contents before `npm publish`.

## 2. Before Android release build

- Create `android/keystore.properties` from `android/keystore.properties.example`.
- Place your release keystore at the path referenced by `storeFile`.
- Build a release bundle:

```sh
cd android
./gradlew bundleRelease
```

- Verify the generated `.aab` installs and pairs correctly on a real Android device.

## 3. Before Play Store submission

- Use your own icon, screenshots, and store text.
- Keep `independent fork of Remodex` in the long description.
- Keep `not the official Remodex app` in the listing copy.
- Make the host requirement explicit: local Codex runs on the user's own computer.
- Confirm the app name, package name, and signing key are yours.

## 4. Launch copy

- GitHub/X templates: [`Docs/LAUNCH_COPY.md`](./LAUNCH_COPY.md)
- Play Store copy: [`Docs/PLAY_STORE_COPY.md`](./PLAY_STORE_COPY.md)
