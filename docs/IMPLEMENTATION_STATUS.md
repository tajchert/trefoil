# Android implementation status

Trefoil is an early native Android client, package `com.tajchert.trefoil`.

Implemented in 0.2.0 (0.1.0 predates SSH): verified SSH password/private-key login, existing-server discovery and automatic pairing over a persistent loopback tunnel, encrypted SSH profiles and host pins, optional saved login credentials; direct HTTPS pairing, encrypted saved access tokens and environment
selection, T3 project/thread browsing, full native message projections, updates
via conflated authoritative refresh, text send/queue, interrupt current run,
basic supported live approvals/user questions, server model selection for new
threads, explicit reconnect, foreground session lifecycle and safe uncertain
mutation behavior. Phone and wide layouts use Compose Material 3.

Verification includes JVM protocol/session tests with MockWebServer, Android
credential-envelope and profile persistence tests, Android lint/debug assembly, emulator launch/pairing
form/error presentation, and an [isolated actual T3 wire/auth check](reference/live-wire-validation.md).
The wire check is independent JavaScript; it does not execute the Kotlin client
against the actual server. Conversation mutation UI has not been exercised
against an actual provider run. No developer threads were modified.

Remaining product work: incremental native event reducers (current full refresh
uses more bandwidth), bounded history pagination, rich Markdown, attachments,
diffs/checkpoints, terminals, steer, cloud relay, notifications, automatic reconnect
backoff, access-token renewal and full provider request UX. Unhandled requests
remain visible and direct users to another T3 client. Approval providers without
basic accept/decline options are not handled in this first UI.

Native iOS has not started; protocol fixtures/documentation are platform independent.

SSH scope: existing running production T3 server, protocol V2; no remote installation/service changes. Reconnect recreates the SSH tunnel before using saved T3 tokens. Unsaved logins must be entered again.

SSH validation (2026-11-06): 13 isolated SSH tests cover host trust before auth,
password/encrypted OpenSSH key login, forwarding cleanup and transport loss,
version/protocol/identity checks, shell quoting, pairing without replay, bounded
command timeout and cancellation. One integration test runs the Kotlin T3 client
through real Mina SSH forwarding to fixture OAuth/RPC endpoints.

On the Android 17 emulator, verified fingerprint comparison/acceptance, password
login, automatic pairing, conversation access, unsaved-credential reconnect
prompt, pasted encrypted private-key/passphrase login, picker return preserving
the login form, and forgetting the fixture environment. The test used an ADB
reverse port for emulator-to-host access; no real T3 credentials or threads were
used. Selecting a file in the system picker was not verified; key paste was.

The signed [0.2.0 release](https://github.com/tajchert/trefoil/releases/tag/v0.2.0) uses the original release certificate, supporting in-place upgrades from 0.1.0.

Current source 0.2.1 corrects retained connection forms, public-key-file validation, visible errors/progress, surrounding username whitespace, and private IPv4 LAN HTTP pairing with explicit consent. IPv6 ULA HTTP is not supported; use HTTPS or SSH for IPv6 remote endpoints. Connection setup cancellation/timeout cleanup has regression coverage. The published 0.2.0 predates these corrections.

0.2.1 verification: 42 tests pass (model/policy, protocol/session, SSH, Compose and credential envelope), lintDebug and both APK builds pass. Android emulator checks confirm retained input on connection refusal, visible/revealed validation errors, public-key rejection and the HTTP consent control for app links. HTTP network policy is tested using isolated fixtures and URL guards; no real user pairing token was consumed during testing. Pairing links are short-lived; generate a fresh one when testing a new build.

## 0.3.0

Adds T3 mobile-inspired thread shelves, search, status precedence, selection, relative times and collapsed archive/settlement groups. The mixed conversation feed preserves projected inherited/local order and groups consecutive work by run. User prompts use compact right-aligned bubbles; assistant replies use basic Markdown, fenced code and copy controls. Active/failed work stays visible. Draft acknowledgment and disconnected write gates have fixture coverage.

68 tests pass across model, SSH, protocol, session and app. Native Robolectric light/dark renders verify thread and conversation layouts. The signed release uses the original certificate for in-place upgrades and includes the previously unpublished 0.2.1 connection fixes. Full CommonMark, attachments and rich diff rendering remain future work.

0.3.0 release validation: debug/release lint and APK assembly pass. Verified the signing certificate against the original SHA-256 fingerprint, installed the signed APK on a separate Android 17 tablet emulator and confirmed onboarding launches. No live environment was connected.

## 0.3.1

Fixes project-enrichment snapshots being mistaken for authoritative shell resets, which hid all threads except those receiving subsequent updates. All thread shelves now survive enrichment; authoritative empty snapshots still remove stale rows. Collapsing a shelf hides its selected row too.

Retains list search, shelf expansion and scroll state across conversation navigation. Reconnect/foreground refresh keeps the last environment and conversation visible, read-only until synchronization. Recently viewed projections are cached in memory, scoped by environment and thread; forgetting an environment clears its cache. No transcript cache is written to disk. Reopening the current thread reuses its subscription.

0.3.1 validation: 76 tests pass, including the 30-thread enrichment reproduction, reconnect freshness/write gating, cache isolation/forgetting and navigation retention. Debug/release lint and APK builds pass. The signed APK upgrades and launches over 0.3.0 on the Android 17 tablet emulator using the original certificate.

## 0.4.0

A deeper source comparison with T3's React Native/Expo Android app adds catalog-based provider marks/account badges/history, run-owned live elapsed timers and exact snooze wake timing. Successful turns fold intermediate commentary/tools while keeping terminal answers, plans/notices/requests and failed or active work visible. Work groups respect run/attempt boundaries. Typed command/file/search/reasoning rows show structured details once; command output and stripped file bodies can be loaded with a read-only RPC. Generation, selection, source-owner and revision guards protect cached content. Unchanged stripped snapshots retain hydrated details; a changed item invalidates them.

See [mobile presentation reference](reference/mobile-presentation.md) for source paths, exact rules and remaining gaps. Remote icon downloads, terminal output streaming, rich diff widgets, subagent navigation and full CommonMark remain future work.

0.4.0 validation: 109 tests pass, including provider identity/custom accounts, timing null/fallback semantics, work folds, prominent rows, output/file-detail hydration and retained-state regressions. Debug/release lint and builds pass. Light/dark thread, completed-turn and failed-command renders were inspected. Signed upgrade/launch verified on Android 17 with the original release certificate. No real user threads were modified.

## 0.5.0

Adds Screens for existing per-thread browser tabs and attached iOS/Android device sessions, including host identity. Native Compose owns discovery, controls, errors/reconnect and immersive phone fullscreen; pinned trusted T3 mobile viewer scripts decode pixels and map touch/input. Fullscreen retains the viewer; reconnect refreshes catalog/access and read-only clients recover using fresh read readiness independently of write permission.

Credentials remain ephemeral, URL policy applies before the viewer is constructed, and the native document is served only at an exact random local route. Inputs drop when unready/disconnected and are never replayed. Browser ownership is explicit. WebRTC-only hosts, native multitouch, rich file/download dialogs and unsupported HID text paste remain outside this release. See [screen protocol](../protocol/SCREENS.md) and viewer asset provenance.

0.5.0 validation: 136 Gradle tests pass; debug/release lint and APK builds pass. Five stdlib fixture-server tests and the actual bundled JS H.264/SEMU, JPEG/ACK, single-touch, Unicode and control-arbitration fixtures pass. A native Android foldable rendered live fixture JPEG frames and delivered a tap; automated Compose tests verify fullscreen retains the viewer. Manual fullscreen walkthrough stopped after the device driver rejected action references. An exact-bootstrap regression now covers the Android-only main-frame routing issue found during that native run. Signed APK certificate matches prior releases.

## 0.6.0

Adds native file cards for assistant absolute file paths and structured message attachments, signed streaming downloads to Downloads/Trefoil, progress/cancel/explicit retry, local Open/Share and APK Install. Artifact-bearing commentary stays visible outside completed-work folds. A fresh-copy control downloads rebuilt files at the same path. Pending partials are removed on cancellation/failure and recovered after crashes; completed files remain accessible offline. Transfer state is in memory; saved files survive process death in Downloads. Uploading files remains future work. See [file protocol and limits](../protocol/FILES.md).

0.6.0 validation: 167 Gradle tests pass, including signed URL confinement/no bearer/no redirects, bounded/truncated/cancelled transfers, stale origin checks before RPC, environment/thread isolation, MediaStore publication/crash cleanup, explicit fresh copies and installer URI grants. Debug/release lint and APK assembly pass. Native phone-width file-card renders were inspected. An Android 17 foldable saved a local debug APK fixture and opened the per-app install-permission Settings screen; full installer confirmation was not exercised. No real user file or thread was accessed. Signing certificate matches previous releases; signed in-place upgrade and launch succeeded on the isolated Android 17 tablet.

## 0.6.1

Corrects thread list membership: subagents, side chats and deleted rows no longer inflate search results or shelf/header counts; forks stay listed. Settlement is explicitly server-owned and precedes pins/working/attention placement. Snooze wake rules, background-held work, parent approval indicators and send/return/settled sorting use the T3 source rules. Removes retained selected-row decoration without clearing conversation/list caches. The shell explicitly requests all child and older settled rows.

0.6.1 validation: 183 tests pass, plus debug/release lint and APK builds. Fixtures reproduce 3 open, 2 working, 1 snoozed and 25 settled rows alongside 56 hidden children, then retain every row across enrichment/live updates. Selection/back navigation and hidden-child search regressions pass. The signed APK upgraded and launched on the isolated Android 17 tablet with the original certificate. Two independent source reviews informed the changes. No real threads were modified. The coding session's attached server exposes a different live settled count from the reported desktop 25; that exact live discrepancy remains unverified, so no history-loss cause is claimed. Native still lacks T3's two-second working-exit grace.

## 0.6.2

Fixes an SSH shutdown worker leak that could accumulate a saturated worker on each disconnect,
including app backgrounding. SSHJ's accept loop retried a socket Trefoil had already closed;
interruptible cancellation now stops it before socket cleanup. Shutdown is idempotent and guarded
against startup races. Failed screen-access attempts also stop retrying on ordinary thread refreshes;
paused coordinators cannot start new catalog/access work.

188 tests, debug/release lint and builds pass. A baseline isolated test reproduced one-core saturation;
twelve native Android SSH cycles now leave zero forwarding loops, with near-idle sampled background
CPU. Signing and upgrade/launch verified. See [cause and validation limits](reference/background-cpu-validation.md).

## 0.6.3

Stops repeated device decoder recovery before first paint. The native viewer already stopped
device recovery after streaming; it now also recognizes the pinned decoder-restart signal during
startup and closes the attempt immediately. Late callbacks cannot revive it; arbitrary JS error
details stay out of UI/logs. Adds a synthetic H.264/SEMU device fixture and repeated lifecycle tests.

190 Gradle tests, nine Python fixtures, bundle checks, both lint variants and APK builds pass.
Native codec failure and background/screen-off cleanup were exercised: sockets close and app CPU
returns idle. Signed upgrade/launch verified. Successful native H.264 playback endurance and
physical-phone thermals remain unverified; see the CPU validation reference for exact evidence.

## 0.7.0

Fixes sending messages: `message.dispatch` omitted the required `createdBy`/`creationSource`, so T3
rejected sends. New conversations now follow T3 mobile. The dialog has no title and picks project, model
and runtime mode, with full-row targets. It opens a local draft, and the first send calls `launchThread`
with `initialMessage`, `generateTitle` and draft-scoped client thread/command IDs. An uncertain launch
keeps the draft and reconciles by thread ID after reconnect. An explicit resend reuses the IDs, so the
server resumes instead of duplicating. Composer chips switch mode (`thread.runtime-mode.set`) and model
(`thread.model-selection.set`). Modes are filtered by provider support and default to the server's
`defaultRuntimeMode`. Provider switching is locked once a thread has started, and model options are
kept. Definite T3 rejections show an error without dropping the session; uncertain outcomes still
disable writes. Request timeouts and cancelled connects now surface messages instead of a silent
Disconnected state. Sends to preparing threads queue.

244 Gradle tests, the disposable-server wire check, debug/release lint and both APK builds pass. The
signed APK upgraded 0.6.3 in place on the isolated Android 17 tablet with the original certificate. Against
an isolated T3 server (devReplay provider, no real providers or user data), the app created a Supervised
thread from its first message, approved a request, switched mode, reconnected and sent a follow-up,
staying connected throughout. The user's exact "Disconnected without error" banner wasn't reproduced
directly; the likely silent paths are fixed. Plan mode, worktree launch, attachments and persisted drafts
across process death remain follow-ups.
