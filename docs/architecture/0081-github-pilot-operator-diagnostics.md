# ADR0081: private GitHub pilot operator diagnostics

Status: synthetic implementation; intended macOS operator profile, host validation pending.
Requirements: SEC-001/002/003/007/012/015; REL-001/005/009; OPS-002/004.

## Decision

Add the Java21-only `pilot-operator` module, an explicit finite CLI and a separate
source-free archive. It consumes operator-owned private configuration and the
actual [ADR0076](0076-github-installation-credential-provider.md) credential
configuration plus [ADR0073](0073-github-pilot-target-preflight.md) target binding.
No caller identity, approval, policy authority, execution permit, admission,
ledger, reconciliation or target write is composed.

`--check` reads bounded private regular files, strictly decodes UTF8 and JSON,
validates exact pins and structurally inspects the RSA PKCS#8 key. It never
constructs a credential provider or transport, signs, resolves DNS or performs
network I/O. The JSON object has an exact versioned field set; duplicate keys,
trailing documents, unknown fields, coercion, malformed UTF8 and lone escaped
surrogates fail. Both files are at most16KiB, owner-only0400/0600, single-link,
with a current-user-owned0700 immediate directory. Every path component must be
free of symbolic links. Reads use NOFOLLOW and compare file identity, size,
permissions and modification time after close. Private paths and configuration
are never rendered. This is a local POSIX custody check, not hardware custody
or a proof against the same user/root replacing files or modifying the JVM.

Only explicit `--preflight` constructs the existing concrete provider and fixed
production transport, then invokes the exact existing four-GET verifier. New
composition classes share its Java package solely for package-private access;
there is no reflection, copied verifier, runtime modification, public transport
injection or config/environment test switch. Test seams remain package-private.
Fixed GitHub origin, public DNS pinning, platform TLS, exact one-repository
selection, immutable IDs, exact ref/SHA checks, main-only/draft-only restrictions,
least permissions, byte bounds, no redirect/proxy/retry and existing per-sequence
timeouts remain unchanged. The credential exchange itself includes one token
minting POST; no PR POST, lookup of existing PRs or dispatch API is called.

The complete diagnostic worker has a30-second bound. Timeout/cancellation
cancels its one task; process exit is the final boundary for blocked native I/O. The Bash3-compatible
launcher adds a35-second bound including JVM startup and at most one second of
forced cleanup, then reaps its direct child.
Success is emitted only after target close and a final credential expiry check.
Any credential/target/cleanup failure yields an indeterminate non-authoritative
result without dependency messages, causes, suppressed failures or secrets.
No command retries. A lost response/cancellation can leave an unused minted
installation token until expiry; it does not prove any authoritative absence.

## Distribution and boundaries

The new package follows [ADR0055](0055-source-free-endpoint-connector-distribution.md):
clean-source commit/timestamp manifest, exact JAR hashes, full SHA256SUMS,
launcher, license/notice and installed runbook under one versioned root. Its
independent dependency allowlist does not widen an existing distribution.
Java21, `/bin/bash` and `/bin/sleep` suffice for installed operation, including an empty PATH;
Node/pnpm/Gradle/source/Docker/downloads are unnecessary. The launcher removes
ambient JVM option variables and suppresses raw JVM stderr. No default service,
secret argument, origin/proxy/trust option or token input exists.

Checksums are integrity comparisons, not signatures or current policy proof.
The primary intended operator host is macOS. Current qualification on the cloud
Linux POSIX host is synthetic/source-free; no macOS, Windows, live App/key
custody, token response compatibility, live GitHub TLS/DNS or target capability
is certified. A successful observation is a point-in-time read, not an atomic
compare-and-swap, permission to dispatch, a signed receipt or durable authority.

## Qualification

New tests cover private/symlink/hardlink/oversized files, strict JSON/Unicode,
exact restrictions, a denying DNS SPI with an active negative control and zero
provider construction on valid offline check, source-free JAR execution with
spaces/empty PATH, JVM-option redaction, generated-key concrete credential
exchange, four real HTTP GETs to a numeric-loopback peer, exact permission/ID/
repository/SHA failures, no target POST/retry, cleanup/expiry, cancellation and
deadlines. Final counts, deliberate guard-removal failures and archive/full-gate
receipts belong to the PR handoff; the original provider/transport suites remain
separate evidence and are not relabeled as live tests.
