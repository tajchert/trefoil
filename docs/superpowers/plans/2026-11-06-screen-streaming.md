# T3 browser and device screens in Trefoil

Reference thread: <t3-thread://v2/2df2a6ce-833d-4d9f-988c-8d64f0565d43/ee098a67-7266-4f5a-ade1-51598054ddab>. Source baseline T3 `46449b5379`.

Use T3's bundled mobile stream viewers in a hardened Android WebView, as its React Native mobile app does. This preserves JPEG/H.264/MJPEG decoding, coordinate mapping and remote input protocol without reimplementing codecs. Native Compose owns discovery, lifecycle, controls and immersive phone fullscreen. Keep the viewer mounted when toggling fullscreen.

1. Core owner implements fixture-tested screen catalog (thread browser tabs and attached device sessions with hostId), ephemeral media tickets and shared URL-policy validation. Discovery is read-only; never start remote tools or devices automatically. Preserve cached thread state and scope failures by environment/selection/generation.
2. Viewer owner vendors exact generated T3 scripts with attribution/provenance; provides secure document/message parsing, narrow native bridge, endpoint-origin CSP and blocked navigation/storage/logging. Stop stream/input on background/dispose/disconnect. Inputs are immediate and never queued/replayed. Only short-lived media tickets enter trusted JS.
3. Parent adds Screens navigation, cached session picker, loading/errors/refresh, device navigation and supported keyboard controls, browser control takeover/history/reload/text, fullscreen with exit affordance and transient system bars. Rotations may reconnect but never replay touches.
4. Verify isolated RPC/ticket fixtures, URL policy, HTML escaping/CSP/navigation, lifecycle/input readiness and fullscreen node retention. Inspect native phone layouts. Validate source JS for binary frames/touch/controller behavior with isolated browser fixtures where feasible; no real user conversation mutation or remote server changes.
5. Run all prescribed Gradle checks plus signed build, review integration and document supported transports and limits. Publish a verified upgrade through the existing release process when complete.

No changes to T3 checkout or server management. No durable tunnel ports/tickets, credentials in logs, arbitrary WebView navigation, SSL bypasses, or automatic input retries. Device platform support follows installed WebView codec capabilities; unsupported transports must show an actionable error. iOS arbitrary text paste and native multitouch are not implied by the source's single-pointer viewer.
