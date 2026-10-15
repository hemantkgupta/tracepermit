# Gate C deterministic presenter qualification

Date: 2026-10-15. Runtime revision:
`1186320435d07b4f9f0066c48bd6a9a99819787c`.
The runner was introduced in PR #22 at `5ed659092e7fb947a20b2ac08ca96df11dc27a55`;
Java enforcement and presenter sources are unchanged from that runtime baseline.
The independent review rerun used `f8e108f2574fc686a30d47840a66ca736f1a8e20`
in an isolated source snapshot, completing before `2026-10-15T17:30:50Z`
(23:00:50 IST). The [retained presenter stdout](gate-c-deterministic-presenters-2026-10-15/presenter-output.txt)
is the actual successful output, not a reconstructed transcript.
Environment: Java **21.0.12.1**, macOS arm64.
Scope: deterministic local presenter scenarios using synthetic cryptographic
signers and safe target simulators. These are standalone, reproducible
presenters, not native model-driven turns or production cloud integrations.

Requirements supported: FR-005, FR-006, FR-011; SEC-001, SEC-002;
REL-001, REL-009; PERF-001, PERF-002; OPS-004.

## Rehearsal summary

All six deterministic scenarios passed in sequence under the unified runner
`pnpm demo:gate-c` in 32 seconds in the review rerun:

| Scenario        | Focus                                    | Invariants verified                                                                       | Outcome                                                                              |
| :-------------- | :--------------------------------------- | :---------------------------------------------------------------------------------------- | :----------------------------------------------------------------------------------- |
| **Scenario 4**  | Signed scheduled sanitization & sequence | `WORKLOAD_CREDENTIAL_REPLAYED`<br/>`RAPID_REPEATED_READ_SEQUENCE`                         | Window clamped 500 &rarr; 100; third read blocked; dispatches = 2                    |
| **Scenario 9**  | Synthetic PII exfiltration sequence      | `SYNTHETIC_PII_READ_THEN_EXTERNAL_SEND`<br/>`SYNTHETIC_PII_EXFILTRATION_SEQUENCE_BLOCKED` | Read allowed (20 records); follow-on send blocked; outbox sends = 0                  |
| **Scenario 11** | Sub-agent authority narrowing            | `INVALID_DELEGATION_CHAIN`<br/>`INVALID_DELEGATION_CREDENTIAL`                            | Valid read dispatches = 1; four amplification attempts rejected                      |
| **Scenario 10** | Tool-result prompt injection             | `IAM_POLICY_ATTACHMENT_BLOCKED`                                                           | IAM dispatches = 0; runbook-read dispatches = 1                                      |
| **Scenario 5**  | Latency & fail-secure qualification      | `SEQUENCE_STATE_UNAVAILABLE`<br/>`failSecureDispatches == 0`                              | Classification p95: 0.042 ms (&le; 25 ms); Authorization p95: 0.771 ms (&le; 100 ms) |
| **Scenario 7**  | Signed scheduled EC2 stop                | `PRODUCTION_TARGET`<br/>`SCHEDULED_EC2_STOP_BLOCKED`                                      | Blocked before simulator; dispatches = 0                                             |

## Detailed scenario results

### 1. Scenario 4: Signed scheduled workload & semantic sequence

- Command: `scripts/run-signed-scheduled-semantic-scenario.sh --auto`
- Subject: `workload:scheduled-log-review` (derived from verified Ed25519 credential)
- Delegation: `workload` &rarr; `tool:aws-logs-simulator`
- Initial proposal: `limit: 500`; Decision: `sanitize / LOG_RESULT_WINDOW_REQUIRES_SANITIZATION`
- Resolved proposal: `limit: 100`; Re-evaluated: `allow`; Simulator dispatches: 1
- Credential replay rejection: `WORKLOAD_CREDENTIAL_REPLAYED`; no extra dispatch
- Gateway-owned sliding history:
  - 2nd read: allowed and simulated (total dispatches: 2)
  - 3rd read: blocked (`RAPID_REPEATED_READ_SEQUENCE / RAPID_LOG_EXTRACTION_SEQUENCE_BLOCKED`)
- Total dispatches: 2 (never 3 or 4); Evidence ledger: 15 append-only events

### 2. Scenario 9: Synthetic PII read then external send blocked

- Command: `scripts/run-synthetic-pii-exfil-scenario.sh --auto`
- Baseline send without read: denied (`EXTERNAL_SEND_NOT_AUTHORIZED`), outbox sends: 0
- Synthetic customer read: allowed, dispatches: 1, 20 synthetic records returned (omitted from evidence)
- Follow-on send: cross-tool exfiltration sequence detected (`SYNTHETIC_PII_READ_THEN_EXTERNAL_SEND`), blocked (`SYNTHETIC_PII_EXFILTRATION_SEQUENCE_BLOCKED`)
- Send outbox count: 0; Evidence records: 11; raw records absent from evidence

### 3. Scenario 11: Sub-agent authority narrowing

- Command: `scripts/run-sub-agent-delegation-scenario.sh --auto`
- Parent grant: read/export scopes on two log groups
- Child grant: narrowed to `aws.logs.read` on `/app/prod` with shorter expiry (valid read dispatches: 1)
- Four amplification attempts independently signed and rejected by the trusted adapter:
  1. Extra `aws.admin` scope &rarr; `INVALID_DELEGATION_CHAIN`
  2. Extra `aws:simulated:log-group/all` resource &rarr; `INVALID_DELEGATION_CHAIN`
  3. Foreign executor audience &rarr; `INVALID_DELEGATION_CREDENTIAL`
  4. Child expiry after parent &rarr; `INVALID_DELEGATION_CHAIN`
- Additional simulator dispatches: 0; gateway evidence remains at 5 events from the valid read.
- These are pre-policy identity/delegation rejections, not policy `block` results.
  The unchanged gateway ledger is not an audit trail for the rejected requests.

### 4. Scenario 10: Tool-result prompt injection cannot authorize IAM mutation

- Command: `scripts/run-tool-result-prompt-injection-scenario.sh --auto`
- Verified read returned untrusted tool output containing injection instructions
- Deterministic agent emulator proposed the instructed IAM policy attachment
- Evaluation: `ACCESS_CONTROL_CHANGE / block-simulated-iam-policy-attachment`
- Decision: `block / IAM_POLICY_ATTACHMENT_BLOCKED`
- Simulated IAM dispatches: 0; role policy attached: `false`
- Runbook-read dispatches: 1; total dispatches: 1. This proves action enforcement
  after an injected proposal, not a model's ability to resist prompt injection.

### 5. Scenario 5: Local latency objectives & fail-secure qualification

- Command: `scripts/run-semantic-qualification.sh`
- Classification & policy (10,000 samples):
  - Measured p50: **0.029 ms**, p95: **0.042 ms** (objective &le; 25.0 ms &rarr; **PASS**)
- Authorization, permit & in-memory simulator (1,000 samples):
  - Measured p50: **0.484 ms**, p95: **0.771 ms** (objective &le; 100.0 ms &rarr; **PASS**)
- Sequence-state outage fail-secure: `SEQUENCE_STATE_UNAVAILABLE`, target dispatches = 0
- Missing sanitizer fail-secure: target dispatches = 0

The harness warms classification/policy with 2,000 evaluations and authorization
with 200 executions before collecting the respective 10,000 and 1,000 samples.
It sorts `System.nanoTime()` durations and uses the nearest-rank p50/p95.
This is a single-process local microqualification, not a production benchmark:
model/provider time, browser review, HTTP transport, remote authentication/JWKS,
PostgreSQL persistence, concurrent load and saturation are not measured.

### 6. Scenario 7: Signed scheduled EC2 stop blocked

- Command: `scripts/run-signed-scheduled-stop-scenario.sh --auto`
- Origin: `workload:scheduled-log-review` with signed delegation to `aws.ec2.stop`
  on a simulated instance labeled `prod`, not a real production instance
- Classification: `modify`, severity `medium`, signal `PRODUCTION_TARGET`
- Policy: `block-scheduled-ec2-stop / SCHEDULED_EC2_STOP_BLOCKED`
- Outcome: `block`, permit: none, simulator dispatches: 0

## Reproduce

```bash
pnpm demo:gate-c
pnpm demo:verify
```

## Scope and remaining qualification

The runner executes fixed Java test-classpath presenters with runtime-generated
signing keys. Its shell unit tests replace those presenters with stubs; only
the actual scenario runs establish the Java simulator assertions. Source-string
checks in `demo:verify` detect fixture drift, not enforcement correctness by
themselves. Build tooling can download dependencies; no host-wide network
isolation or credential-file isolation is claimed.

The standalone fixtures do not populate the normal demo's live console feed.
They are not new model-selected MCP calls, real sub-agent processes, external
PII inspection, a production performance SLO or live recovery-daemon proof.
Native endpoint Gate B remains partial: full observed process-fact capture and
the remaining native source-proof rejection rehearsals are separate work.

## Independent review checks

At review revision `f8e108f2574fc686a30d47840a66ca736f1a8e20`, an isolated
source snapshot passed:

- `pnpm test:gate-c`: five orchestration tests, no failures or skips.
- `pnpm demo:gate-c`: all six real Java presenters; stdout linked above.
- `pnpm check`: console build/typecheck and 12 React tests; Java environment,
  443 Java tests (61 suites, zero failures/errors/skips), and coverage gates.
  Compilation reused some Gradle cache entries; this is not a cold-build claim.
- `pnpm test:approval-api --rerun-tasks`, after the full check: 126 tests,
  zero failures/errors/skips. These are a subset of the Java test population,
  not 126 additional tests. All 24 selected Gradle tasks executed.
- `pnpm demo:verify`: script syntax, shutdown/cleanup checks, 26 endpoint-launcher
  tests, five runner tests and fixture-source drift checks.
- Formatting, documentation links and generated flow-diagram checks passed.
  The final evidence-only edits were separately checked for formatting/links,
  and the active checkout's unchanged public site built successfully.

No new model prompt, personal account login, cloud target call or browser
approval action was performed in this automated review. The broader release
operations and native-client acceptance gates remain incomplete.
