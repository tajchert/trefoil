# Thread image previews

Expanded tool activity rows can show images referenced by `dynamic_tool.viewedImagePath` and indexed raster images in structured tool output. Previews use the existing inline image presentation and tap-to-zoom viewer. Image bytes stay out of display text.

Remote images use authenticated `assets.createUrl` references and the existing bounded image loader. Local viewed paths are normalized as media-file references. Omitted activity detail is loaded through existing hydration; image references retain their source identity and connection guards. No arbitrary shell-output path inference is intended.

Relevant T3 references: `apps/mobile/src/features/threads/thread-work-log.tsx`, `apps/web/src/components/chat/V2ItemInspector.tsx`, and `packages/shared/src/toolOutput.ts` (image markers and accepted raster MIME shapes). Authenticated asset semantics are documented in [`protocol/FILES.md`](../protocol/FILES.md).

Cover projections, identity/revision changes, omitted-detail hydration, path normalization, ordering/caps, MIME filtering, row expansion and viewer wiring with fixtures. Relevant existing test homes include core model/session tests, `RetainedSessionTest`, `ConversationTimelineTest`, `InlineImageUiTest` and `InlineImageLoaderTest`. Never use real user threads. This documents intended behavior; it does not claim implementation is verified.
