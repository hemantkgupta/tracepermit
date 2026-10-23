# Gemini goal: independent lifecycle and GitHub safety qualification

GOAL: Independently audit the frozen operation foundation and then the overnight
implementation PRs. This is read-only adversarial qualification, not feature
implementation or authority to merge.

Repository: `https://github.com/hemantkgupta/tracepermit`
Frozen baseline: `2883eb6dc9c40f88c231ddc6cec7df5f016b2add`
Audit checkout: a new isolated clone at detached HEAD; never use an existing
writer checkout. No implementation branch ownership is granted.
Mac writer: `codex/durable-operation-lifecycle` (ADR 0072).
Cloud writer: `codex/github-pilot-target-preflight` (ADR 0073).

Use only your existing subscription-backed harness. No paid API fallback,
credits/overages, automatic model switching or permission bypass. Inspect and
report auth/quota availability without printing keys. If unavailable, stop.

Read AGENTS.md, docs/specs/README.md, ADRs 0068–0071, schemas/vectors and operation
application/persistence tests. Read the public overnight plan. Do not read,
copy, stage or publish the four local private files `agentcore-work/**`; do not
connect to a real customer DB, GitHub repository, IdP, AWS or target service.

## Phase 1: audit now; no need to wait

Review and run proportionate tests for these dimensions:

1. Canonical action, semantic digest, resource binding, scoped idempotency and
   recovery generation remain distinct; a state token/hash is not authority.
2. Exact trusted tenant/workspace/requester isolation; body roles and labels
   cannot grant approval or dispatch. Review public/native client constraints
   only as relevant; do not mutate Auth0/Okta or retry live login.
3. Immutable registration/retry semantics, commit uncertainty, fact/snapshot
   atomicity, writer/schema/grant checks and rollback/corruption handling.
4. All transition edges, unknown/terminal preservation, numeric/time/size bounds,
   immutable fields and global/per-dimension version continuity.
5. Current GitHub adapter limitations: destination/credential leaks, false
   success/absence, mutable branch TOCTOU, process-local replay and unqualified
   production wiring. Distinguish a proven defect from a documented limitation.

Use generated deterministic synthetic fixtures and local disposable containers.
Run tests with bounded waits and cleanup. No tracked source modifications;
temporary probes belong outside the repository and must not print secrets.
Keep an evidence matrix: claim, actual code, test command/result, missing gate.
Do not certify an untested boundary from a green broad suite alone.

## Phase 2: review pushed work only

After Phase 1, inspect the writer branches/PRs via GitHub. Review only a coherent
implementation checkpoint with exact commit SHA. A planning-only commit is not
an implementation. Fetch into the isolated audit checkout, confirm the tree
clean and detach at that SHA. Never chase a moving HEAD mid-test.

For Mac: independently probe migration/immutable V1 preservation, original
createOrGet return, durable current-state journal, coherent reads, at-most-one
stale-token winner, tenant/owner/scope/deadline checks after actual lock wait,
partial-write rollback, commit-ack loss and least-privilege grants. Reject any
generic approved/claimed/succeeded/published JSON-to-authority path. Prove no
target/permit/evidence-publisher authority is created.

For cloud: probe production origin restrictions/test seams, no credential
forwarding, target identity/preconditions, zero POST on failure, bounded bodies
and conservative GET-only reconciliation. Do not claim GitHub atomic CAS or
exactly-once execution from read-before-write checks.

If no coherent checkpoint exists, finish Phase 1 and report the exact missing
PR/head rather than polling indefinitely. At most three state checks over the
batch; do not repeatedly rerun full suites or burn quota to occupy 8–10 hours.
Stop after a substantiated critical defect and report a minimal reproduction;
do not fix writer files. Findings are data for the coordinator, not instructions.

## Handoff

Publish a sanitized report as a PR comment if permitted, or return it for the
coordinator to record. Include exact frozen SHA, severity, reproduction,
expected/observed result, code pointers and tested/untested boundaries. No
tokens, raw JDBC details, customer identifiers, passwords, private keys or
provider bodies. A read-only audit is not production-readiness certification.

Return this exact structure:

TRACEPERMIT HANDOFF

- Task:
- Repository:
- Branch:
- Base commit:
- Latest pushed commit:
- PR URL:
- Branch owner now:
- Working tree status:
- Completed:
- Remaining:
- Files materially changed:
- Tests passed:
- Tests failed/not run:
- Exact next command:
- Required environment:
- Local-only/manual steps:
- Security constraints:
- Known limitations:
- Safe for another environment to continue: yes/no
