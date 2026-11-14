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
background probing of disconnected environments.
