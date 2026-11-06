# Android implementation status

Trefoil is an early native Android client, package `com.tajchert.trefoil`.

Implemented: direct HTTPS pairing, encrypted saved access tokens and environment
selection, T3 project/thread browsing, full native message projections, updates
via conflated authoritative refresh, text send/queue, interrupt current run,
basic supported live approvals/user questions, server model selection for new
threads, explicit reconnect, foreground session lifecycle and safe uncertain
mutation behavior. Phone and wide layouts use Compose Material 3.

Verification includes JVM protocol/session tests with MockWebServer, Android
credential-envelope tests, Android lint/debug assembly, emulator launch/pairing
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
