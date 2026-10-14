# Scenario 11 local qualification — 2026-10-13

**Scope:** Standalone Java signed-scheduled adapter, complete two-grant
delegation chain, policy/permit service, and deterministic local AWS simulator.
All keys were generated in memory. No real scheduler, AWS credential, SDK,
network target, or enterprise identifier participated.

| Command                                    | Observed result                                                                                                                                     |
| ------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| `pnpm demo:scenario:delegation:auto`       | Passed: one narrowed-chain log read allowed and dispatched; four fresh signed child escalations rejected before policy; zero additional dispatches. |
| `pnpm demo:scenario:semantic:auto`         | Passed: Scenario 4 sanitized a read, rejected credential replay, and blocked the third rapid read.                                                  |
| `pnpm demo:scenario:scheduled-stop:auto`   | Passed: Scenario 7 still blocks the signed simulated EC2 stop before dispatch.                                                                      |
| `pnpm demo:scenario:prompt-injection:auto` | Passed: Scenario 10 still blocks the signed simulated IAM proposal before role mutation.                                                            |
| `pnpm check`                               | Passed: console build/typecheck/tests and all Java modules, including the new presenter regression test.                                            |
| `pnpm format:check`                        | Passed after formatting the touched documentation.                                                                                                  |

## Secret-free presenter output

```text
TracePermit Live Scenario 11 — Sub-agent authority cannot grow
=============================================================
Runtime Ed25519 keys and one local AWS simulator; no real credential or target.

1. Valid chain: workload -> sub-agent -> simulator tool
   Parent grants read/export scopes and two simulated log groups.
   Child narrows to aws.logs.read on /app/prod for a shorter lifetime.
   Signed chain verified; Java policy allowed; local read dispatches: 1

2. Independently signed child escalation attempts
   extra aws.admin scope -> INVALID_DELEGATION_CHAIN; not_executed
   extra all-groups resource -> INVALID_DELEGATION_CHAIN; not_executed
   different executor audience -> INVALID_DELEGATION_CREDENTIAL; not_executed
   child expiry after parent -> INVALID_DELEGATION_CHAIN; not_executed

3. Boundary proof
   All four escalations rejected by the trusted adapter before policy or permit.
   Additional execution attempts: 0; additional simulator dispatches: 0
   Gateway evidence remained at 5 events from the valid read.

[PASS] Child grants cannot amplify a signed parent's authority.
```

The unchanged gateway evidence count confirms the negative requests never
entered the policy/evidence service. It is not an audit record for those
rejections. Rejection is observed in the presenter and tested by the verifier;
production needs an independently specified, bounded pre-policy rejection
telemetry route.
