# Overflow-Safe CloudWatch Log-Window Classification

Requirements: FR-005, FR-006; SEC-002; REL-001; OPS-002.
Architecture Decision: [ADR 0039](../architecture/0039-policy-lifecycle-staging-rollback-and-simulation.md).

This runbook documents the resolution of the integer-narrowing gap in CloudWatch log-window
classification within `PolicyEvaluator`. The fix ensures that any positive integral JSON
number greater than the 100-result threshold is accurately identified as an oversized log
query without integer truncation, bit-wrapping, or floating-point precision loss.

---

## Vulnerability & Root Cause Analysis

For the simulated CloudWatch log query tool `aws.logs.filter_log_events`, the policy classifier
evaluates whether the requested `limit` argument represents an oversized log extraction window.
The original code inspected:

```java
if ("aws.logs.filter_log_events".equals(operation)
    && arguments.path("limit").isIntegralNumber()
    && arguments.path("limit").asInt() > 100) {
  score += 50;
  addSignal(signals, "OVERSIZED_RESULT_WINDOW");
  addSignal(semanticIntents, "POTENTIAL_DATA_EXFILTRATION");
}
```

In Jackson, `asInt()` narrows numeric nodes to a 32-bit signed Java `int` via `(int) longValue()`.
This introduced an integer truncation/wrap gap:

1. **Integer Overflow / Negative Wrap:** For values `Integer.MAX_VALUE + 1` (`2147483648L`) up to
   `4294967295L`, `asInt()` wraps into negative numbers (`-2147483648` to `-1`). Because negative
   numbers are `<= 100`, the condition evaluated to `false`.
2. **Modulo Wrap-to-Small:** For values such as `2^32 + 50` (`4294967346L`), the lower 32 bits
   wrap to `50`. Because `50 <= 100`, the condition evaluated to `false`.
3. **Long.MAX_VALUE Wrap:** For `Long.MAX_VALUE` (`9223372036854775807L`), the lower 32 bits wrap
   to `-1`, failing the `> 100` check.
4. **Arbitrary BigInteger Wrap:** Any `BigIntegerNode` whose lower 32 bits wrap to `<= 100` failed
   the check.

### Policy Impact in Simulator Templates

When `OVERSIZED_RESULT_WINDOW` was omitted due to numeric narrowing:

- Rule 200 (`block-oversized-log-window`) failed to match because it requires signal `OVERSIZED_RESULT_WINDOW`.
- Rule 100 (`allow-cli-staging-cloudwatch-read`) matched because tool name, origin, subject, and environment matched.
- The evaluation returned an unauthorized **`allow`** instead of **`block`** for huge extractions.

---

## Exact Numeric Solution

The classifier now compares the exact mathematical integer value using `BigInteger` comparison
without int/long truncation, floating-point conversion, or string coercion:

```java
private static final BigInteger OVERSIZED_LOG_WINDOW_THRESHOLD = BigInteger.valueOf(100);
...
if ("aws.logs.filter_log_events".equals(operation)
    && arguments.path("limit").isIntegralNumber()
    && arguments.path("limit").bigIntegerValue().compareTo(OVERSIZED_LOG_WINDOW_THRESHOLD) > 0) {
  score += 50;
  addSignal(signals, "OVERSIZED_RESULT_WINDOW");
  addSignal(semanticIntents, "POTENTIAL_DATA_EXFILTRATION");
}
```

### Exact Precision Guarantees

- `isIntegralNumber()` strictly accepts Jackson integral nodes (`IntNode`, `LongNode`, `ShortNode`, `BigIntegerNode`).
- `bigIntegerValue()` retrieves the exact mathematical integer without bit truncation or IEEE-754 floating-point conversion.
- `compareTo(OVERSIZED_LOG_WINDOW_THRESHOLD) > 0` evaluates true for every positive integral value exceeding 100.
- Preserves exact `<= 100` behavior (`100`, `99`, `0`, `-1`, `-100`), score increment (`+50`), and `high` severity.

```mermaid
flowchart TD
  subgraph Input ["Incoming Action Event"]
    ARG["action.arguments.limit"]
  end

  subgraph TypeCheck ["Integral Check"]
    IS_INT{"isIntegralNumber()?"}
  end

  subgraph ValueCheck ["Exact Value Comparison"]
    EXACT["bigIntegerValue().compareTo(100) > 0"]
  end

  subgraph Signals ["Classification Outcome"]
    OVER["Add OVERSIZED_RESULT_WINDOW<br/>Add POTENTIAL_DATA_EXFILTRATION<br/>score += 50 (high severity)"]
    NORM["Preserve low severity (score 10)<br/>No oversized signal added"]
  end

  ARG --> IS_INT
  IS_INT -- Yes --> EXACT
  IS_INT -- No (missing/string/float/bool/obj) --> NORM
  EXACT -- "> 100" --> OVER
  EXACT -- "<= 100" --> NORM
```

---

## Verification and Reproduction Evidence

### Reproduction: Failing Before Fix

Running `LogWindowNumericBoundaryTest` against the original `PolicyEvaluator.java`:

- **Total Tests:** 26
- **Failures:** 12
- **Passing:** 14 (controls `<= 100`, un-overflowed small numbers, non-integral controls)

```
LogWindowNumericBoundaryTest > simulationWithBothCandidateTemplatesBlocksOversizedNarrowingVectors() FAILED
    expected: "block" but was: "allow"
LogWindowNumericBoundaryTest > classifierCorrectlyIdentifiesOversizedResultWindowWithoutNumericNarrowing:
    - Integer.MAX_VALUE + 1: 2147483648 FAILED (missing OVERSIZED_RESULT_WINDOW)
    - 2^32 + 50: 4294967346 FAILED (missing OVERSIZED_RESULT_WINDOW)
    - Long.MAX_VALUE: 9223372036854775807 FAILED (missing OVERSIZED_RESULT_WINDOW)
    - Long.MAX_VALUE + 1: 9223372036854775808 FAILED (missing OVERSIZED_RESULT_WINDOW)
    - BigInteger wrap-to-small: 2*Long.MAX + 50 FAILED (missing OVERSIZED_RESULT_WINDOW)
    - parsed JSON: 2147483648 FAILED (missing OVERSIZED_RESULT_WINDOW)
    - parsed JSON: 4294967346 FAILED (missing OVERSIZED_RESULT_WINDOW)
    - parsed JSON: 9223372036854775807 FAILED (missing OVERSIZED_RESULT_WINDOW)
    - parsed JSON: 9223372036854775808 FAILED (missing OVERSIZED_RESULT_WINDOW)
    - parsed JSON wrap-to-small: 18446744073709551664 FAILED (missing OVERSIZED_RESULT_WINDOW)
LogWindowNumericBoundaryTest > simulationLeavesInputsUnmutatedAndStoresNoCatalogState() FAILED
```

### Passing After Fix

Running `LogWindowNumericBoundaryTest` against the fixed `PolicyEvaluator.java`:

- **Total Tests:** 26
- **Failures:** 0
- **Skipped:** 0

All 26 boundary cases, dry-run simulations against both candidate templates, input immutability
checks, zero-mutation catalog assertions, and non-integral characterizations pass cleanly.

---

## How to Run Qualification Tests

### Focused Unit Test

```bash
source scripts/configure-java-environment.sh
java/gradlew -p java :policy:test --tests io.tracepermit.policy.LogWindowNumericBoundaryTest
```

### Full Repository Gates

```bash
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
```

---

## Capability Limits and Boundary Guarantees

> [!NOTE]
> This fix addresses numeric narrowing in the raw classifier signal logic. It does not turn
> the classifier into general argument validation.

1. **Classifier Threshold vs Adapter Validation:** The 100-result threshold on
   `aws.logs.filter_log_events` is a coarse risk signal. Comprehensive argument validation
   (e.g., rejecting strings, booleans, floating-point numbers, or out-of-range parameters)
   remains the responsibility of trusted adapters at the ingress boundary.
2. **Non-Integral Argument Handling:** Missing, null, string (e.g., `"500"`), floating-point
   (e.g., `100.5`), boolean, or object values do not satisfy `isIntegralNumber()` and do not
   produce `OVERSIZED_RESULT_WINDOW`. This preserves existing behavior without breaking contract.
3. **Simulator-Only Boundaries:** All effects remain strictly in simulation. Zero real AWS calls,
   credentials, or production networks are accessed.

---

## Proposed Requirement and Traceability Updates

The following updates are proposed for `docs/specs/12-traceability-matrix.md` to be applied by
the repository integrator before branch merge:

| Requirement ID | Requirement Description                                                                                                  | Implementation Artifacts                                               | Verification Test                                                                               | Status                 |
| -------------- | ------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- | ---------------------- |
| **FR-005**     | TracePermit SHALL classify action class, severity, and risk signals from normalized action data.                         | `java/policy/.../PolicyEvaluator.java`                                 | `LogWindowNumericBoundaryTest`<br/>(overflow-safe limit threshold & signal generation)          | Qualified (simulation) |
| **FR-006**     | Policy SHALL return exactly one of allow, block, sanitize, or require approval, with policy version and reason evidence. | `java/policy/.../PolicyEvaluator.java`<br/>`policies/templates/*.json` | `LogWindowNumericBoundaryTest`<br/>(exact block outcome on oversized limits)                    | Qualified (simulation) |
| **SEC-002**    | Unknown tools, schemas, algorithms, issuers, audiences, decision values, and permit states MUST fail closed.             | `java/policy/.../PolicyEvaluator.java`                                 | `LogWindowNumericBoundaryTest`<br/>(large integer overflow fails closed into block)             | Qualified (simulation) |
| **REL-001**    | The enforcement engine SHALL evaluate actions deterministically and without numeric precision loss.                      | `java/policy/.../PolicyEvaluator.java`                                 | `LogWindowNumericBoundaryTest`<br/>(arbitrary-precision BigInteger boundary qualification)      | Qualified (simulation) |
| **OPS-002**    | Operators SHALL be able to identify active policy, key, schema, and adapter versions for an evaluation.                  | `java/policy/.../PolicySimulationResult.java`                          | `LogWindowNumericBoundaryTest`<br/>(asserts exact policyId, version, reasonCode, matchedRuleId) | Qualified (simulation) |

---

## Unimplemented Target Scope

1. **General Argument Matching:** Policy schema matchers continue to support only discrete
   enum/string criteria; arbitrary argument predicates remain deferred.
2. **Production Egress & Adapters:** Live AWS SDK or CloudWatch API communication remains
   unqualified and prohibited.
3. **Dynamic Administrative Policy Mutation:** Runtime policy administration via HTTP/MCP
   remains deferred.
