# Android releases

Release `0.11.0` (`versionCode = 18`) adds the thread Agents sheet: a header chip and menu item
list this turn's subagents, rows open child threads, back returns to the parent, provider-native
children are read-only, and eligible agents can be stopped. Check the sheet against a real
environment with running subagents on a device.

Release `0.10.0` (`versionCode = 17`) probes saved machines on open and auto-connects to a
reachable one, shows images inline in threads, renders GitHub-flavoured Markdown (tables,
lists, quotes, links), stops write controls flickering while agents work, and tidies thread
rows. Check machine status dots and auto-connect against a real environment on a device.

Release `0.9.1` (`versionCode = 16`) keeps the conversation pinned to the latest message
when the keyboard opens, unless the reader had scrolled up.

Release `0.9.0` (`versionCode = 15`) redesigns the app: new tokens, bundled Inter and
JetBrains Mono (OFL-1.1, see `licenses/`), new adaptive launcher icon and splash, and
adaptive phone, two-pane and rail layouts. Check the launcher icon and onboarding on a device.

Release `0.8.1` (`versionCode = 14`) fixes settlement Undo during conversation
refresh, pin ordering, snooze restoration, stale project filters and date pickers.

GitHub Actions verification is manual (`workflow_dispatch`) while hosted Actions
usage is exhausted. Pushes, tags and pull requests do not launch paid runners.
Run the verification commands below locally before publishing; this is not an
automatic CI pass. Restore push/pull-request triggers when quota is available.

Release `0.8.0` (`versionCode = 13`) adds scoped thread and row actions,
settlement Undo, project filtering and app-level machine navigation.

Version values live in `android/app/build.gradle.kts`. The first release is
`0.1.0` (`versionCode = 1`), application ID `com.tajchert.trefoil`, minimum Android 10.
Increment both version values for subsequent published APKs.

## Signing

The release signing setup follows Flocket. All four values are required; without
them the release task produces an unsigned APK, suitable only for build validation.

Create an ignored `android/keystore.properties`:

```properties
storeFile=/absolute/path/to/trefoil-release.jks
storePassword=<store password>
keyAlias=trefoil
keyPassword=<key password>
```

Values are read from this file first, then matching environment variables or
Gradle properties: `TREFOIL_KEYSTORE_FILE`, `TREFOIL_KEYSTORE_PASSWORD`,
`TREFOIL_KEY_ALIAS`, `TREFOIL_KEY_PASSWORD`.

Signing material must never be committed or attached to a GitHub release.
Back up the keystore and passwords in your password manager: future updates must
use the same key. Local files created for the first release use owner-only permissions.

Certificate subject: `CN=Trefoil Release, O=Tajchert, C=PL`.
Verified certificate SHA-256:

```text
a058e08d99619a222472500a2b7661b59e47e6e36b656dc3990e58e9dc570a02
```

The first keystore is RSA-4096 in JKS format, alias `trefoil`, with separate
store and key passwords. The local keystore is outside the repository at
`~/keystores/trefoil-release.jks`. The generated private password-manager record
is `~/keystores/trefoil-release-credentials.txt`; it is not part of this repository.

## Verify, build and publish

```sh
cd android
./gradlew :core:protocol:test :core:session:test :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
"$ANDROID_HOME/build-tools/36.1.0/apksigner" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

Install and smoke-test the signed APK on a test device before publishing. A debug
installation uses a different certificate; remove it from the test emulator before
installing the release APK. This clears only that installation's local app data.

Publish a versioned APK and its SHA-256 checksum from the verified commit:

```sh
git push origin main
git tag v0.1.0
git push origin v0.1.0
gh release create v0.1.0 trefoil-0.1.0.apk trefoil-0.1.0.apk.sha256 --repo tajchert/trefoil --verify-tag --title 'Trefoil 0.1.0' --notes-file release-notes.md
```

Adapt the version and asset paths for future releases. Only upload the public APK
and checksum; signing credentials stay outside Git.

Release `0.2.0` (`versionCode = 2`) adds SSH onboarding. Verify `:core:model:test` and `:core:ssh:test` as well when releasing SSH
changes. Keep the same release keystore for in-place upgrades.

Release `0.3.0` (`versionCode = 4`) adds T3-style thread shelves and a mixed conversation/activity timeline, and includes the retained connection forms and private-LAN pairing fixes from source 0.2.1. Use the same release certificate for upgrades.

Release `0.5.0` (`versionCode = 7`) adds remote Screens and phone fullscreen. Verify the pinned viewer bundle fixtures (`node scripts/verify-screen-bundles.mjs`) and isolated WS fixture tests in addition to Gradle checks. Viewer and Effect licenses/notices ship with the assets.

Release `0.6.0` (`versionCode = 8`) adds file downloads and Android APK installer handoff. Use isolated fixture files; the debug-only `trefoil-file-fixture://download` activity copies its own APK to MediaStore without opening a server or vault. Its entry point is absent from release builds. Verify original signing certificate for upgrades.

Release `0.6.1` (`versionCode = 9`) corrects lineage filtering, shelf grouping/sorting and retained row decoration. Include shell fixtures containing hidden child rows when validating counts.

Release `0.6.2` (`versionCode = 10`) fixes SSH forwarding workers spinning after disconnect.
Run `TunnelShutdownRegressionTest` and use the debug-only SSH shutdown fixture for repeated native
connect/close checks against the isolated service. The activity is absent from release builds.

Release `0.6.3` (`versionCode = 11`) stops decoder restart storms during device-stream startup.
Run the synthetic H.264 device fixture and Python wire tests; debug streaming accepts `mode=device`.
The checked-in H.264 asset is generated test-pattern video; its regeneration command is in the
fixture script. It is never bundled into the release APK.

Release `0.7.0` (`versionCode = 12`) creates new conversations with their first message
(`orchestration.launchThread` + `initialMessage`) and adds runtime mode/model switching. Run
`node scripts/validate-t3-wire.mjs` against the disposable server: it checks client thread/command IDs,
commandId resume and typed rejections. For an emulator smoke test, use an isolated T3 server with no
provider CLIs on `PATH` and its `devReplay` models, reached through `adb reverse` on loopback.
