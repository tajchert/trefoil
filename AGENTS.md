# Trefoil

Native T3 Code mobile client. Android uses Kotlin/Compose and application ID
`com.tajchert.trefoil`; iOS is future work.

- T3 projects/threads/conversations are the product model; terminals are separate.
- Contracts are pinned and documented in `protocol/`. Tolerate additive fields.
- Never replay mutations after disconnect. Reconcile unknown outcomes.
- Never log tokens, pairing credentials or conversation content. Persist tokens encrypted.
- SSH host keys must be verified before authentication; changed keys fail closed. Never store ephemeral tunnel ports as durable endpoints.
- Never install, restart, update or stop a remote T3 server during onboarding.
- Disable writes while disconnected/stale; one environment's failure must not corrupt another.
- Tests must use fixtures or an isolated T3 instance; never modify real user threads.
- Preserve Flocket Apache-2.0 and T3 MIT attribution on adapted code.
- Use Conventional Commits and verify relevant tests before committing.

Build from `android/`: `./gradlew :core:model:test :core:ssh:test :core:protocol:test :core:session:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
