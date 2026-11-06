# SSH connections implementation plan

> **For agentic workers:** Use subagent-driven development. The user requested parallel work; agents own disjoint transport and UI files while the parent integrates.

**Goal:** Connect a native T3 client through a verified persistent SSH tunnel.
**Architecture:** JVM SSHJ transport discovers the remote runtime and forwards a local ephemeral loopback port; Android coordinator pairs/reconnects the existing T3Client and stores profiles in the encrypted vault.
**Tech Stack:** Kotlin, SSHJ, BouncyCastle, coroutines, Compose, kotlinx.serialization.
**Spec:** ../specs/2026-11-06-ssh-connections-design.md (approved by user).

## Global constraints
- Never authenticate before verifying host keys; changed keys fail closed.
- Keep secrets out of saved-instance state, logs, raw errors and Git.
- Only remote loopback forwarding; never replay uncertain mutations.
- Do not install/restart/stop remote T3; verify matching CLI before opening auth state.
- Preserve direct HTTPS pairing and backwards-compatible encrypted profiles.

## Review focus
- Host key change and unknown key abort before auth.
- Stale ephemeral loopback URLs after reconnect.
- Tunnel closure/background races and fresh write gating.
- Remote argument injection and mismatch between CLI/data directory/server.
- Unsaved credentials and cancelled trust/login prompts.

### Task 1: Shared contract and plan (parent)
- [x] Add SSH profile/input/trust models to core:model; extend SavedEnvironment with optional SSH profile.
- [x] Add core:ssh build and app dependency.

### Task 2: SSH transport (parallel agent)
Files: android/core/ssh/**.
- [x] Write failing tests for trust, auth, quoting, discovery and forwarding.
- [x] Implement SSHJ host validation, password/key login, runtime discovery and version checks.
- [x] Forward loopback to remote loopback; issue pairing JSON with bounded command execution.
- [x] Test cancellation, tunnel closure and identity/discovery failures using isolated SSH server.

### Task 3: Onboarding UI (parallel agent)
Files: android/app/src/main/kotlin/com/tajchert/trefoil/ui/**.
- [x] Add SSH form, authentication/key import, advanced paths, explicit save-secrets choice.
- [x] Add host fingerprint trust dialog, saved-profile credential prompt and cancellation.
- [x] Mark SSH environments distinctly; keep direct pairing.

### Task 4: Secure integration (parent)
Files: app ViewModel/MainActivity/security/**, core/model tests.
- [x] Test backwards-compatible saved environment serialization and secret-free persistence policy.
- [x] Persist host pins and SSH profile in encrypted vault; never persist ephemeral forward URL.
- [x] Coordinate tunnel → descriptor → pairing → session; reconnect SSH before T3.
- [x] Close tunnels on background/disconnect; cancel pending trust/auth cleanly.

### Task 5: Review and delivery
- [x] Run SSH/JVM/Android unit tests, lint and APK assembly.
- [x] Exercise isolated end-to-end SSH→T3 flow and emulator onboarding without real user mutations.
- [x] Independent review, fix material findings, update README/protocol docs and commit.

## Implementation rulings

- Runtime metadata may name a LAN/wildcard origin. Treat it as metadata and always forward to remote loopback, validating the descriptor there; do not reject the user's existing bound-all-interfaces server.
- Runtime serverVersion is optional in the schema; use the live descriptor's version when absent and require the chosen CLI to match.
- Android document-picker trips may retain the transient login dialog while the tunnel is paused. Ordinary backgrounding cancels trust/login attempts.
- Target local build version 0.2.0/versionCode2 for upgrade testing; do not publish a GitHub release without a separate request.

## Verification

Final cleanup rebuild: 26 tests pass; Android lint/debug and signed release APK assembly pass. Isolated emulator verification covers password, trust, automatic pairing, conversation access and encrypted private-key login by paste. Independent review findings and scoped fix review are recorded in the implementation status. Final verification completed successfully before commit.
