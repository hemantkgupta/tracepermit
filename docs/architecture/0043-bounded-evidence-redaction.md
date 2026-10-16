# ADR 0043 — Bounded evidence projection redaction

- Status: Accepted for the Java simulator foundation
- Date: 2026-10-16
- Requirements: SEC-012, DATA-003, FR-007, FR-010

## Context

Approval responses need a reusable way to mask known credential fields without
changing the canonical action used for request hashes, approval, permits or
dispatch. A redacted projection is not a replacement canonical action and its
hash is not the original request hash.

Independent review of the initial library found three failures: hashing a
matched subtree bypassed the configured depth limit; diagnostic text ignored
the configured hash strategy; and standard AWS environment and API-key header
aliases were missing. Synthetic regression probes retain these cases.

## Decision

`io.tracepermit.contracts.redaction` provides immutable rule/profile configuration
and a redactor with invocation-local state. Callers must not mutate an input
tree concurrently. Returned object/array trees are independent, **mutable**
projections; the input remains unchanged.

### Validate before transforming

Every JSON entry point validates the entire input before hashing or recursive
copying, including subtrees matched by MASK or DROP and envelope fields outside
`action.arguments`. Root depth is zero; the configured maximum depth is inclusive
(default 32, supported 1–128). Cycles fail this depth check before copying.

Each call also limits input to 10,000 nodes, 65,536 UTF-16 code units per field
name or text value, and 1,048,576 aggregate field-name/text code units. Diagnostic
text has the same per-value input limit and a 1,048,576-code-unit output limit.
Unsupported non-JSON node types fail closed. These are application bounds, not
a total JVM-memory or regex-time guarantee; custom rules are trusted Java
configuration, not caller-supplied expressions. Limit errors contain no payload
values, paths, or partial redaction output. Callers must not fall back to raw
input if redaction fails.

### Matching and transformations

- Field names match an explicit, case-insensitive alias list. It includes
  `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`,
  `AWS_SECURITY_TOKEN`, `X-Api-Key`, `api-key` and `Proxy-Authorization` in
  addition to the existing password/token/key names. Benign names such as
  `aws_region` and `session_token_count` remain unchanged.
- Paths use exact dot-separated names or simple slash-separated aliases;
  this is not a full escaped JSON Pointer implementation.
- Value patterns detect configured **string** forms of bearer/JWT tokens,
  PEM private keys, card-like numbers and US SSNs. These are heuristics,
  not exhaustive credential/PII detection or validation. Unknown aliases,
  numeric PII, encoded or split secrets need separate handling.
- For structured JSON, the first matching field/path rule transforms the
  whole value. Otherwise the first matching string pattern transforms the
  whole string. MASK emits `[REDACTED:<rule>]`; HASH fingerprints canonical
  JSON; DROP omits a property or emits null for standalone/array values.
- For diagnostic text, leftmost matching spans are transformed; profile
  order breaks ties. MASK emits the marker, HASH fingerprints the matched
  canonical JSON string, and DROP removes that span. Unmatched context is
  retained. Generated markers/hashes are never scanned again. An exact
  whole-text match has the same fingerprint as the structured string.
  Zero-width diagnostic matches fail closed.

`enterpriseDefault()` masks matching fields/patterns; `irreversibleHash()`
hashes them; `strictDrop()` drops credential properties but deliberately masks
the default value patterns. The historical `IRREVERSIBLE_HASH` API name is
retained, but an **unkeyed deterministic SHA-256 fingerprint is not a
confidentiality guarantee**: low-entropy values can be guessed and hashes allow
correlation. Prefer MASK/DROP for secrets. This library provides neither
non-repudiation nor keyed pseudonymization.

### Actual integration boundary

`SimulatorApprovalServer.projection()` applies the default profile to the
response action snapshot. The original action and stored approval binding remain
unchanged. `redactActionArguments()` is an opt-in projection helper: envelope
fields outside the arguments are intentionally not sanitized.

There is no new global logger, telemetry interceptor, audit-export pipeline or
before-persistence filter. In particular, exact pending-action snapshots in
PostgreSQL remain authoritative and are **not** rewritten with redacted values.
Current simulator schemas reject arbitrary credential arguments; this work does
not qualify storage of customer secrets or real-target operations. SEC-012 and
DATA-003 remain partial requirements at repository/deployment scope.

## Verification and consequences

`RedactionBoundaryReviewTest` retains the three independently failing probes.
`EvidenceRedactionBoundsTest` covers inclusive depth limits for all strategies,
cycles, pre-copy envelope validation, node/text budgets, text strategy/overlap
semantics, replacement isolation, credential aliases, benign fields, and source
hash/container independence. `EvidenceRedactorTest` preserves original behavior.

`SimulatorApprovalRedactionBoundaryTest` additionally exercises real loopback
HTTP, signed synthetic identity, browser sessions, MCP structured/text responses
and disposable PostgreSQL. It verifies masked projections, unchanged original
snapshots/bindings, authorization before projection, bounded failure without raw
fallback, strict credential-field rejection and a clean one-effect/replay control.
Canary snapshots are seeded through test-only internal Java calls, not public
credential ingestion. See the [API qualification runbook](../runbooks/approval-response-redaction-qualification.md).

No canonicalization profile, signed claim, database migration, policy rule,
approval authority or dispatch input changes. Existing approval and simulator
regressions remain required. This is bounded projection hardening, not completion
of authenticated history, retention/export, production privacy or compliance.
