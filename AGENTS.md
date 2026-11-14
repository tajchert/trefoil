# Trefoil

Native T3 Code mobile client. Android uses Kotlin/Compose (`android/`, application ID `com.tajchert.trefoil`); iOS is future work. Protocol contracts are pinned in `protocol/`; core modules are under `android/core/`, UI and app code under `android/app/`.

## Setup and checks

Use Java 21 and Gradle 9.8. When `android/local.properties` is absent, set `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` for this checkout. Run Gradle from `android/`.

Relevant existing checks:

```sh
./gradlew :core:model:test :core:ssh:test :core:protocol:test :core:session:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Screenshot tests: `./gradlew :app:testDebugScreenshotTestDefaultTestSuite` validates, `:app:updateDebugScreenshotTestDefaultTestSuite` re-records; narrow either with `--tests '*AreaScreenshotsKt'` and commit changed reference PNGs. See `docs/SCREENSHOT_TESTING.md`.

Focused thread image coverage belongs in `:core:model:test`, `:core:session:test`, and `:app:testDebugUnitTest`; use fixtures or an isolated T3 instance, never real user threads. This repository has no configured formatter or code generator. Follow neighboring Kotlin formatting and run `git diff --check`. Debug builds do not normally run R8.

## Product and safety constraints

- T3 projects, threads, and conversations are the product model; terminals are separate. Tolerate additive fields.
- Never replay mutations after disconnect; reconcile unknown outcomes. Disable writes while disconnected or stale, and isolate failures by environment.
- Never log tokens, pairing credentials, or conversation content. Persist tokens encrypted.
- Verify SSH host keys before authentication; changed keys fail closed. Never store ephemeral tunnel ports as durable endpoints.
- Never install, restart, update, or stop a remote T3 server during onboarding.
- Direct private-LAN HTTP requires explicit saved consent and literal RFC1918 IP validation. Public HTTP stays blocked; never bypass shared URL policy.
- Preserve Flocket Apache-2.0 and T3 MIT attribution on adapted code.
- Use Conventional Commits and verify relevant checks before committing.
