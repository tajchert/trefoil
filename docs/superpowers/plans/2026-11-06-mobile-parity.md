# Native parity with T3 Code mobile

User-selected reference: `/Users/mtajchert/coding/priv/t3code/apps/mobile` (React Native/Expo Android), backed by contracts and shared client-runtime rules. Keep native Compose and port the behavior; embedding RN would discard existing native security/session work.

1. Read provider identity/history, timer, work-log and successful-run folding source. Record concrete rules in docs/reference/mobile-presentation.md.
2. Core owner extends backward-compatible metadata and fixture tests: provider catalog and instance history, run-owned timestamps/IDs, run status/attempts, typed tool details.
3. Parent ports local vector provider marks, instance badges/history stack, live work timers (visible foreground only), waiting labels and accurate snooze wake ticks. Use catalog identity; never infer provider from model names.
4. Conversation owner ports conservative successful-run folds, attempt boundaries and typed command/search/file/reasoning work rows. Preserve terminal answers, active/failure/request visibility, projected chronology, retention and write gates.
5. Verify fixtures including nondefault provider instance IDs, exact timing semantics, completed/active/failed folds and command exit results. Inspect light/dark native renders, full prescribed Gradle checks, integrated review.

Constraints: no user thread mutations, logs/transcript disk persistence, security/session policy changes, or new UI dependencies. Preserve T3 MIT attribution. Existing retained-state regression tests must pass. Remote output expansion/subagent navigation requires separate verified contracts and is not implied by a display row.
