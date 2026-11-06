# Connection form corrections

User-reported defects: submit destroys the form before result; .pub import accepted;
errors offscreen; trailing username spaces rejected; private-LAN HTTP pairing blocked.

Confirmed root causes: TrefoilApp closes dialogs in submit callbacks, key validation
checks only nonempty/size, errors appear below the scrollable form without reveal,
username validation precedes trim, and both URL policy and Android cleartext config
reject LAN HTTP. User explicitly approved a private-LAN HTTP opt-in.

- [x] Add shared attempt state (kind/phase/message/id), drive stages in ViewModel;
  retain transient form across testing/trust/failure, close only after success.
- [x] Add tested shared form validation: trim username/host, reject public-key and
  malformed key files, validate bounded private-key formats; reveal inline errors.
- [x] Add explicit persisted allowInsecureHttp setting restricted to private IPs;
  decode direct/app link before policy, forbid public HTTP and redirects, add tests.
- [x] Keep pairing/SSH errors visible in their dialog with useful safe descriptions;
  do not surface raw output that may include pairing credentials/private keys.
- [x] Test realistic form progress/failure/retry/trust behavior and validation with
  Compose/Robolectric; run existing protocol/SSH tests, lint and APK builds.
- [x] Emulator-check correction flows; independent review; docs and local commit.

No GitHub release is implied by this bugfix request.

Verification: 42 tests pass, including four Compose regressions, cancellation/timeout cleanup and private-LAN URL policy. Restoring the original immediate-close behavior and public-key validation omission makes the corresponding regression tests fail; restored fixes pass. Android lint/debug/signed release builds pass. Emulator confirmed visible invalid-username/public-key errors, retained form after refused connection, trailing username whitespace accepted, and wrapped private-LAN HTTP opt-in. Independent policy/lifecycle review and scoped cancellation-fix recheck completed.
