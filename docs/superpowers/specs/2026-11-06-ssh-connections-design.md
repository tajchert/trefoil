# SSH connections for Trefoil

Status: persistent SSH tunnel approach approved and implemented; see `docs/IMPLEMENTATION_STATUS.md` for validation and limits.

## Intent

Connect Trefoil to an SSH-installed T3 Code environment using an IP/hostname,
SSH username and password or private key. Pairing and the subsequent HTTP/RPC
traffic travel inside SSH, so the phone needs neither a public HTTPS endpoint
nor Tailscale. Keep the existing direct HTTPS pairing option.

## Approach

1. **Persistent SSH tunnel (recommended):** reuse Flocket's SSHJ authentication,
host verification and crypto patterns, then forward phone loopback to remote
T3 loopback. Pair through this tunnel and retain it while the client is connected.
2. **SSH only for pairing:** obtains a token but still requires a reachable HTTPS
endpoint. Does not solve the user's current Mac mini setup; not recommended.

The user selected the persistent SSH tunnel approach.

## User flow

Add environment offers **Connect over SSH** and **Pair with link**. The SSH form
asks for display name, address, username and authentication, with port 22 by
default. Password and OpenSSH private-key authentication are supported; private
keys may have a passphrase. Secrets stay out of Android saved instance state.

On first connection, show the server's SSH key type and SHA-256 fingerprint.
Only after confirmation reconnect and authenticate. Pin the key by hostname and
SSH port. A changed key is a hard stop, with no credential submission and no
one-tap replacement. Make verification possible against the server's own SSH
fingerprint. This confirmation belongs to the product's trust flow.

Locate the installed T3 runtime and its active state, verify its protocol V2
descriptor, create a short-lived credential using the matching runtime's
`auth pairing create --base-dir <T3_HOME> --json`, exchange it through the tunnel, and open the
existing conversation UI. No pairing URL needs to be copied by the user.

## Remote scope and discovery

The first implementation targets an already installed and running T3 server,
including this Mac mini. Read runtime metadata from the selected T3 data
directory (default `~/.t3`), validate the port and environment identity, and
prefer the matching desktop launcher (`~/.t3/bin/t3`) or standalone runtime over a stale `t3` on PATH. Check CLI/server version compatibility before invoking an auth command that can open/migrate its database. Runtime metadata has no executable path or environment ID: use its version to locate `<base>/runtime/versions/<serverVersion>/t3`, or require an explicit matching executable when standard discovery is ambiguous. The live descriptor supplies the environment identity. Advanced fields can
supply a T3 executable/data directory/port where automatic discovery fails.
Do not assume port 3773 or scrape QR/human-readable pairing output. Reject unsupported dev-origin configurations explicitly. Use bounded commands and
validate JSON. Quote every user-supplied remote argument.

Do not silently install, update, restart, stop or migrate a remote T3 server.
If missing/not running/incompatible, show an actionable error. Desktop's full
managed installation/bootstrap mechanism is later work and is not necessary
for the current installed Mac mini.

## Lifecycle and storage

A JVM `core:ssh` module owns authentication, command execution and forwarding;
Android UI and secure storage stay in the app layer. The SSH connection manager
owns both tunnel and T3 session. Bind the forwarded port only on local loopback,
with an ephemeral port. Forward only to the remote loopback T3 endpoint, never
to an arbitrary target received in pairing output. All remote traffic remains
encrypted by SSH; this does not relax the existing remote HTTP restriction.

Save the SSH connection profile, host pin and issued T3 token in the existing
encrypted vault. Saving login secrets is explicit; without it, prompt when a
new SSH session is needed. Never expose raw CLI stdout/stderr, which can contain credentials. Pairing creation is a mutation and is never automatically retried after an ambiguous outcome. Never persist a consumed pairing token or reuse a
saved ephemeral local URL without first opening its owning SSH tunnel. Verify
the descriptor environment ID again after forwarding and on reconnect.

Foreground resume/reconnect recreates SSH, forwarding and RPC subscriptions in
that order. Background/disconnect closes local subscriptions and tunnel without
stopping remote work. Tunnel loss disables writes; mutations are not replayed.
Pending trust/auth prompts cancel cleanly and cannot be applied to another host.

## Verification

Use an isolated SSH test server and fake/disposable T3 service, never the
user's real environment for test mutations. Cover unknown/changed host keys
before authentication, password and encrypted-key login, command quoting,
discovery/protocol failures, loopback-only forwarding, tunnel cancellation,
reconnect without mutation replay, and profile serialization. Run existing JVM
and Android tests, lint, APK assembly and emulator onboarding checks.

Preserve Flocket Apache-2.0 and T3 MIT notices. This feature does not imply
publishing a new release; that can follow verification when requested.

Builder review: implementable; remaining discovery and lifecycle choices are builder defaults. No additional product decisions are required beyond the selected approach.
