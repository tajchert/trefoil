# Android releases

Release `0.25.0` (`versionCode = 44`) adds turn diffs, pull requests, issue trackers and side chats. The
thread overflow's Changes entry opens a turn picker, a review-ordered file list and a native diff viewer
(docked on wide windows). A header chip opens the pull request panel (overview, checks, files) with merge,
update branch, ready/draft and comment, each sent once and reconciled when the outcome is unknown. When the
server advertises issues, an Issues destination lists and filters issues; detail shows activity, linked PRs
and threads, links or unlinks the current thread and starts a thread from the issue. Side chats and subagents
open read-only in a Thread tab on docked windows, `/side` starts a side chat, and assistant replies can be
quoted with citations. Revisited threads repaint from memory and the first config comes from the slim
`subscribeServerConfig` snapshot. Check Changes, the PR panel (including a merge), issues and a side chat
against a real environment on a device; none of these were exercised on a device against a live T3 server.

Release `0.24.1` (`versionCode = 43`) stops a thread too large for mobile from breaking the connection. A
thread whose snapshot exceeded the 16 MB frame cap used to fail the whole session with "Invalid server RPC
response", and reconnecting reselected the same thread, so the app looped. Now only that thread closes, with a
"too large to open on mobile" notice; an oversized reply to any other request fails just that request. Check
opening a very large thread and then a normal one against a real environment on a device.

Release `0.24.0` (`versionCode = 42`) adds local agent activity notifications: while connected to a
machine with the app in the background, Trefoil posts Android notifications when a thread or one of its
subagents needs an approval or an answer, when a run finishes (after staying completed for 5 seconds) and when
a run fails. There is no push service; alerts come from the app's own live connection, one per thread, and
tapping one opens the thread once the shell is synchronized. The setting (**Notify about agent activity**, on
by default) sits under Keep connected in background. The empty composer shows T3 prompt suggestions, bare URLs
in thread messages are tappable, and system notices render as T3-style warnings. Check approval, input, finish
and failure alerts and a notification tap against a real environment on a device.

Release `0.23.0` (`versionCode = 41`) lays the app out from its window on tablets and foldables: a
navigation rail from medium width, thread list and conversation in two resizable panes, a docked side panel
(Agents, Screens, dev servers), centred dialogs from 600 dp, nothing laid across a separating hinge, a
tabletop screen viewer, and keyboard shortcuts, tooltips and pointer states. The model picker is rewritten as
a native grouped picker: provider tabs, version families with a version tray, search, favourites synced with
T3 server settings, and a two-pane layout on tablets. New threads can start in Fast mode, and a Fast or effort
pick the model lacks is never stored or restored. Check the picker, favourites and a tablet or foldable
layout against a real environment on a device (the adaptive layouts were checked only in screenshot tests).

Release `0.22.0` (`versionCode = 40`) sorts the New thread project picker by recent thread activity,
preselects the most recent project, shows each project's last activity and path, and searches titles and
workspace paths (five recent projects, then Show all). New threads start from T3's default model selection
(with its options, such as reasoning effort) and per-project model and runtime mode overrides, falling back
to the next scope when a selection is unavailable. Each new-thread draft now gets a fresh composer, so a
second draft in the same project no longer sticks on "Waiting for server confirmation". Check the picker
order, the default model and two drafts in a row against a real environment on a device.

Release `0.21.1` (`versionCode = 39`) adds T3 provider marks for Antigravity, Kiro, Muse, fx, Pi and ACP
Registry, shared by the app and the usage widget; the brand-colored marks (Antigravity, Kiro, Muse) render
untinted in the app, as T3 Code does. The New thread FAB is now a round plus button and slides above a
snackbar instead of being covered by it. Check the new provider marks and the FAB with a snackbar on a device.

Release `0.21.0` (`versionCode = 38`) adds stop, steer and queue to the thread composer: while an agent
works, a follow-up can steer the running turn or wait in a queue strip (Send now, edit, remove), and Stop ends
the turn, following T3's queue eligibility rules. The thread list starts from an encrypted per-machine shell
cache at cold start (rows marked not live until connected), the last-used machine connects before route
probing, routes are preflighted together and reconnects resync from the shell cursor. Token renewal, saved
machine writes and browser forward holds got correctness fixes. Check stop, steer and queue, and a cold start
with cached rows, against a real environment on a device.

Release `0.20.0` (`versionCode = 37`) adds dev servers and port forwarding: the app menu and a thread
header chip open a screen of servers listening on the T3 host, which open in an in-app browser or are
forwarded to a loopback port on the phone (T3 Code v0.1.11+, browser tunnels on, `terminal:operate`; see
`protocol/PORTS.md`). Forwards stop on disconnect, reconnect and machine switch. Composer drafts (new-thread
prompts and replies) persist locally and encrypted across restarts, with a Drafts block in the thread list.
Settling the open thread returns to the thread list. Check a forward over a direct and an SSH connection, and
drafts across a process restart, against a real environment on a device.

Release `0.19.0` (`versionCode = 36`) adds pull to refresh to the thread list, and rows whose turn ended
while subagents still run show a Waiting spinner. Reconnects resume the shell from its cursor, so the server
replays missed deltas and skips the full snapshot. Hosts that advertise access-token renewal get device-bound
DPoP tokens, renewed with the device key. Opening a thread records a `thread.visit`. Long threads load earlier
turns on demand. The baseline profile is regenerated. Check pull to refresh, reconnect and DPoP renewal against
a real environment on a device.

Release `0.18.5` (`versionCode = 35`) shows a "Waiting on N subagents" row in the thread screen when the
parent's turn has ended but subagents or awaited background tasks are still running, following T3's
pending background work rules (including a usage-limited run kept as latest). An ended turn no longer shows
"Thinking". Check a live thread with delegated subagents on a device.

Release `0.18.4` (`versionCode = 34`) ships a baseline profile, installed by `androidx.profileinstaller`, so
ART compiles the cold-start path ahead of time instead of interpreting it on the first launches. The
`:baselineprofile` module generates it from a fresh-install journey (startup, Machines screen, pairing dialog)
and also holds a startup macrobenchmark; see `docs/BASELINE_PROFILE.md`. On an API 36 emulator the benchmark
showed no measurable change, so measure cold start on a physical device before claiming a gain.

Release `0.18.3` (`versionCode = 33`) turns on R8 code and resource shrinking for release builds, cutting
the APK from 63 MB to 11 MB (most of the old size was unused Material icon classes and BouncyCastle).
Keep rules in `android/app/proguard-rules.pro` cover the BouncyCastle JCA provider, sshj and the WebView
bridge. The minified build was smoke-tested against the SSH emulator fixture (host key check, password login,
projects, threads). Check SSH, link pairing and an HTML render against a real environment on a device.

Release `0.18.2` (`versionCode = 32`) keeps the live connection when you tap the machine you are already
connected to on the Machines screen (or rail): a session that answered a ping within 20 s is reused, with no
reconnect and the selected thread kept; a different machine still closes the old connection first. The
Machines working badge now counts only threads that are actively running or starting, ignoring idle threads
with past runs, archived threads, threads awaiting approval or input and unknown statuses. Check returning
to the same machine and the badge against a real environment on a device.

Release `0.18.1` (`versionCode = 31`) makes the effort and permission pills icon-only in the New thread
sheet and the thread composer, using the desktop app's icons: lock (Supervised), pen (Auto-accept edits),
shield check (Auto) and crossed-out shield (Full access); menus show the same icons. In a thread the model
pill no longer spells out the effort; a signal-bars pill next to it shows the level and opens the model sheet.
Check mode switching and effort selection against a real environment on a device.

Release `0.18.0` (`versionCode = 30`) adds a reasoning effort picker: the composer model pill opens a
bottom sheet with models grouped by provider, reasoning choices and a Fast switch, and the pill shows the
effort and Fast state. Model option changes are serialized. The New thread sheet collapses the project
picker to a single pill once a project is picked or the prompt gains focus (tap to change), opens collapsed
with one project and handles projects that arrive late. It also gets a reasoning effort pill (signal-bars icon)
next to the model. Check effort selection and starting a new thread
against a real environment on a device.

Release `0.17.2` (`versionCode = 29`) is a review-fix release. HTML render pages now run sandboxed on a
random origin per session, without WebRTC, WebTransport, Cache Storage, IndexedDB or cookies, and stay
script-free if the lockdown script cannot be injected; their subresource fetches refuse non-public literal,
NAT64 and 6to4 hosts and share a 32 MiB memory budget. The render card offers Retry when offline, keeps its
height while scrolling and stays full screen across rotation. The work log flags stale running entries,
bounds long labels and detects large failed tool results; keep-alive reconnects at once on resume. Known
gap: `<link rel="preconnect">` still opens sockets outside the fetcher (see `docs/THREAD_HTML_RENDERS.md`).
Run `:app:connectedDebugAndroidTest` and check an HTML render against a real environment on a device.

Release `0.17.1` (`versionCode = 28`) shows live turns like T3 Code: a ticking "Working for X" row at the
top of the active turn (it becomes the "Worked for X" fold in place), each subagent as one inline row
(avatar, current activity, status and elapsed time, tap to open), and a single "Thinking" row at the bottom,
replacing the separate "Working…" and subagent summary rows. Check a running thread with a subagent against
a real environment on a device.

Release `0.17.0` (`versionCode = 27`) renders thread work like T3 Code: settled turns fold under a muted
"Worked for X" row (tool failures no longer block the fold; failed or interrupted runs stay open), a run's
tool calls and thinking group into flat summary rows ("Ran 1 command and read 2 files") that expand to
individual calls and then their details, group headers never show a failure mark, and failed calls only
tint their own icon. Check a long thread with failed commands against a real environment on a device.

Release `0.16.1` (`versionCode = 26`) fixes inline HTML renders, which all showed "Unable to load": the
WebView reports the page request with its `#t3-theme=` fragment and the document policy compared the URL
exactly. Covered by the instrumented `HtmlRenderPageTest` (`./gradlew :app:connectedDebugAndroidTest`).
Check an HTML render against a real environment on a device.

Release `0.16.0` (`versionCode = 25`) shows pages that agents publish with T3's `html_render` tool
inline in threads (themed, auto-sized, full screen with pinch zoom; subresources fetched app-side, public
https only, see `docs/THREAD_HTML_RENDERS.md`) and adds an opt-in "Keep connected in background"
foreground service. Check an HTML render and the keep-connected notification against a real environment
on a device.

Release `0.15.1` (`versionCode = 24`) fails over fast when the phone changes network (for example
Wi-Fi to cellular): the live connection is pinged on a default-network change and, if it does not
answer within 4 s, reconnects through the route plan at once, so the route badge no longer shows
"Local network" on a dead link. Check a Wi-Fi to cellular switch against a real environment on a device.

Release `0.15.0` (`versionCode = 23`) puts the tightest limit in the thread-list header, renames
the screen to Limits & usage (readable chart, reached-limit state, offline view of last known data)
and drops limits from Machines, restyles all home screen widgets with one chrome and the fixed
Trefoil palette, signals when another thread needs you (back-arrow badge, snackbar, row label), and
adds a Paste button and a `t3code://pair` link handler to pairing. Check the widgets on a launcher
and a `t3code://pair` link from the system camera on a device.

Release `0.14.0` (`versionCode = 22`) adds T3 Connect routes: each machine keeps LAN, Tailscale,
HTTPS, SSH and T3 Connect tunnel addresses learned from the host, connects through them in order
after an identity check, switches to a faster route when one answers, auto-reconnects after an
unexpected drop, and shows routes and LAN HTTP consent per machine. See `docs/T3_CONNECT_TUNNEL.md`.
Check tunnel connect and route switching against a real environment on a device.

Release `0.13.0` (`versionCode = 21`) previews images inside expanded tool rows (local image
reads and structured tool-output images) with full-screen zoom, and keeps hydrated images across
compact snapshots. Check image retrieval against a real environment on a device.

Release `0.12.1` (`versionCode = 20`) adds a distinct launcher picker preview image
for each of the five usage widgets, using illustrative sample data.

Release `0.12.0` (`versionCode = 19`) adds the Usage screen (provider limits, cost and tokens)
and adaptive home screen widgets for limits, cost and tokens, shows working subagents in the
conversation, groups consecutive commands and expands failed ones, and keeps live connections
through a short background grace period. Check widget refresh against a real environment on a device.

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

### Scripted release

`scripts/release.sh [--check] --notes FILE <x.y.z>` runs the steps above in one go. Before it starts, write the
paragraph for the new version in this file and the release notes file, and commit both: the tree must be clean.
`--notes FILE` is required for a real release (the path is resolved before the script changes directory).

The script checks, in order:

- `android/keystore.properties`, the Android SDK, `apksigner` and `gh` are available.
- A real release runs from `main` with `HEAD == origin/main` (after `git fetch origin main --tags`), and the tag
  `vX.Y.Z` exists neither locally nor on `origin`.
- The new version is strictly greater than the current `versionName`, and both `versionName` and `versionCode`
  (current + 1) land in `android/app/build.gradle.kts`.
- The gate passes: `:core:model:test :core:ssh:test :core:protocol:test :core:session:test :app:testDebugUnitTest :app:lintRelease :app:assembleRelease`.
- `apksigner` verifies the APK and its certificate is not the debug one.

It then commits `chore(release): X.Y.Z`, tags, runs `git push --atomic origin main vX.Y.Z` and
`gh release create` with the APK and its SHA-256 file. If anything fails before the release commit exists, the
version bump is reverted automatically. Set `RELEASE_TRAILER` to replace the release commit's default
`Co-Authored-By` trailer.

`--check` is a dry run: it bumps, runs the gate and the signature check, then reverts. It can run on any branch,
does not require `HEAD == origin/main`, and never commits, tags, pushes or publishes. Run it first.

The script does not run `:app:connectedDebugAndroidTest` (instrumented HTML render tests) or screenshot validation
(`:app:testDebugScreenshotTestDefaultTestSuite`). Run both manually before releasing, and smoke-test the signed
APK on a device as described above.

Recovery after a failed publish:

- If the gate or signature check failed, the bump was reverted; fix the cause and re-run.
- If the push failed, first check what reached the remote: `git ls-remote origin refs/heads/main refs/tags/vX.Y.Z`.
  A dropped connection can report a failure after the push was accepted.
  - Both refs point at the release commit: the push landed. Do not roll back; publish with `gh release create`
    as in the next item.
  - Neither moved (the push is atomic, so it is both or neither): delete the local tag (`git tag -d vX.Y.Z`), undo
    the release commit (`git reset --hard HEAD~1`; it only touches `android/app/build.gradle.kts`), fix the cause
    (for example `git pull --rebase`), and run the script again.
- If only `gh release create` failed, `main` and the tag are already pushed. Re-run the exact `gh release create`
  command the script prints; the APK and checksum are in `android/app/build/outputs/apk/release/`.

The manual flow above remains the fallback if the script cannot be used.

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
