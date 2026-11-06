# Background SSH CPU regression

The reported release incident involved multiple saturated DefaultDispatcher workers continuing
after backgrounding. The reproduced cause was SSHJ 0.41.2 `LocalPortForwarder.listen`:

- Its loop exits when the worker thread is interrupted.
- Closing the listening socket makes `accept` throw. SSHJ catches the exception and repeats.
- SSHJ's `close` interrupts only while the socket is still open; Trefoil closed it first.
- Cancelling an ordinary coroutine does not interrupt its blocking Java call. Each closed tunnel
  could therefore leave another worker repeatedly allocating exceptions.

The failing isolated regression measured **205 ms thread CPU in a 200 ms interval** and captured
`Throwable.fillInStackTrace → SocketException → ServerSocket.accept → LocalPortForwarder.listen`.
This demonstrates one-core saturation per orphan, consistent with the reported accumulated workers.
It does not substitute for a thread dump from the original physical-phone incident.

The fix runs forwarding with `runInterruptible`, cancels before socket cleanup, synchronizes
publication/activation with close and makes close idempotent. It avoids invoking SSHJ close against
its retained thread reference after that pooled worker may have been reused.

Regression checks:

```sh
cd android
./gradlew :core:ssh:test --tests '*TunnelShutdownRegressionTest' \
  :core:session:test --tests '*ClientIdleRegressionTest' \
  :app:testDebugUnitTest --tests '*ScreenSessionCoordinatorTest'
```

The SSH checks assert completion after normal and concurrent/repeated close across twelve cycles.
Core idle checks perform 25 thread selections, verify one event produces one projection read, then
verify no client coroutine children remain after disconnect. Screen tests prevent failed-access
retries across 100 freshness transitions/foreground and reject new access/catalog work while paused.

Native Android 17 verification used the debug-only `SshShutdownFixtureActivity` and the existing
loopback SSH fixture, with an ADB reverse port. The fixture copies no credentials from the vault,
opens no user environment and verifies its generated host key. All twelve connect/close cycles
completed with **zero live forwarding loops**. After Home, three `top` samples five seconds apart
showed **0.0%, 1.8%, 0.0% CPU**; process CPU time advanced 0.09 seconds over the sampled intervals.
This is a short emulator idle check, not a physical-device thermal endurance measurement.

Actual vendored viewer bundles also passed an isolated stress run: 10,000 frames created one
decoder/socket and no timers. Ten explicit upstream video-session resets created the corresponding
decoder replacements. Stop followed by 10,000 late frames produced no paints and left no decoder
or timer active. No viewer asset changes were needed for this issue.

## Follow-up: emulator streaming startup

The user confirmed the original connection used SSH. Opening emulator streaming could precede
the background/disconnect that exposed the SSH worker leak. A separate native decoder recovery
problem was also reproduced using `scripts/device-stream-fixture.py` and its generated baseline
H.264 test pattern; no real device pixels or T3 threads were accessed.

The Android 17 emulator's `c2.goldfish.h264.decoder` failed buffer submission with CodecException
14 and a memfd SELinux denial. Before first paint the pinned JS kept recreating the decoder:
139 frames and 137 reset requests until the 15-second bootstrap timeout. The same stream and
actual viewer decoded 194 frames with one configuration and no errors in host Chrome, so the
synthetic bitstream was independently validated.

0.6.3 recognizes only the exact pinned-viewer decoder-restart signal and halts the device attempt
even before its first Streaming state. Arbitrary remote error details remain discarded. The
native check stopped after four in-flight frames/resets, closed video/control sockets and remained
idle. One explicit retry made one additional bounded attempt. No viewer bundle changes were made.

A delayed-first-frame run had active video/control sockets when Home was pressed. Backgrounding
and turning the emulator screen off closed both sockets, sent no additional frames, and sampled
0.0% app CPU twice five seconds apart. Twelve repeated pause/fresh-access cycles destroy every
WebView in Compose regression coverage; late error/streaming messages cannot revive a halted view.

190 Gradle tests and nine Python wire-fixture tests pass, along with trusted bundle fixtures,
debug/release lint and builds. Signed upgrade/launch uses the original certificate. The emulator's
decoder failure prevented a successful native H.264 playback endurance run; host decoding and
native failure/background cleanup were tested separately. Physical-phone thermals remain unverified.

Earlier 0.6.2 release validation: 188 tests, debug/release lint and APK builds pass. Original signing
certificate verified; signed upgrade/launch on the isolated Android 17 tablet succeeds. No real user
threads or production T3 services were changed.
