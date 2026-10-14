# ADR 0026: Derive sequence risk server-side and re-authorize sanitized actions

- **Status:** Accepted for the simulator-only POC
- **Date:** 2026-10-11
- **Requirements:** FR-001, FR-005, FR-006, FR-008, FR-009, FR-010,
  SEC-001, SEC-002, SEC-005, SEC-006, SEC-012, REL-001, REL-009,
  PERF-001, PERF-002, OPS-004

## Context

A tool name alone does not fully describe risk. A log-read call can be benign
with a result limit of 20, excessive with a limit of 500, or suspicious when it
is the third individually valid read in a short interval. Neither a client
label such as `safe` nor model prose is trustworthy evidence for those facts.

The policy contract already supports `sanitize`, but executing the original
request after returning that outcome would violate complete mediation. Editing
the original event in place would also destroy the evidence needed to explain
what was proposed and would let a permit bind the wrong action.

## Decision

The Java enforcement service owns a bounded recent-action store. Before policy
evaluation it derives sequence context from tenant, authenticated subject, and
target system. The request body cannot provide or override that context. The
POC keeps at most 1,024 keys, 32 observations per key, and a one-minute window.
A production deployment requires shared tenant-partitioned state.

**Scenario 9 addendum (2026-10-12):** The exact registered synthetic customer
read and external-send tools also use a separate, bounded tenant + authenticated
subject key, without target system, so the gateway can correlate CRM and
egress proposals. Only policy-allowed (or successfully re-authorized
sanitized) proposals are recorded; a denied read cannot create a false
exfiltration chain. Recording precedes permit issue and dispatch, so a later
failure of the read may conservatively leave an authorized-proposal signal.
This is not a claim of committed-data provenance. The local single-process
store remains bounded to 1,024 such principal keys and the same one-minute
window; production needs shared durable state and explicit effect-versus-
attempt semantics.

The deterministic classifier keeps its existing portable CRUD signals and
adds a separate semantic-intent projection. Tool-specific argument rules add
operational policy signals such as `OVERSIZED_RESULT_WINDOW`. Gateway-owned
history can add `RAPID_REPEATED_READ_SEQUENCE` or
`READ_THEN_DESTRUCTIVE_SEQUENCE`. A separate exact-tool rule can add
`SYNTHETIC_PII_READ_THEN_EXTERNAL_SEND` across targets. Policy rules may
require these operational signals.

`sanitize` is a non-executable intermediate decision:

1. validate and preserve the original canonical event;
2. select an allowlisted tool-specific transformer;
3. create a new event with a new deterministic event ID and changed action
   hash;
4. independently recompute the reviewed transformation and reject an unknown
   transformer or any change outside that exact output;
5. preserve identity, lineage, target, and authority boundaries;
6. classify and evaluate the derived event again using the same server-owned
   sequence context;
7. issue a permit only when the derived event receives an explicit `allow`;
8. bind permit and idempotency state to the derived event, never the original.

Missing, failing, or non-converging transformation remains not executed. A
sequence-store error fails closed before permit issue and target dispatch.
Evidence records both hashes, the transformer ID, a bounded change label, and
the final decision; it omits raw arguments and results.

## Consequences

- An isolated log read requesting 101–1,000 results is reduced to 100 and then
  independently re-authorized before reaching the simulator.
- The third rapid log read by the same tenant subject toward AWS is blocked,
  even though each request is independently authenticated and schema-valid.
- The React console can explain initial versus effective decisions without
  gaining approval or execution authority.
- Existing policy vectors remain valid because Java keeps operational CRUD
  signals separate from the semantic-intent projection.
- The deterministic fast path remains cheap and auditable; a secondary LLM is
  neither required nor trusted for inline authorization.
- A cross-system synthetic read-then-send receives an exfiltration-specific
  denial, while the baseline external send remains denied by its own rule.

## Rejected alternatives

- **Trust client-supplied intent or sequence fields:** the caller can forge
  them and suppress risk.
- **Modify the original event in place:** this loses the proposal/effect
  distinction and breaks request-bound evidence.
- **Treat `sanitize` as implicit allow:** no decision would authorize the
  transformed action actually sent to the target.
- **Issue a permit before transformation:** the permit hash would bind a
  different action.
- **Use an inline LLM for every decision:** it adds latency, nondeterminism,
  prompt-injection exposure, and availability dependency to the fast path.
- **Fail open when recent-action state is unavailable:** an attacker could
  induce an outage precisely to bypass sequence controls.

## Limitations

Only one reviewed transformer and a small deterministic semantic vocabulary
are implemented. The recent-action store is process-local, so it is neither
restart-safe nor horizontally consistent. The POC benchmark is local and
single-process; it is useful qualification evidence, not a production SLO or
capacity claim.
