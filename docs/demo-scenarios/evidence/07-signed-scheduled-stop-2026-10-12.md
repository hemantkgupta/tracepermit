# Scenario 7 local qualification

**Scope:** Local Java presenter composition, runtime-generated development
signing keys, and a simulated AWS target label. No AWS credential, SDK call,
real instance, or hosted client was involved.

The following commands were run against the local working tree after adding
the scenario-specific policy. This is a reproducible POC qualification, not
an independent production audit.

| Command                                  | Observed result                                                                                                                             |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| `pnpm demo:scenario:scheduled-stop:auto` | Passed; signed scheduled identity and delegation verified; exact deny rule matched; simulator dispatch count `0`                            |
| `pnpm demo:scenario:semantic:auto`       | Passed; existing Scenario 4 still sanitized 500→100, rejected credential replay, blocked the third rapid read, and dispatched exactly twice |
| `pnpm check`                             | Passed; Java and console build, type checks, tests, and Java aggregate checks                                                               |
| `pnpm format:check`                      | Passed                                                                                                                                      |

## Secret-free Scenario 7 output

```text
TracePermit Live Scenario 7 — Signed scheduled EC2 stop is blocked
================================================================
The production label is simulated; no AWS endpoint or credential is configured.

1. Trusted adapter verified the scheduled origin
   Subject: authenticated workload:scheduled-log-review
   Signed delegation: aws.ec2.stop on one simulated instance
   Trigger: scheduled; target environment label: prod

2. Java classified and enforced the exact action
   Action class: modify; severity: medium; signal: PRODUCTION_TARGET
   Policy: block-scheduled-ec2-stop / SCHEDULED_EC2_STOP_BLOCKED
   Outcome: block; permit: none; execution: not_executed
   Simulator dispatch count: 0
   Evidence: action.observed -> policy.evaluated -> authorization.denied

[PASS] Verified automation was denied before any simulated side effect.
```

This record supports the exact local outcome shown. It does not prove that a
real scheduler key is protected, that a real AWS account is unreachable, or
that endpoint agents cannot bypass a future broker.
