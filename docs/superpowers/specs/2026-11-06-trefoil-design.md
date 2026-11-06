# Trefoil native T3 Code client

Status: native approach approved by the user on 2026-11-06; initial Android implementation delivered; see `docs/IMPLEMENTATION_STATUS.md` for verified scope and gaps.

## Intent

Trefoil is a native mobile client for T3 Code. Android comes first, with a native iOS client later. Android uses Kotlin and Jetpack Compose and the application ID `com.tajchert.trefoil`. The repository remote is `git@github.com:tajchert/trefoil.git`.

T3 projects, persistent conversation threads, runs, tool activity and pending requests are the product model. Shell terminals are a separate feature. Closing Trefoil must never stop remote work.

## Approaches

1. **Selected Flocket reuse (selected):** adapt its Gradle setup, Material/Compose theme and useful storage patterns, while writing T3-specific models, transport and conversation features. This retains native foundations without carrying herdr assumptions into the new app.
2. **Full Flocket fork:** initially retains more working screens and infrastructure, but most protocol, SSH, terminal and data modules must subsequently be removed or replaced.
3. **Extend T3's React Native app:** shares the existing TypeScript runtime, but does not satisfy the requested native Kotlin Android direction. Use its implementation as a behavioral reference instead.

The user selected the recommended native approach.

## First useful Android release

- Add an environment using its direct HTTPS endpoint and pairing credential/link; verify the environment descriptor before connecting. Store credentials using Android Keystore-backed encryption; exclude secrets from backups and logs.
- Browse projects and threads, including run and attention state. Keep environment identity attached to every remote ID.
- Read a native conversation timeline, including messages, visible tool activity and pending requests. Support incremental streaming and loading older history.
- Send text, start a thread in an existing project, interrupt a run, and respond to supported approval and user-input requests. Respect server permissions and validate required inputs.
- Show connection errors, reconnect explicitly or with bounded backoff, and resubscribe from authoritative state. Disable writes against stale data. Preserve drafts across transient connection failures.
- Provide accessible light/dark layouts for phones and wider screens, with clear empty, loading, disconnected and error states.

The first release is a coherent vertical slice, not a claim of full T3 mobile parity. Follow-on work includes attachments, rich diffs/checkpoints, integrated shell terminals, notifications, cloud relay discovery and native iOS. Unsupported requests must be clearly presented with guidance to use another T3 client; they must never silently disappear.

## Android architecture

Use a self-contained `android/` Gradle project. Start with focused modules: `core:model`, `core:protocol`, `core:session`, `core:security`, `core:designsystem`, and `app` with feature packages. Introduce additional feature modules when actual boundaries warrant them. Keep model/protocol/session code Kotlin/JVM where practical and Android storage/UI at the platform edge.

The protocol module owns tolerant JSON decoding, Effect RPC envelopes, request correlation and command encoding. The session layer owns authorization, WebSocket lifecycle, subscriptions and state projection. UI consumes immutable state through coroutine flows and ViewModels, never raw sockets. No herdr commands, SSH host setup or terminal-as-conversation abstraction is carried over.

This is a builder-selected initial structure. Sharing wire fixtures and documented semantics with future Swift code is preferable to introducing Kotlin Multiplatform without an explicit need.

## T3 integration and safety

Implement against the actual local `../t3code` contracts and runtime (inspected commit `46449b5379a0089f4ef3ce85bce10a1d28beb4cb`), recording the source commit in protocol documentation and fixtures.

- Discover `/.well-known/t3/environment` and require a supported orchestration protocol (currently version 2).
- Exchange a direct bootstrap credential through form-encoded `/oauth/token`; use the supported bearer flow initially. Do not treat arbitrary pairing URLs as trusted destinations or forward credentials across origins.
- Request a ticket through `/api/auth/websocket-ticket/rpc`; fall back to the legacy bare route only on the documented 404 case. Connect to `/ws` using `wsTicket` and `orchestrationProtocol=2`.
- Implement Effect RPC Request/Chunk/Exit/Ack/Interrupt and heartbeat behavior as required by the pinned runtime, including stream teardown and bounded pending requests. This is not JSON-RPC 2.0.
- Use shell and selected-thread projections/subscriptions rather than scraping rendered output. Scope subscriptions to the foreground UI and prevent old connection callbacks from changing a new session.
- Initially omit optional compact IDs, deduplicated bodies, compact turn items and bounded snapshots. Consume full projections, updating messages and turn items by ID. Obtain provider/model selections from server configuration rather than hard-coding catalogs.
- Encode the current V2 commands (`message.dispatch`, `run.interrupt`, `thread.stop`, `runtime-request.respond`) and use `orchestration.launchThread` for creation. Expose steer/queue only when the current run and permissions permit them.
- Allocate a stable command ID before a mutation. Never blindly resend after disconnect: show an uncertain outcome and reconcile receipts/state according to the contract. Ordinary reconnection resubscribes reads.
- Preserve unknown/additive fields where useful and tolerate them in decoding. Reject incompatible protocol versions with an actionable message.
- Require TLS for remote connections; any development-only HTTP exception must be explicit and limited. Never disable certificate validation.

### Builder defaults confirmed by design review

- Idle send uses `start_immediately`; sending during an active run defaults to `queue_after_active`. Steer is a separate explicit action with the target run ID.
- Initial stop control means **Interrupt run**. A broader stop of the thread and delegated tasks requires a separately labelled action.
- Discover provider/model choices from the server; use an approval-requiring runtime mode for new threads unless explicitly selected otherwise. Let the launch API select the workspace using its supported defaults.
- Prove full projections first, then add older-history loading. Request the complete shell snapshot initially; add `getOlderSettledThreads` if adopting bounded shell lists later.
- On expired/revoked tokens, disable writes, preserve drafts and request pairing again when renewal is unavailable. Discard the bootstrap credential after successful exchange; never save it as a reusable access token.

A parallel builder review found no further technical decisions that require user input.

## Reuse and licensing

Flocket source baseline inspected: `afbd8315809c6f9c7e209692ea0b18493eadb13c`. Preserve Apache-2.0 attribution and notices for adapted code. Retain T3's MIT notice for any copied code or fixtures requiring attribution. Do not copy local credentials, signing files, build outputs, caches, Git history or Flocket's product-specific agent rules.

## Verification and delivery

Before expanding the UI, prove discovery → credential exchange → ticket → Effect RPC subscription against an isolated local T3 server and deterministic protocol fixtures. Never send test prompts or approvals to the developer's real threads.

Test wire parsing, stream cancellation, malformed responses, authentication failures, snapshot/event ordering, reconnect behavior and uncertain mutation outcomes. Verify Android unit tests, lint and debug APK assembly. Exercise the main connection and conversation flows on an available emulator through the device tools, if available. Report any checks that could not run.

Use small Conventional Commits. Document setup, supported functionality and known gaps in README and maintain protocol fixtures outside Android for future iOS. Pushing and publishing are separate from local implementation unless requested.
