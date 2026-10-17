# Canonical Action Boundary Qualification

Requirements: SEC-001; SEC-002; SEC-005; FR-011; PORT-001; PORT-002; PORT-003.

This runbook documents the boundary qualification suite for the canonical action contract
(`ActionValidator` / `ActionNormalizer` in `java/contracts`). The suite qualifies the
defensive-copy boundaries on both sides of `ActionValidator.validate`, repeated-validation
input stability, conservative fail-closed handling of unsupported schema versions and
malformed field types, causal-lineage structure invariants, delegation narrowing, and the
delegation temporal-window edges. It complements the frozen vectors in
`contracts/vectors/validation-v1.json` (driven by `PortableContractsTest` in
`java/conformance`) without duplicating them.

---

## Guarantees under qualification

`ActionValidator.validate` normalizes its input first (`ActionNormalizer.normalize`,
rebuilds a completely fresh object tree field-by-field), applies the contract rules, and
returns a defensive copy of the normalized document (`return event.deepCopy()`). Three
independent isolation layers result:

1. The normalizer rebuilds every field from the input into a fresh tree.
2. The `clientClaims` and `action.arguments` subtrees are explicitly deep-copied out of
   the caller's tree during normalization, because those are the only subtrees the
   rebuild reuses structurally.
3. `validate` returns a deep copy of the normalized document, so no node of the returned
   tree aliases the (internal) normalized tree either.

The suite asserts each guarantee below; every negative case has a nearby valid positive
control in the same test or suite.

---

## Positive / negative matrix

| #   | Test                                                                             | Boundary                                                                                                                                                                                                                                                                               | Role                                                                   |
| --- | -------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| 1   | `validatedResultIsIndependentOfCallerTreeMutationsAfterValidation`               | Mutating identity, lineage, target, arguments, and `clientClaims` nodes of the caller tree after validation must not alter the result, its snapshot equality, or its canonical SHA-256; unknown fields must be stripped                                                                | Negative (isolation, result side) with field-level positive assertions |
| 2   | `mutatingResultDoesNotAlterCallerTree`                                           | Mutating the returned document (subject id, evidence fingerprint, arguments, target, hop subject, `clientClaims`) must not alter the caller tree or its canonical hash                                                                                                                 | Negative (isolation, caller side)                                      |
| 3   | `repeatedValidationDoesNotMutateInputAndIsIdempotent`                            | Repeated validation must not mutate the input; validation must be deterministic; validating an already-validated document must be a fixed point; documented defaults (`delegations` → `[]`, absent `clientClaims` → `{}`) must be established explicitly                               | Positive (input stability + normalization defaults)                    |
| 4   | `unsupportedSchemaVersionsFailClosed`                                            | `schemaVersion` values `1.1`, `2.0`, `0.9`, `"1.0 "` (trailing space), and a numeric version must fail closed with `ContractViolation` at path `schemaVersion`; `1.0` remains the positive control                                                                                     | Negative (fail closed) + positive control                              |
| 5   | `malformedRequiredFieldTypesAreRejectedOneFaultAtATime`                          | One-fault-at-a-time rejections with exact violation paths: `identity.evidence` as object, numeric `eventId`, 31-character `traceId`, date-only `observedAt`, missing `lineage`                                                                                                         | Negative (type fidelity)                                               |
| 6   | `duplicateHopIdentifiersAreRejected`                                             | A duplicated `hopId` must be rejected at `lineage.hops[1].hopId`; the untouched two-hop chain stays valid                                                                                                                                                                              | Negative + positive control                                            |
| 7   | `danglingParentHopReferenceIsRejected`                                           | A `parentHopId` that references no existing hop must be rejected                                                                                                                                                                                                                       | Negative                                                               |
| 8   | `rootHopInvariantsRequireExactlyOneRootForTheSubject`                            | Two root hops, a root hop for a foreign subject, and a chain with no root hop must each be rejected                                                                                                                                                                                    | Negative (3 variants)                                                  |
| 9   | `causalLineageIsAcceptedWithoutDelegatedAuthority`                               | A purely causal chain validates with an empty delegation list and no hop carrying `delegationTokenId` (a causal hop is not an authority grant); a self-labelled `delegated` assurance without a known token must be rejected                                                           | Positive (causal) + negative (SEC-001)                                 |
| 10  | `narrowedChildDelegationIsAcceptedAsThePositiveBoundary`                         | A valid parent/child delegation must validate with child scopes/resources strictly inside the parent, equal audience, child lifetime inside the parent lifetime, `parentTokenId` linkage, and the delegated hop referencing the child token; canonical hash must be stable across runs | Positive (FR-011 narrowing shape)                                      |
| 11  | `observedAtDelegationWindowBoundariesFollowInclusiveLowerAndExclusiveUpperEdges` | Fixed synthetic instants: `observedAt ==` child `issuedAt` accepts (inclusive lower edge); one millisecond before child issuance rejects; `observedAt ==` child expiry rejects (exclusive upper edge); `observedAt ==` root expiry rejects at the root grant                           | Negative/positive (4 boundaries, no sleeps)                            |
| 12  | `collapsedDelegationWindowIsRejected`                                            | `expiresAt == issuedAt` on a delegation must be rejected                                                                                                                                                                                                                               | Negative                                                               |

All fixture values are deterministic synthetic data (`example.invalid`-style principals,
simulated account IDs, fixed 2026-10-08 instants). Both tests reuse the checked-in
fixtures `fixtures/chatgpt-aws-read.json` and `fixtures/delegation-chain-valid.json` via a
fresh load per case; the fixtures themselves are never modified.

---

## Verification evidence

### How to run

```bash
source scripts/configure-java-environment.sh
java/gradlew -p java :contracts:test --tests io.tracepermit.contracts.ActionValidatorBoundaryQualificationTest
```

### Observed results

The canonical Gradle invocation above was executed against this suite and completed
successfully:

```
BUILD SUCCESSFUL in 12s
3 actionable tasks: 3 executed
```

The recorded JUnit result for the focused task is `tests="12" skipped="0" failures="0"
errors="0"`. The suite also runs green under a direct JUnit Platform launcher invocation
against the contracts module's pinned dependency set (JUnit 5.12.2, AssertJ 3.27.3,
Jackson 2.18.3), which is how the sensitivity demonstrations below re-ran it inside a
disposable worktree without a second Gradle daemon.

### Sensitivity control (guard-removal demonstrations)

Each demonstration ran in a disposable detached worktree at the same commit, recompiled
the contracts module, re-ran the 12-test suite, observed the expected failure, then
restored the exact production source (`git status` clean) and re-ran the suite green.

1. **Delegation temporal-window guard.** Replacing the window check in
   `ActionValidator.validateGrant` (`!observed.isBefore(issued) && observed.isBefore(expires)`,
   `ActionValidator.java:131`) with an always-true condition made exactly one test fail,
   `observedAtDelegationWindowBoundariesFollowInclusiveLowerAndExclusiveUpperEdges`, at its
   first reject boundary ("Expecting code to raise a throwable"). The other 11 tests stayed
   green. Restoring the guard returned the suite to 12/12.
2. **Copy-boundary layers.** The copy-isolation tests are backed by layered defenses, and
   the demonstrations show each layer honestly:
   - Removing only the `clientClaims` deep copy in `ActionNormalizer` (`:51`) left the
     suite green: the validator's return copy (`ActionValidator.java:60`) still isolates
     the caller from the result.
   - Removing only the return copy (`ActionValidator.java:60`) left the suite green: the
     normalizer's fresh-tree rebuild still isolates every rebuilt field.
   - Removing both the return copy and the `clientClaims` deep copy made exactly the two
     copy-isolation tests fail (`validatedResultIsIndependentOfCallerTreeMutationsAfterValidation`
     and `mutatingResultDoesNotAlterCallerTree`) with the expected diff: the result's
     `clientClaims.providerWorkspace` showed the post-validation mutation
     (`"relabelled"` / `"mutated"`) instead of the fixture value. The
     repeated-validation test stayed green because its input intentionally omits
     `clientClaims`, so the documented `{}` default is built fresh and never aliases the
     caller. Restoring both guards returned the suite to 12/12.

The `action.arguments` deep copy (`ActionNormalizer.java:105`) is protected by the same
layering mechanism as `clientClaims`; it was not separately bypassed.

---

## Capability limits and boundary guarantees

> [!NOTE]
> Validating the internal normalized contract does not authenticate a caller, verify a
> token signature, enroll an endpoint, or authorize an effect. These are contract-module
> guarantees only.

1. **Trust fields are representative, not self-asserted.** `authenticated` / `delegated`
   / `verified` values in the fixtures stand for trusted-adapter output: production
   adapters derive those fields after token or transport verification. Nothing in this
   suite constitutes a public API that accepts self-asserted trust state (SEC-001).
2. **A causal hop is not an authority grant.** The lineage hops of a causal chain carry no
   `delegationTokenId`; delegation authority exists only through the separate, verified,
   parent-linked delegation chain (test 9).
3. **Deep copies are isolation, not immutability.** The defensive copies establish that
   caller and result trees do not alias. The returned `JsonNode` remains a mutable
   Jackson node; callers that must not mutate it are responsible for their own handling.
4. **Message-level dedup with the frozen vectors.** The frozen vectors in
   `contracts/vectors/validation-v1.json` and `PortableContractsTest` already cover, at
   message level, root-principal mismatch, missing `scheduleId`, unverified delegation,
   missing `tokenHash`, unknown delegation token on a hop, child scope/resource/audience/
   issued-at/actor/lifetime faults, dangling delegation parent, causal cycle, delegation
   cycle, evidence-subject mismatch, unknown-field stripping, arguments-as-string, bad
   fingerprint format, offset timestamps, and unsupported UUID versions. This suite
   deliberately does not re-assert those messages; it qualifies the adjacent boundaries
   the vectors do not reach (copy isolation, idempotence, exact `ContractViolation.path()`
   values for structural faults, root-hop invariants, and the temporal-window edges).
5. **Simulator-only data.** All fixture values are synthetic. No bearer tokens, private
   keys, real enterprise identifiers, cloud calls, or external identity providers are
   involved at any point.

---

## Requirement coverage and limits

The [shared traceability matrix](../specs/12-traceability-matrix.md) records the bounded
evidence; this runbook cites the affected IDs without broadening their wording.

| Requirement  | Added evidence                                                                                                                                                     | Qualification boundary                                                                                       |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------ |
| SEC-001      | Self-labelled `delegated` assurance without a known token is rejected; causal hops carry no authority token; unknown fields are stripped before the canonical hash | Contract-module normalization/validation only; adapter-side trust derivation is not qualified here           |
| SEC-002      | Unsupported `schemaVersion` values and malformed required field types fail closed with conservative `ContractViolation`s, one fault at a time                      | Known fault families enumerated in the matrix; not an exhaustive fuzz qualification                          |
| SEC-005      | Canonical SHA-256 of the validated document is stable under caller mutation, repeated validation, and re-validation of an already-validated document               | Hash stability of the canonical form; permit/approval binding is out of scope for this suite                 |
| FR-011       | Positive parent/child delegation accepted with strict scope/resource containment, equal audience, lifetime containment, and parent linkage                         | Structure of the checked-in valid chain; token issuance/verification mechanics are adapter/identity concerns |
| PORT-001/002 | Versioned canonical contract artifacts exercised through their normalized form with deterministic golden fixture data                                              | Reuses checked-in artifacts read-only; no artifact content changed                                           |
| PORT-003     | Java-side conformance surface extended with deterministic boundary tests against the checked-in contracts module                                                   | Complements, does not replace, the shared-vector conformance suite                                           |

---

## Unimplemented target scope

1. **Public trust API:** the contract module remains an internal normalized-contract
   validator; no authentication, token-verification, endpoint-enrollment, or authorization
   decision is exposed or claimed by this suite.
2. **Immutability guarantees:** returning an immutable document type instead of a deep
   copy of a mutable `JsonNode` remains deferred; the suite pins the current isolation
   behavior, not an immutability promise.
3. **Fuzz/exhaustive malformed-input coverage:** the one-fault-at-a-time matrix covers the
   documented required fields; automated fuzzing of arbitrary malformed structures remains
   out of scope.
