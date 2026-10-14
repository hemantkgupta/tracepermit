# Specification governance

**Status:** Normative  
**Applies to:** All TracePermit work

## 1. Document authority

Repository artifacts have different roles:

| Artifact              | Authority                                                       |
| --------------------- | --------------------------------------------------------------- |
| `AGENTS.md`           | Mandatory repository and security instructions for contributors |
| `docs/specs`          | Required current and target behavior, with explicit status      |
| `docs/architecture`   | Accepted decisions, rationale, and consequences                 |
| Source and tests      | Current executable behavior and evidence                        |
| `docs/demo-scenarios` | Reproducible simulator qualifications and evidence              |

If code differs from a requirement marked **implemented**, treat it as a defect
or specification drift. A requirement marked **target** is not evidence that
the behavior exists today.

## 2. Requirement language

- **MUST / SHALL:** Mandatory for the stated release or status.
- **MUST NOT / SHALL NOT:** Prohibited.
- **SHOULD:** Expected unless an ADR records a justified exception.
- **MAY:** Optional behavior.
- **Proposed:** Requires an explicit decision before becoming normative.

## 3. Status vocabulary

Every substantial specification should use one of these statuses:

- **Implemented:** Enforced by current source and tests.
- **Partially implemented:** Some enforcement exists; listed gaps remain.
- **Target:** Required for the enterprise product but not implemented.
- **Proposed:** Direction for review; not yet an accepted requirement.
- **Deferred:** Intentionally outside the current roadmap phase.

## 4. Requirement identifiers

Requirements use stable identifiers:

```text
FR-xxx       Functional requirement
SEC-xxx      Security requirement
REL-xxx      Reliability requirement
PERF-xxx     Performance requirement
OPS-xxx      Operability requirement
DATA-xxx     Data, retention, or privacy requirement
PORT-xxx     Portable contract and adapter requirement
```

IDs are never reused for different semantics. Superseded requirements remain
in history or are explicitly marked superseded.

## 5. Change protocol

Any security boundary or externally visible behavior change must:

1. identify affected requirement IDs;
2. update the relevant design or interface specification;
3. add or update an ADR when trust assumptions or major trade-offs change;
4. update canonical contracts before transport-specific adapters;
5. add tests for success, denial, malformed input, and bypass attempts;
6. update the traceability matrix and relevant public documentation; and
7. pass the complete validation suite.

## 6. Compatibility and versioning

- Public contracts and signed artifact formats MUST be versioned.
- Unknown major schema versions MUST fail closed.
- Canonicalization and signature algorithms MUST be explicitly versioned.
- Backward compatibility MUST NOT weaken identity, policy, approval, replay, or
  executor verification.
- Deprecation requires a migration path, telemetry, and a removal release.

## 7. Secrets and evidence

Specifications and examples MUST use `.invalid`, simulated, or clearly fake
identifiers. They MUST NOT include real bearer tokens, private keys, client
secrets, cloud credentials, or enterprise identifiers.

Evidence records may store non-secret fingerprints and key IDs. They must not
store reusable credential material.

## 8. Definition of done for a specification-backed change

A change is complete when:

- behavior matches the cited requirements;
- trust inputs and derived fields are separated;
- failure behavior is explicit and fail-closed where required;
- code, tests, ADRs, and specifications agree;
- safe simulator tests cover the meaningful path;
- complete validation passes; and
- remaining limitations are recorded without implying future controls exist.
