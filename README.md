# ☘️ Trefoil

**T3 Code, within reach.** A native Android client built with Kotlin and Jetpack Compose, with selected foundations from [Flocket](https://github.com/tajchert/flocket).

[Download the latest APK](https://github.com/tajchert/trefoil/releases/latest) · [Release notes](https://github.com/tajchert/trefoil/releases) · [Implementation status](docs/IMPLEMENTATION_STATUS.md)

## 📱 What works

- Direct T3 pairing and encrypted credentials for saved environments.
- Projects, threads and native conversations with streaming updates.
- Text messages, queueing during active runs and interrupting a run.
- Supported live approvals and questions, plus new conversations with server-provided models.
- Phone and wide layouts, light/dark themes, retained drafts and explicit reconnect.

**Early release:** attachments, rich Markdown, diffs, shell terminals, notifications, T3 Connect relay and iOS are still planned. Some provider requests require another T3 client. See [current limits and validation](docs/IMPLEMENTATION_STATUS.md).

## Connect

1. Install the [signed release APK](https://github.com/tajchert/trefoil/releases/latest) on Android **10 or newer**.
2. Create a direct pairing link in your T3 environment.
3. In Trefoil, choose **Pair an environment**, paste the link and give it a name.

Use an HTTPS endpoint reachable from your phone with a trusted certificate. Trefoil supports T3 orchestration protocol **V2**, pinned to the [documented contract](protocol/SPEC.md).

Tokens are encrypted with Android Keystore and excluded from backups. Forgetting an environment removes its local credentials; revoke the session in T3 to revoke server access. Remote work continues when Trefoil closes. Uncertain writes are never automatically replayed—check the conversation before retrying.

## 🛠️ Build

Requires Java **21** and Android SDK platform **37.0**. Set `ANDROID_HOME` or `sdk.dir` in the ignored `android/local.properties`.

```sh
cd android
./gradlew :core:protocol:test :core:session:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

For a signed release, configure the ignored `android/keystore.properties`, then run:

```sh
cd android
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

[Signing and release instructions](docs/RELEASING.md) · [Isolated T3 interoperability check](docs/reference/live-wire-validation.md)

Loopback HTTP is allowed for local development only; Android Emulator reaches the host through `10.0.2.2`. Remote endpoints require HTTPS.

## Project

| Path | Purpose |
|---|---|
| `android/core/model` | Native client contract and immutable state |
| `android/core/protocol` | Effect RPC wire handling |
| `android/core/session` | Authorization, subscriptions and commands |
| `android/app` | Compose UI, lifecycle and encrypted credentials |
| `protocol` | Platform-independent contract and fixtures for future iOS |

Application ID: `com.tajchert.trefoil`. Trefoil is an independent client for T3 Code.

Apache-2.0 · [License](LICENSE) · [Notices](NOTICE) · [T3 attribution](licenses/T3-Code-MIT.txt)
