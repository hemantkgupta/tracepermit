# Scenario 9 local qualification

**Scope:** Signed local scheduled-workload presenter, runtime-generated
development keys, fixed invented customer dataset, and an in-memory outbox
without a network sender. This is reproducible POC evidence, not a production
audit or a claim about arbitrary customer data.

| Command                                  | Observed result                                                                                                                                       |
| ---------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| `pnpm demo:scenario:pii-exfil:auto`      | Passed; baseline send blocked, 20-record synthetic read succeeded, follow-on cross-target send blocked as critical; one read dispatch and zero sends. |
| `pnpm demo:scenario:semantic:auto`       | Passed; Scenario 4 still sanitizes and re-authorizes an oversized read, rejects replay, and blocks the third read with two total dispatches.          |
| `pnpm demo:scenario:scheduled-stop:auto` | Passed; Scenario 7 still blocks the signed scheduled stop with zero dispatches.                                                                       |
| `pnpm check`                             | Passed; console build/typecheck/tests and Java aggregate checks, including simulator, sequence-store, and enforcement tests.                          |
| `pnpm format:check`                      | Passed after formatting the updated Markdown.                                                                                                         |

## Secret-free output

```text
TracePermit Live Scenario 9 — Synthetic PII read then external send blocked
=======================================================================
All customer names and addresses are invented; the outbox has no network transport.

1. Signed workload and exact delegation verified for each proposal
   Subject: authenticated workload:scheduled-log-review; trigger: scheduled
   Delegations: one fixed synthetic read or one fixed non-routable send

2. Baseline send, without prior read, is denied by default
   Reason: EXTERNAL_SEND_NOT_AUTHORIZED; outbox count: 0

3. Fixed synthetic customer read is allowed and simulated
   Class: read; signal: SYNTHETIC_PII_DATASET; invented record count: 20
   Read simulator dispatches: 1; raw records absent from evidence

4. Follow-on send crosses target systems and is blocked before execution
   Signal: SYNTHETIC_PII_READ_THEN_EXTERNAL_SEND; severity: critical
   Reason: SYNTHETIC_PII_EXFILTRATION_SEQUENCE_BLOCKED
   Permit: none; execution attempt: none; send outbox count: 0
   Total target dispatches: 1 (the read only)
   Evidence records: 11; no raw synthetic records or credentials stored

[PASS] Cross-tool exfiltration proposal was denied before any send side effect.
```

No raw customer row, private key, signed compact credential, access token, or
real destination was captured here. The read executes inside a simulator. The
signal reflects an authorized read proposal that precedes its successful
dispatch; it is not an independently persisted result-correlation ledger.
