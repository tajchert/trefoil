# File downloads

Pinned to T3 Code `46449b5379a0089f4ef3ce85bce10a1d28beb4cb`:
`packages/contracts/src/assets.ts`, mobile `FilePreviewModal.tsx` and
`lib/attachmentDownload.ts`. Adapted contract/parser portions retain T3 MIT attribution.

The authenticated `assets.createUrl` RPC receives `resource`:

- Host paths: `{ "_tag": "media-file", "threadId": "…", "path": "/absolute/file.apk", "disposition": "attachment" }`.
- Structured attachments: `{ "_tag": "attachment", "attachmentId": "…", "fileName": "…", "mimeType": "…", "disposition": "attachment" }` (`mimeType` optional).

Host files can live outside the thread workspace; authorization and file resolution
belong to the T3 server. Trefoil never reads them through a shell or SSH command.
The originating environment/thread must match atomically before issuing the RPC;
cancellation and generation/selection guards also apply before signed HTTP access.

The response supplies `relativeUrl` and numeric epoch-millisecond `expiresAt`.
Only an exact same-origin `/api/assets/{token}/{filename}` URL is accepted, with no
authority, query, fragment or traversal. The shared environment URL/HTTP-consent
policy applies again. The signed GET sends no bearer token or cookies and refuses
redirects. Transfer limit is 512 MiB, timeout 10 minutes; truncated responses fail.

Assistant prose absolute paths, inline file paths and Markdown file destinations
produce cards after streaming ends. Commands, fenced/indented code, directories,
relative paths and ambiguous expressions are omitted. Structured attachment
metadata is preserved for all message roles. Artifact messages stay outside
successful-work folds. No upload support is implied.

Android streams to a pending MediaStore Downloads/Trefoil item. Failed/cancelled
partials are removed; pending local URIs are journaled for crash cleanup. Completed
files remain on disk. In-app progress/completion state lasts for the ViewModel
lifetime, scoped by environment/thread/reference; after process death the files
remain accessible in the phone's Downloads. A fresh-copy action explicitly fetches
rebuilt artifacts at the same path. Reconnect never resumes or repeats a transfer.

Completed files expose read-granted content-URI Open/Share intents. APK Install
requires Android's per-app unknown-source permission and a separate tap after
returning from Settings, then the normal package installer confirmation. Completion
never opens or installs a file automatically. There is no broad storage permission.
