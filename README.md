# ☘️ Trefoil

**T3 Code, within reach.** A native Android client built with Kotlin and Jetpack Compose, with selected foundations from [Flocket](https://github.com/tajchert/flocket).

[Download the latest APK](https://github.com/tajchert/trefoil/releases/latest) · [Release notes](https://github.com/tajchert/trefoil/releases) · [Implementation status](docs/IMPLEMENTATION_STATUS.md)

## 📱 What works

- SSH connections with password or private-key login, verified host fingerprints and automatic pairing through an encrypted tunnel.
- Direct HTTPS pairing and encrypted credentials for saved environments.
- Searchable T3-style thread shelves with provider/account icons, live work timers, and pinned, working, snoozed, settled and archived groups.
- Ordered conversations with successful-turn folds, typed command/file/search activity, on-demand output/details, basic Markdown and copy controls.
- Text messages, queueing during active runs and interrupting a run.
- Supported live approvals and questions, plus new conversations with server-provided models.
- Browser and attached-device pixel streams with touch/input controls and immersive phone fullscreen.
- Phone and wide layouts, light/dark themes, retained drafts and explicit reconnect.

Agent file links and attachments can be downloaded to **Downloads/Trefoil**, opened or shared. APK cards also open Android's installer. Download again after rebuilding a file at the same path.

**Early release:** uploading attachments, full Markdown support, diffs, shell terminals, notifications, T3 Connect relay and iOS are still planned. Some provider requests require another T3 client. See [current limits and validation](docs/IMPLEMENTATION_STATUS.md).

## Connect

**SSH (0.2.0+):** choose **Connect over SSH**, enter the host/IP, SSH username and password or private key, then verify the server fingerprint. Trefoil discovers the installed running T3 server, obtains its pairing credential and carries HTTP/WebSocket traffic inside SSH. No public HTTPS endpoint or Tailscale is needed. Saving login credentials is optional; without it, reconnect asks for them again. Advanced settings support a custom T3 data directory and matching executable.

The server must already be installed and running; Trefoil does not install, update or restart it. Changed SSH host keys block authentication.

**Direct pairing:**

1. Install the [signed release APK](https://github.com/tajchert/trefoil/releases/latest) on Android **10 or newer**.
2. Create a direct pairing link in your T3 environment.
3. In Trefoil, choose **Pair an environment**, paste the link and give it a name.

HTTPS endpoints must be reachable from your phone with a trusted certificate. Version 0.3.0 also accepts HTTP on literal private IPv4 LAN addresses after you explicitly enable **Allow unencrypted HTTP on this private network**. That choice is saved per environment; public internet HTTP is blocked. Trefoil supports T3 orchestration protocol **V2**, pinned to the [documented contract](protocol/SPEC.md).

Tokens are encrypted with Android Keystore and excluded from backups. Forgetting an environment removes its local credentials; revoke the session in T3 to revoke server access. Remote work continues when Trefoil closes. Uncertain writes are never automatically replayed—check the conversation before retrying.

## Remote screens

Choose a conversation, then tap **Screen sessions** in the toolbar. Open one of its existing browser tabs or attached devices. Tap **Enter full screen** to hide app/system chrome; the floating exit button restores it without restarting the stream. Browser controls include explicit takeover/release, back/forward/reload and text; devices provide supported home/back/app-switcher/rotation/text controls.

Media stays on your validated T3 connection, including SSH. Reconnect gets a new ticket; gestures are never replayed. This release supports the standard HTTP/WebSocket JPEG/H.264/MJPEG paths. WebRTC-only hosts, native multitouch and arbitrary iOS text paste are not supported. Update Android System WebView for codec support. [Screen protocol and validation](protocol/SCREENS.md).

## 🛠️ Build

Requires Java **21** and Android SDK platform **37.0**. Set `ANDROID_HOME` or `sdk.dir` in the ignored `android/local.properties`.

```sh
cd android
./gradlew :core:model:test :core:ssh:test :core:protocol:test :core:session:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

For a signed release, configure the ignored `android/keystore.properties`, then run:

```sh
cd android
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

[Signing and release instructions](docs/RELEASING.md) · [Isolated T3 interoperability check](docs/reference/live-wire-validation.md)

Loopback HTTP is allowed for local development only; Android Emulator reaches the host through `10.0.2.2`. Direct public endpoints require HTTPS. Private IPv4 LAN HTTP requires explicit consent; SSH carries loopback HTTP inside its encrypted tunnel.

Connection forms in 0.3.0 retain input while testing and after failures, show progress and reveal validation errors. Public-key files are rejected; use the matching private key without `.pub`. Surrounding spaces in hostnames/usernames are trimmed.

## Project

| Path | Purpose |
|---|---|
| `android/core/model` | Native client contract and immutable state |
| `android/core/protocol` | Effect RPC wire handling |
| `android/core/ssh` | Verified SSH login, discovery, commands and loopback forwarding |
| `android/core/session` | Authorization, subscriptions and commands |
| `android/app` | Compose UI, lifecycle and encrypted credentials |
| `protocol` | Platform-independent contract and fixtures for future iOS |

Application ID: `com.tajchert.trefoil`. Trefoil is an independent client for T3 Code.

Apache-2.0 · [License](LICENSE) · [Notices](NOTICE) · [T3 attribution](licenses/T3-Code-MIT.txt)
