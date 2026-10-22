# ADR 0064: Unsigned, role-scoped approval review export

Status: Implemented backend, console-download and offline-checker slices.

Requirements: FR-007/010/012; SEC-003/005/009/012; DATA-001/003; REL-002.

## Decision

Add a read-only per-request metadata export to the local authenticated simulator
approval service. Reuse the detail route's current enrollment, `approvals.read`,
requester-only ownership and workspace reviewer/viewer visibility. Resolve
authorization and visibility before snapshot lookup. Browser requests reuse the
same-origin session boundary; bearer and cookie authentication cannot be mixed.
No new role, scope, policy exception or MCP execution capability is introduced.

Export only an allowlist of approval metadata and tool identifier. Omit the
canonical action, arguments, target/response data, identity claims, credentials,
source proofs, execution permits, raw evidence and outbox. Pseudonymous
participants, request identifiers and hashes are still potentially sensitive;
authorized readers must control any downloaded copy. This is not anonymization
or a before-persistence redaction solution.

The stored original action hash is checked before producing metadata. The
operational `requestHash` remains unchanged; it cannot be recomputed from this
deliberately incomplete export. Copy fields into a new object, cap the serialized
document at 32,768 UTF-8 bytes, serialize before sending success headers, and
return no raw fallback on failure. Reads never change state, publish evidence,
mint/consume authority or call a target.

## Integrity and consistency

The versioned closed envelope contains a SHA-256 checksum of `payload` using
`tracepermit-canonical-json-v1`. Its fixed schema/kind/profile are separately
validated by consumers. The checksum detects an unchanged payload against that
checksum; an attacker can edit both. There is no signature, trust root, full
event chain, independent anchor or claim of non-repudiation. It grants no approval
or execution authority and must never be accepted as an authorization artifact.

`generatedAt` is export time, not an execution/review event time. Approval,
snapshot and attempt are existing sequential reads, not a shared transactional
snapshot or complete lifecycle history. Concurrent updates may make the view
stale immediately. Expiry is a derived observation; an expired pending row does
not mutate into stored `expired`. Unknown outcomes remain unknown. No request is
replayed to produce or verify a report.

All six assurance flags are explicitly false: signed, completeHistory,
transactionalSnapshot, independentAnchor, authorizesExecution, argumentsIncluded.
Unknown versions/fields must not be silently promoted to stronger assurance.

## Alternatives and limits

Raw action/evidence export was rejected: it broadens privacy exposure and may
misrepresent a redacted payload as the original hash-chain input. Signed receipts
and externally anchored complete streams require a separate reviewed contract.
Bulk export, retention/deletion, durable export auditing, real targets and public
ingress are not added. Existing database-admin and in-flight revocation limits
remain. The browser download has a separate acceptance gate that exercises the
real Java route, saves the file and requires the installed offline checker to
accept its closed structure and checksum. Neither consumer strengthens this
record into authority.
