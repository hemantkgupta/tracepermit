# ADR0093: source-free closed operation lifecycle command

- Status: implemented candidate; fresh three-engine and aggregate qualification passed
- Date: 2026-10-27
- Requirements: FR-003/008/011/012; SEC-001/003/006/009/012;
  REL-001/002/004; OPS-002/004. Partial evidence only; no whole-ID promotion.

## Context

The opt-in V7 operation stack has separate, qualified Java libraries for current
authority, evaluation/review, permit issuance, single-use admission, durable
synthetic delivery and read-only reconciliation. Those libraries deliberately
have no executable or default route. The existing source-free registration
commands stop before human review and execution authority. An operator therefore
cannot exercise the complete closed lifecycle from an installed archive without
writing a new composition and potentially reopening its trust seams.

The missing slice must preserve the central invariant: a simulated side effect
is evaluated and authorized before invocation. It must not accept authenticated,
human, delegated, approved or verified state from command JSON; expose a permit;
add a generic repository/signer/target override; or turn an uncertain outcome
into retry authority.

## Decision

Add the separate Java 21 application distribution
`agentcore-operation-lifecycle-cli`, launched as
`tracepermit-synthetic-operation-lifecycle`. A Java 8-bytecode bootstrap gives a
fixed diagnostic before Java 21 classes load. The parent supervises exactly one
closed worker and clears its environment. Parent and worker independently require
stdin to be an inherited anonymous pipe.

The command set is exactly `check-config`, `inspect`, `evaluate`, `review`,
`deliver-synthetic` and `reconcile`. Input consists of big-endian length-prefixed
frames followed by EOF. Configuration is always first; non-check commands add a
bearer and command frame, while delivery places one Ed25519 PKCS8 private-key
frame between them. Maximum frame sizes are 65,536, 16,384, 128 and 1,024 bytes.
EOF, strict UTF-8, duplicate-key rejection and closed schemas are established
before configuration parsing, authentication, JDBC, signing or target activity.

The operator configuration has one exact profile,
`tracepermit-local-synthetic-operation-lifecycle-v1`. It pins numeric-loopback
V7 JDBC settings; operation scope; exact GitHub draft-PR target, policy and
artifact hashes; current authority generation; permit public key and lifetimes;
two offline RS256 OIDC providers; exact requester enrollment; and an independent
human-reviewer roster. There is no discovery, JWKS fetch, default, environment
overlay or request-selected authority. A narrow facade reuses the existing
installed JDBC parser without exposing its package-private representation.

Verified requester and reviewer tokens are independently authenticated. The
reviewer roster is operator-owned, requires a human entry, is fingerprinted into
current authority and caps membership at the earlier of token expiry and five
minutes after verification. The requester cannot become the reviewer. Delivery
accepts a private key only through the private pipe, parses canonical Ed25519
PKCS8 and proves it matches the configured public key before permit issuance.
Raw bearers, private keys and permits never enter receipts or evidence.

Reviewer authentication also rejects the exact issuer-qualified subject enrolled
as requester, independently of client ID or requester principal alias. The
existing principal-ID inequality check remains. Existing scope/principal IDs
are not normalized or migrated. Distinct issuers with the same raw `sub` remain
distinct; this does not establish whether different accounts belong to one
person, refresh offline membership, or invalidate already recorded approvals.

The worker composes only the actual V7 stores:

1. `inspect` verifies fresh owner execute scope and reads current authority,
   current lifecycle state and whether `dispatch_started` exists.
2. `evaluate` verifies fresh evaluator identity and commits the actual closed
   evaluation transaction.
3. `review` verifies a separate human reviewer plus current roster membership
   and commits approve or deny.
4. `deliver-synthetic` verifies the owner and pinned signing key, loads the
   approved operation, issues a permit, atomically admits it, claims delivery,
   acknowledges durable `dispatch_started`, invokes only the fixed synthetic
   target and records success, rejection or unknown.
5. `reconcile` reauthenticates the owner and performs read-only durable
   reconciliation. It cannot issue a permit, admit, claim, invoke or retry.

The new `JdbcGitHubOperationInspector` validates an existing V7 store, requires
the current authority profile inside a read-committed writer transaction, replays
the current operation and delivery journal, rolls back and returns defensive
copies. It has no migration, repair, listener or target capability.

## Process, receipt and uncertainty rules

The parent allows at most 60 seconds total, reserves five seconds for owned
cleanup and bounds its final receipt write to one second. The worker emits one
strict JSON receipt no larger than 2,048 bytes and empty stderr. Receipts expose
only command/outcome, operation lifecycle status, dispatch-started observation,
the configured/current profile fingerprints, `executionEnabled:false` and
`automaticRetryAllowed:false`. Exit and receipt must agree.

Known configuration/readiness faults are fixed `NOT_READY` or `REJECTED` results.
Commit, storage or delivery-result uncertainty maps to `UNKNOWN`. Once admission
or `dispatch_started` may be durable, neither the supervisor nor any command
automatically retries, restarts, reconstructs authority or resends a target.
Reconciliation reports state only. A successful synthetic target result does not
enable execution generally or certify a live downstream effect.

## Consequences and limits

The source-free archive can exercise the exact closed lifecycle across process
boundaries without source checkout, listener, request-derived trust or a live
target. It deliberately remains an operator-run local synthetic composition.
Numeric loopback is not process isolation. JDBC query timeouts are not total
pool/commit/cleanup deadlines. Database failover/restore fencing, instantaneous
revocation, public/TLS ingress, live provider compatibility, credential custody,
real GitHub/AWS/Jira/AgentCore execution and production load are not qualified.

The [qualification runbook](../runbooks/source-free-operation-lifecycle.md)
records exact commands, engines, evidence and remaining gates. The
[learning note](../learning/synthetic-lifecycle-success-is-not-live-execution.md)
explains why a successful synthetic delivery receipt remains execution-disabled.

## Producer format compatibility update — validation pending

A module-only build source set finalizes fresh lifecycle TAR output with
standard USTAR name/prefix fields, preserving complete paths, payloads and
entry metadata before distTar completes. It is excluded from installed JARs.
The runtime/bootstrap/protocol/authority and all deadlines remain unchanged.
Unsupported input fails without truncation, verifier broadening or old-digest
substitution. This preserves the existing trust decisions and adds no adapter.

The [manual compatibility runbook](../runbooks/lifecycle-archive-compatibility.md)
defines producer/installed cases, raw/final inventory comparison, an unchanged
public TAR-parser comparator and unchanged repository/coverage gates. All new
stages are UNRUN; earlier receipts do not qualify the new producer/archive.
