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
