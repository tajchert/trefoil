# Isolated SSH validation

Run normal SSH tests from `android/`:

```sh
./gradlew :core:model:test :core:ssh:test :core:session:test
```

Tests use an ephemeral local Apache Mina SSH server and fixture HTTP/WebSocket
endpoints. The SSH fixture key and credentials are deliberately public test data;
they must never be used on a real host. The tests do not connect to the developer's
SSH server, open its T3 database or issue prompts/approvals on real threads.

For opt-in emulator QA, start the fixture in a separate terminal:

```sh
cd android
./gradlew :core:ssh:runEmulatorFixture --args=/tmp/trefoil-ssh-emulator.json
```

The metadata file contains the ephemeral port and public test login. Forward
that host port to the chosen emulator using `adb -s <serial> reverse tcp:<port>
tcp:<port>`, then enter `127.0.0.1`, that port and the test login in Trefoil's SSH
form. Compare the displayed fingerprint with the fixture metadata before trusting.
This fixture simulates a running T3 server and returns deterministic projects and
threads; it does not run real coding agents.

Use the device tools for all UI actions. Stop the fixture with Ctrl-C, remove the
ADB reverse mapping and forget the fixture environment afterward. Fixture runner
classes live under `src/test` and do not ship in the APK. Finish the long-running
fixture Gradle task before running final verification builds.
