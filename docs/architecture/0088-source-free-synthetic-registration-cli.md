# ADR0088: source-free local synthetic registration command

Status: implemented and locally qualified across the three exact database engines.

Requirements: FR-003/008/011/012; SEC-001/003/006/009/012;
REL-001/002/004; OPS-002/004. Partial evidence only.

## Context

[ADR0086](0086-durable-agentcore-registration-composition.md) composes the real
verifier, enrolled ingress, replay store and registration repository. An operator
needs a source-free command with an explicit offline synthetic trust profile and
bounded process lifecycle. This module starts at frozen PR120
`f655db740f90d008f2d8237e0a3fe40baa82bc37`; producer runtime and migrations stay
unchanged. Human-merged main is observed separately, without integration.

## Decision

Add only `check-config` and `register-once` in `agentcore-registration-cli`.
The application archive contains normal Java runtime libraries and a Java 21
launcher. A Java 8 bytecode bootstrap checks the actual VM's specification version
before loading the Java 21 implementation. It requires no source checkout, Git,
Gradle, Node, pnpm, compiler or test classes at runtime.

The trusted local operator starts the private process. A bounded anonymous stdin
pipe carries a separate, closed operator JSON frame, then (for registration) a
bearer frame and closed untrusted request frame. EOF is mandatory before any
database access. Anonymous pipe provenance is enforced on supported platforms
(Linux `/proc/self/fd/0` symlink target matching `pipe:[\d+]` in `pipefs`; Darwin
`/dev/fd/0` in `devfs` with zero link count, epoch birthtime and no vnode pathname
rewrite) and fails closed on unverified platforms or when provenance cannot be
established. File type (`S_IFIFO`), link count or path absence alone are not
accepted as proof: filesystem-named FIFOs (including opened FIFOs whose path has
been unlinked) are rejected with `INPUT_INVALID`. Duplicate keys, unknown fields,
invalid UTF8, trailing JSON, truncation, oversized lengths and extra frames fail
before effects. Regular-file, FIFO and terminal input are refused. Configuration,
token, request and credentials never come from argv, environment variables or
credential files.

The operator explicitly pins the public RSA key, key ID, algorithm, issuer,
audience, provider claim/scope policy, exact workload enrollment, strict existing
GitHub profile, dedicated stores, replay capacity and DML budget. The internally
constructed concrete verifier has an offline key source. The original producer
owns verification, canonical/hash recipes, replay, request-key idempotency and
registration. The command supplies no signing key, verification override,
repository callback or fallback store. A pin is a local synthetic trust decision,
not live provider enrollment or proof of human delegation.

Database frames admit only exact engine identifiers, numeric `127.0.0.1`, bounded
port, restricted catalog/user identifiers and bounded password. The command
constructs driver URLs and fixed connection/socket/lock options internally.
Arbitrary URLs/properties, hostnames, alternate IPs, multi-hosts, socket factories
and remote options are absent. Exact server versions are checked on connection.
Both stores must already be initialized, dedicated and distinct. Runtime never
initializes, migrates, repairs or broadens privileges.

`check-config` parses and validates the complete offline configuration, public
key, enrollment/profile and driver settings without creating a datasource or
calling a verifier, JDBC, DNS, network, signing, replay or registration operation.
Tests guard DNS/network calls and JDBC, and use an actual installed TCP sentinel.

`register-once` constructs the real `DurableAgentCoreRegistration` factory and
invokes it exactly once. A receipt has closed version/profile/outcome fields,
nullable `created`/`operationId`, and constant `executionEnabled=false`. Receipt
decoding strictly requires textual JSON AST nodes for `schemaVersion`, `profile`,
and `outcome`, refusing numeric coercion (such as JSON numeric `1.0`) and non-string
types as `UNKNOWN`. Identity and conflict refusals are `REJECTED`; uncertain
replay/storage failures are conservatively `UNKNOWN`, including producer replay
refusals whose public code does not distinguish replay from commit uncertainty.
Initialization is `NOT_READY`. All failures are nonzero. There is no retry or
acknowledgement reconstruction through database readback. Replay and registration
remain separate transactions.

## Process and diagnostic boundary

The supervisor owns one worker immediately after process creation. The worker
inherits the anonymous stdin pipe directly; the supervisor never blocks on
operator input. Bounded daemon readers capture at most 1,024 bytes on each worker
output channel. The supervisor requires a complete closed receipt, matching exit
status, empty stderr and completed worker exit/readers before acknowledging it.
Worker driver logging is disabled before loading JDBC; raw diagnostics never
become public output. Stderr pressure still causes an unknown result.

The fixed command budget is 60 seconds including setup and receipt send, with a
separate 5 second cleanup reserve. Worker supervision gets the remaining budget
minus a 1 second output reserve. Pipe writes/reads/flush/close execute only on
bounded supervised or daemon paths. Cleanup operates against one monotonic
cleanup deadline: initial and arriving interrupts are collected and preserved,
forced worker termination is positively awaited and observed within the remaining
cleanup reserve, and owned pipe closer and output drainer threads are joined.
Unobserved exit or interrupt during cleanup reports `UNKNOWN`. It never kills
unrelated processes. Shutdown handles ordinary termination; final `halt` avoids
unbounded JVM stream/shutdown-hook flushing.

These are cooperative JVM/OS scheduling bounds, not a hard real-time guarantee.
A stalled kernel, process creation before Java returns an owned handle, host
suspend, root/same-UID interference, SIGKILL, or a driver/OS that cannot terminate
can exceed or defeat cleanup. The operator must keep the host awake. Numeric
loopback does not attest database ownership against a malicious local user.

## Evidence and scope

The [runbook](../runbooks/source-free-synthetic-registration-cli.md) maps installed
three-engine races/restarts/negative controls, real JDBC commit-reply loss,
worker death, offline guards, positive stalls and cleanup to these requirements.
Test signing keys and credentials are generated only in memory. The installed
inventory and SHA256 identify the tested artifact; they are not signed provenance.

The command has no listener, provider discovery, token minting, human/OBO,
approval, admission, permit, sender, target credentials, GitHub transport or
execution path. Synthetic success does not qualify AgentCore/Okta compatibility,
production tenancy, restoration/failover fencing, revocation or a live target.

Rollback removes this independent distribution/module while preserving existing
durable records for explicit operator review. No migration reversal is required.
