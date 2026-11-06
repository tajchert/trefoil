# T3-aligned thread presentation

Reference: local T3 Code mobile threadListV2 and ThreadFeed, plus pinned protocol contracts.

Use native Compose, existing dependencies, and existing security/session boundaries.
The chosen approach ports mobile shelves and conversation hierarchy rather than a desktop sidebar or generic chat cards.

1. Research upstream ordering, status precedence, grouping and spacing. Preserve T3 MIT attribution.
2. Extend optional model/session presentation metadata with fixtures: timestamps, archive/pin/settlement/order fields, runtime status and message/activity chronology. Keep additive compatibility.
3. Build compact searchable Pinned / Threads / Working / Settled shelves, project subtitles, status indicators, relative time, selection and collapsed archive. Match supported upstream ordering deterministically.
4. Build conversation in separate Compose files: right-aligned user bubbles, open assistant markdown, expandable consecutive work activity, pending requests, live state, near-bottom scroll and jump to latest. Preserve draft confirmation and disconnected mutation gates.
5. Verify ordering and interaction fixtures, all prescribed Gradle checks, and visual fixture screenshots. Review integrated diff and fix findings. No real user conversation mutations.

Owners: model/session agent; conversation UI agent; parent thread list/integration. Research agent supplies source-grounded behavior. No new network behavior or remote server management.
