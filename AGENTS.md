# Trefoil

Native T3 Code mobile client. Android uses Kotlin/Compose (`android/`, application ID `com.tajchert.trefoil`); iOS is future work. Protocol contracts are pinned in `protocol/`; core modules are under `android/core/`, UI and app code under `android/app/`.

## Setup and checks

Use Java 21 and Gradle 9.8. When `android/local.properties` is absent, set `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` for this checkout. Run Gradle from `android/`.

Relevant existing checks:

```sh
./gradlew :core:model:test :core:ssh:test :core:protocol:test :core:session:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Screenshot tests: `./gradlew :app:testDebugScreenshotTestDefaultTestSuite` validates, `:app:updateDebugScreenshotTestDefaultTestSuite` re-records; narrow either with `--tests '*AreaScreenshotsKt'` and commit changed reference PNGs. See `docs/SCREENSHOT_TESTING.md`.

Baseline profile: `:baselineprofile` generates `app/src/release/generated/baselineProfiles/` with `./gradlew :app:generateBaselineProfile` on one running API 33+ or rooted emulator; see `docs/BASELINE_PROFILE.md`.

Instrumented tests (`app/src/androidTest`, currently the HTML render WebView) need a running emulator: `./gradlew :app:connectedDebugAndroidTest`.

Diff parser and review-order tests live in `:core:model:test`, PR and diff session tests in `:core:session:test`, and diff, Changes and PR-panel screenshots in `*DiffScreenshotsKt`, `*ChangesScreenshotsKt`, `*PullRequestScreenshotsKt`.

Focused thread image coverage belongs in `:core:model:test`, `:core:session:test`, and `:app:testDebugUnitTest`; use fixtures or an isolated T3 instance, never real user threads. This repository has no configured formatter or code generator. Follow neighboring Kotlin formatting and run `git diff --check`. Debug builds do not normally run R8.

## Agent workflow

- Run Gradle from `android/` and use paths relative to it (`app/build/...`, never `android/app/...`).
- `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` (also `sdk.dir` in `android/local.properties`); fresh worktrees need `android/local.properties` and `android/keystore.properties` (listed in `.worktreeinclude`) or Gradle fails with "SDK location not found".
- Release means `scripts/release.sh [--check] --notes FILE <version>`: bump, gate, apksigner check, commit, tag, push, `gh release`. Use `--check` for a dry run. Write the `docs/RELEASING.md` paragraph first (clean tree required).
- Emulators: export `ADB_MDNS=0 ADB_MDNS_OPENSCREEN=0` if the adb server keeps restarting; dismiss the crash-report consent dialog (it hangs the UI); stop any emulator you booted; never `adb kill-server`.

## Product and safety constraints

- T3 projects, threads, and conversations are the product model; terminals are separate. Tolerate additive fields.
- Never replay mutations after disconnect; reconcile unknown outcomes. Disable writes while disconnected or stale, and isolate failures by environment.
- Never log tokens, pairing credentials, or conversation content. Persist tokens encrypted.
- Verify SSH host keys before authentication; changed keys fail closed. Never store ephemeral tunnel ports as durable endpoints.
- Never install, restart, update, or stop a remote T3 server during onboarding.
- Direct private-LAN HTTP requires explicit saved consent and literal RFC1918 IP validation. Public HTTP stays blocked; never bypass shared URL policy.
- Preserve Flocket Apache-2.0 and T3 MIT attribution on adapted code.
- Use Conventional Commits and verify relevant checks before committing.
