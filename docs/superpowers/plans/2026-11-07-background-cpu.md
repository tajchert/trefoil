# Background CPU runaway

Prioritize the reported six-core sustained load over the remaining live thread-count comparison.
Inspect worker loops/dependencies and media lifecycle; reproduce using isolated SSH/T3 fixtures.

Confirmed cause: SSHJ 0.41.2 LocalPortForwarder.listen exits on thread interruption, not a closed
socket. Trefoil closed the socket before SSHJ close could interrupt the worker, leaving a tight
SocketException loop. Coroutine cancellation alone did not interrupt blocking Java code. One
baseline regression consumed 205 ms thread CPU over a 200 ms interval.

Use runInterruptible for forwarding; cancel before closing sockets. Make cleanup idempotent
and synchronize listener publication/active state with shutdown. Do not call SSHJ close against
its retained pooled-worker reference after the forwarding task has returned. Cover repeated,
concurrent and early cleanup, and verify all coroutine children terminate.

Separately prevent failed screen-access attempts from retrying on ordinary thread freshness
transitions/foreground. Paused coordinators must not start access/catalog work. Preserve explicit
manual retry and successful-view resume with fresh credentials.

Run full tests/lint/build, independently review, exercise twelve connect-close cycles on Android,
sample idle/background CPU, verify signing/upgrade and ship a patch. Never exercise real user
threads, log credentials or change the production T3 server. Retain 192.168.10.199 as the target
for the still-pending live Settled-count comparison.
