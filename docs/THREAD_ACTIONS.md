# Thread actions

Rows and the selected conversation share Settle/Un-settle, Pin/Unpin, Rename,
Snooze/Wake, Copy thread and Copy thread ID. Settlement offers a snackbar Undo.
The selected menu is also available in the wide two-pane layout. Machine controls
live under App navigation. Project filtering combines with search and shelves.
The list belongs to one connected environment, so it has no environment filter.

Mutations capture environment and thread IDs independently of selection. They
require fresh synchronized state and operate permission, are serialized, and are
cancelled across connection generations. An uncertain outcome disables writes
until reconnect reconciles server state; commands are never replayed. Undo is
single use, invalidated by another thread action or connection change, and
restores the captured thread's previous pin placement and unexpired snooze after
un-settling. A failed restoration stops the sequence.

Wire semantics follow the official local T3 sources:
`apps/mobile/src/features/threads/thread-list-v2-items.tsx`,
`apps/mobile/src/features/home/useThreadListActions.ts`,
`packages/contracts/src/orchestrationV2.ts`, and client-runtime
`operations/commands.ts`, `state/threadUndo.ts`, `state/threadSettled.ts`.
Pin/Snooze/Wake follow mobile's loaded-server-config gate; no invented capability
bits are sent. Un-settle and Wake send `reason: "user"`. Rename sends
`thread.metadata.update` with a trimmed nonempty title. Snooze uses a future UTC
instant from presets or the device's local date/time picker, and rejects pending
approvals, child requests, guest messages and queued turn starts. Copy thread
produces the environment-scoped hosted thread URL; Copy thread ID copies the raw ID.

Fixture coverage lives in `ThreadActionsTest`, `RetainedSessionTest`, and
`ThreadActionsUiTest`. No tests mutate real-user threads. Run from `android/`:

```sh
./gradlew :core:model:test :core:session:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

This checkout has no configured formatter or code generator. Debug builds do not
normally run R8; minification was separately exercised with a temporary Gradle
init script enabling debug minification and an isolated application ID suffix.
