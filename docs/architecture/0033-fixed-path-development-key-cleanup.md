# ADR 0033: Fixed-path cleanup for the local demo endpoint key

- **Status:** Accepted for the local POC
- **Date:** 2026-10-15
- **Requirements:** OPS-004, SEC-013

## Context

The one-command demo generates an ephemeral Ed25519 endpoint key. Its shell
trap removes the key on normal shutdown, but abrupt termination of a
package-manager supervisor can bypass that trap. The original detached cleanup
process accepted a temporary directory path from command-line arguments. Even
with a narrow path pattern and ownership checks, that made deletion depend on
caller-controlled path material and produced high-severity scanner findings.

## Decision

The launcher now uses a fixed ignored `.cache/demo/endpoint-key.pk8` path
inside its checkout. It fails if a stale key or session descriptor exists,
rejects symbolic-link cache paths, creates an owner-only directory, and writes
the key with mode `0600`. The detached watcher receives no path or PID
arguments: it observes its operating-system parent PID and derives the key and
session paths from its own source location. It validates directory ownership
and mode, key type/ownership/mode/link count, and records the key's file
identity and SHA-256 digest. After the launcher dies, it removes the key and
session descriptor only if the exact observed key is still present. Normal
shutdown retains an independent shell trap.

Both the local and hosted OAuth launchers handle `SIGINT`, `SIGTERM`, and
`SIGHUP` by exiting successfully through the `EXIT` cleanup trap. Cleanup runs
once and does not return to child-health monitoring. Unexpected child exits
still fail the launcher, and startup validation keeps its nonzero status.
Subprocess tests cover requested stops, actual child exits, and validation
failures in isolated temporary fixtures; real local acceptance additionally
checks listener closure, key removal, restart, and rejection of old credentials.

## Consequences

The local demo is intentionally single-session. A stale key or state file
requires explicit operator inspection and cleanup before restart. The watcher
will not delete a replacement key from a later run. This is development key
hygiene, not hardware-backed endpoint key custody or protection against a
malicious process running as the same operating-system user. The cache remains
Git-ignored; no private-key bytes are logged or committed.
