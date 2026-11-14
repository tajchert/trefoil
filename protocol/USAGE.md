# Usage and provider limits

Pinned to T3 Code `46449b5379a0089f4ef3ce85bce10a1d28beb4cb`, usage contract v6:
`packages/contracts/src/{usage,providerUsageLimits}.ts`. Adapted parsers retain T3 MIT attribution.

`server.getUsageSummary` requires `diagnostics:read`. Session `permissions` are
authoritative, falling back to legacy `scopes` only when absent. Trefoil sends
`{sinceDay, untilDay, timeZone, provisional:true}` for a fast initial read, then
`{sinceDay, untilDay, timeZone, awaitRefresh:true}` when a source is refreshing.
The latter has a 120-second timeout. Windows include today and the preceding
0, 6 or 29 calendar days in the requested IANA time zone.

Buckets aggregate cost, cache savings and token totals by provider, provider/model
and day. Input includes uncached, cached and cache-creation tokens; reasoning is
already included in output. Missing days receive zeros. Unknown literals and fields
are tolerated; malformed array elements are skipped. Prices remain estimates and
unpriced record counts and pricing status are exposed to the UI.

The memory-only summary cache lasts 60 seconds per environment, range, time zone
and until-day and serves provisional reads only; `awaitRefresh` reads (explicit
Refresh, provisional follow-up) always ask the server. Changing environments clears the cache.

`server.getConfig` providers contain `usageLimits`, including checked time, windows
and unavailable notices. `server.refreshProviders {}` requires `orchestration:read`
and returns `{providers:[...]}`. It replaces provider limits only after a valid
reply. Concurrent refreshes share one request; automatic refreshes are skipped for
five minutes after the last finished attempt per environment. Manual refreshes
bypass the throttle but join an existing request.

These are reads: timeout, rejection, interrupted response or parse failure throws
`UsageException` without disconnecting or disabling writes. Replies from an old
connection generation are discarded. No mutation is replayed. Source projections
retain only provider, status and message, never fingerprint host IDs or paths;
those identifiers and usage payloads must not be displayed or logged.

Out of scope: credit redemption, cliproxy usage-limit sources, pooled accounts,
model filters, price overrides, hourly charts, per-thread context usage and
general background probing of disconnected environments.

## Home screen widgets

Widgets persist one last-known snapshot for the most recently connected machine in
`noBackupFilesDir/usage-widget.v1.json`: machine name and environment ID, enabled
providers and their limits, Today/Week/Month usage reports, timestamps and a generic
refresh error. Switching machines replaces it; forgetting that machine deletes it.
Disconnecting retains it for stale display. Credentials remain in the encrypted
environment vault; widget snapshots contain no credentials, endpoints or conversation
content. Source messages and provider error messages are omitted from persistence.

While widgets are placed, the connected app reads provisional reports and automatically
refreshes limits at most once per ten minutes per environment. A unique WorkManager
periodic job (KEEP, network required) runs approximately every 30 minutes; Android may
defer it. Receivers schedule it on first placement, app startup restores it when widgets
exist, and removing the last widget cancels it. The refresh button enqueues unique
one-time work (KEEP, network required), bypassing the ten-minute snapshot age check.
Both worker modes skip network if the app holds a live connection to that machine;
periodic work also skips snapshots younger than ten minutes. Every run re-renders widgets.

Background reads use saved direct connections, or complete saved SSH profiles with a
pinned host key and saved authentication. They never prompt, change trust, pair, or
install/start/stop a server. Changed SSH keys fail closed. T3Client enforces the same
HTTPS/private-LAN consent policy as foreground connections. Failed reads retain old
data with a generic error and never disconnect the app. No usage payloads are logged.
