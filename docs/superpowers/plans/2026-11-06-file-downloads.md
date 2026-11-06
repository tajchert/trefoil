# Agent file downloads and APK installation

Use T3's existing signed asset API for remote host files and structured attachments. Native file cards expose download/progress/cancel/retry, then Open/Share and Install for APKs. Android's installer performs package validation and user confirmation; no silent installation or arbitrary file execution.

1. Core owner studies T3 file mentions/asset routes, adds backwards-compatible attachment/reference mapping and conservative path extraction, and streams signed downloads to a caller-owned sink with limits, cancellation, scoped freshness and no redirects/credential leakage. Never shell/SSH-cat files or bypass T3's file authorization.
2. Android owner writes pending MediaStore Downloads/Trefoil rows, publishes only complete files, deletes partial rows on failure/cancel/scope loss, and builds content-URI open/share/installer/settings intents with temporary read grants. Completed files survive lifecycle changes.
3. Parent adds scoped inline file cards to messages, preserves artifact-bearing assistant messages outside successful work folds, wires ViewModel state/actions and Android permission handoff. Downloads require an active readable environment; completed local actions work offline. No auto-download or auto-install from agent text.
4. Verify user-shaped plain/backticked/Markdown APK paths, structured attachments, signed URL validation/redirect rejection/truncation/stale scope, pending-row cleanup, filename collision/traversal, installer/settings permissions, and UI download/install actions. Use fixtures, not the user's real file or threads.
5. Run full prescribed checks and signed build, inspect native fixture renders, review integration, and ship through the established release process with limitations documented.

Keep URLs/tokens ephemeral and out of logs/MediaStore metadata. Filenames are sanitized, not used as local paths. Use system download storage (Android10+), no broad external-storage permission. REQUEST_INSTALL_PACKAGES only supports an explicit user-chosen APK action; the system remains the installer.
