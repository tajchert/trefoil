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
