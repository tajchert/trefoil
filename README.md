# Trefoil

Native Android client for T3 Code, built with Kotlin and Jetpack Compose.
Application ID: `com.tajchert.trefoil`. Native iOS is planned.

Trefoil uses T3's projects, durable conversation threads and runtime requests.
It connects directly to a T3 environment over HTTPS and Effect RPC WebSockets.
The app reuses selected Android foundations from [Flocket](https://github.com/tajchert/flocket).

## Development

Install Java 21 and Android SDK platform 37.0. Set `ANDROID_HOME`, or create
`android/local.properties` with `sdk.dir=/your/android/sdk`.

```sh
cd android
./gradlew :core:protocol:test :core:session:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is `android/app/build/outputs/apk/debug/app-debug.apk`.

## Connecting

Create a direct pairing link in your T3 environment. In Trefoil, select
**Pair an environment**, paste the link and give it a name. Use an HTTPS
endpoint reachable from your phone with a trusted certificate. Pairing credentials
are exchanged for access tokens; saved access tokens are encrypted using Android
Keystore and excluded from backups. Forgetting an environment removes its local
credentials; revoke its session in T3 to revoke server access too.

For isolated development only, loopback HTTP is permitted. Android Emulator can
reach the development host through `10.0.2.2`. Production connections require HTTPS.
Never paste a real pairing credential into an issue, log or test fixture.

## Scope

The first Android implementation covers direct pairing, saved environments,
projects/threads, native conversation rendering, streaming updates, text messages,
interrupting runs, runtime approvals/questions and reconnect. It uses T3 V2 full
projections initially. Verify supported details and constraints in [protocol](protocol/SPEC.md).

This is an early client, with no claim of feature parity. Attachments, rich Markdown,
diffs/checkpoints, shell terminals, notifications, T3 Connect relay and native iOS
are follow-on work. Remote work continues when the app closes. Failed writes are
never automatically replayed; inspect remote state before retrying an uncertain action.

For validation evidence and precise limits, see [implementation status](docs/IMPLEMENTATION_STATUS.md).

## Project

- `android/core/model`: shared native client contract and immutable UI data.
- `android/core/protocol`: Effect RPC wire handling.
- `android/core/session`: direct authorization, subscriptions and commands.
- `android/app`: Compose UI, lifecycle and encrypted local credentials.
- `protocol`: pinned T3 integration documentation and fixtures.
- [Design](docs/superpowers/specs/2026-11-06-trefoil-design.md) and [implementation plan](docs/superpowers/plans/2026-11-06-trefoil.md).

Apache-2.0; see [LICENSE](LICENSE), [NOTICE](NOTICE) and [T3 MIT attribution](licenses/T3-Code-MIT.txt).
