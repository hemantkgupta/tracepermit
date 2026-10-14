# Scenario 5: latency and fail-secure qualification

**Requirements demonstrated:** FR-005, FR-006, FR-008, FR-009, SEC-002,
REL-001, REL-009, PERF-001, PERF-002, OPS-004

## Scenario objective

Replace an anecdotal four-request latency number with a repeatable local
measurement, and prove that missing sequence or sanitization dependencies
cannot convert uncertainty into execution.

## Run it

```bash
pnpm demo:scenario:qualification
```

The command performs warmup outside the samples, then reports:

- p50/p95 for 10,000 deterministic classification and policy evaluations;
- p50/p95 for 1,000 local authorization, permit, and in-memory simulator calls;
- a sequence-store outage with zero target dispatches; and
- an unresolved sanitize outcome with zero target dispatches.

It exits nonzero if the local PERF-001 25 ms p95 or PERF-002 100 ms p95
objective is missed, or if either fail-secure probe dispatches.

## How to describe the result

Say: “This qualifies the single-process local POC and the measurement harness.”
Do not say: “This proves the production SLO.” Production evidence still needs
concurrency, sustained/burst load, shared state, OAuth/JWKS, telemetry,
persistence, resource saturation, multi-region deployment, and tail-latency
analysis.

## Code map

| Responsibility                     | Implementation                                                                                 |
| ---------------------------------- | ---------------------------------------------------------------------------------------------- |
| Launcher                           | `scripts/run-semantic-qualification.sh`                                                        |
| Measurement and fail-secure probes | `java/adapters-http/src/test/java/io/tracepermit/adapters/http/SemanticQualificationMain.java` |
| Policy fast path                   | `java/policy/src/main/java/io/tracepermit/policy/PolicyEvaluator.java`                         |
| Complete local enforcement         | `java/application/src/main/java/io/tracepermit/application/SecureExecutionService.java`        |

## Honest limitation

The run is a local micro-qualification, not JMH and not a distributed load
test. Results vary by machine and concurrent workload. They are valuable
because the sample count, warmup, percentile rule, code path, and fail criteria
are explicit and rerunnable.
