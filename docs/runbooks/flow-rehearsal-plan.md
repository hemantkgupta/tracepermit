# Flow rehearsal and qualification plan

Scope: simulator-only, local customer-owned pilot development. This is a plan,
not evidence that the remaining manual steps have passed. Run one profile at a
time and stop its own launcher before changing profiles. Do not kill unrelated
processes, reuse expired synthetic credentials, weaken policy or expose the
loopback browser approval profile through a tunnel.

The [developer flow guide](../flows-guide.md) maps these paths to Java code and
tests; the [scenario index](../demo-scenarios/README.md) has independent presenters.

## Current proof levels

| Flow             | Existing evidence                                                                                                                                                | Next manual gate                                                                                 |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| 1–2: read/block  | Deterministic adapter/executor tests; prior CLI and hosted-client simulator qualification                                                                        | Refresh the chosen client proof on the final demo revision                                       |
| 3: approval      | Native protocol proof plus Codex 0.162.1/`gpt-5.6-sol` model-selected request/resume, browser review, replay, expiry and explicit-denial rejection on 2026-10-15 | Named local approval cases complete; native source-attested connector is a separate gate         |
| 4: sanitization  | Signed-workload fixture/application tests; dated automated Gate C refresh                                                                                        | Optional operator narration; no new model-driven proof implied                                   |
| 5: sequence deny | Synthetic bulk-read/external-send fixture/tests; dated automated Gate C refresh                                                                                  | Optional operator narration of baseline and sequence-specific denies                             |
| 6: endpoint      | Named native Codex 0.162.1/`gpt-5.6-sol` model read/block correlated with Java events; separate verifier/connector tests                                         | Retain exact observed process-fact evidence and distinguish remaining source-negative rehearsals |
| 7: hosted OAuth  | One dated Auth0 + unmodified ChatGPT + private-tunnel composition                                                                                                | Reconnect/rehearse only that configured profile; collect fresh evidence if used                  |
| 8: delegation    | Signed narrow-chain fixture plus four amplification rejects; dated automated Gate C refresh                                                                      | Optional operator narration; fixture signers are not real sub-agents                             |
| 9: uncertainty   | Approval lost-completion/restart test plus separate unknown-queue worker tests                                                                                   | Test-guided explanation; no live automatic recovery daemon is qualified                          |

Historical records: [hosted/semantic qualification](../demo-scenarios/QUALIFICATION-EVIDENCE-2026-10-11.md)
and [native Codex approval qualification](../demo-scenarios/evidence/codex-durable-approval-2026-10-15.md).
The separate [model-driven record](../demo-scenarios/evidence/codex-model-driven-approval-2026-10-15.md)
preserves the later prompt rehearsal and its bounded screenshot evidence.
The [native endpoint record](../demo-scenarios/evidence/codex-native-endpoint-2026-10-15.md)
adds model-selected read/block through the Java stdio connector on the same date.
Do not overwrite dated evidence or convert an automated protocol test into a
claim that the model selected a tool.

## Gate A: model-driven Codex approval

The named Codex 0.162.1/`gpt-5.6-sol` profile has passed request → synthetic Bob
review → explicit resume, replay rejection, expiry rejection and explicit Bob
denial → rejected resume. **Gate A is complete only for this named local profile**,
not production identity/deployment, other clients or arbitrary prompts.
Follow [the exact runbook](codex-durable-approvals.md), including installed-version
checks and private synthetic token handling. The full sequence below is for
reproduction; the next separate qualification is Gate B, not another approval write.

1. Run `pnpm demo:approvals:codex` with Java 21 and Docker. Keep the printed
   private token path and environment off-screen; use the printed ports.
2. Connect a read-only Codex session with its invocation-only MCP configuration.
   Confirm the actual handshake and three-tool catalog, not just configuration.
3. Prepare the reviewer browser and all prompts before creating the request;
   refresh/reload the synthetic bearer immediately before the requester session.
   Send the runbook's request-only prompt. Verify `pending / not_executed`, save
   the exact approval ID/hash, and check the exact `sg-demo` arguments.
4. Sign in separately as synthetic Bob in the browser. Approve the same hash.
   Verify `approved / not_executed` before any requester resume.
5. Ask Codex to read status, then explicitly resume once using the original ID/hash.
   Verify `consumed / succeeded` and that the target is a simulator.
6. Replaying the resume must return `APPROVAL_NOT_EXECUTABLE`. Do not infer a
   measured dispatch count from UI resource state; use the separate automated
   counter/claim tests. With a fresh request, Bob's explicit denial must leave
   `denied / not_executed`, and resume must return `APPROVAL_NOT_EXECUTABLE`.
7. Stop and discard the fixture. Never record/reuse its synthetic credential or
   automatically recreate an uncertain write. A token refresh does not extend an
   existing approval's expiry.

User participation: the operator runs the model prompt and the separate reviewer
clicks. The fixture needs no personal login or external IdP change. Any model
service usage is separate from the fixture's synthetic identity system.

## Gate B: native endpoint connector

Start from [Scenario 0](../demo-scenarios/00-connector-origin-and-egress.md) and the
[connector runbook](java-mcp-endpoint-connector.md). This is **not** the bearer-only
approval MCP profile. First agree the installed native client's stdio registration
and key/profile configuration; do not silently change global client settings.

The [2026-10-15 named native rehearsal](../demo-scenarios/evidence/codex-native-endpoint-2026-10-15.md)
passed model-selected `allow / executed` and `block / denied`, independently
correlated with `succeeded` and `not_executed` action-feed events. An earlier
`EXPIRED_ACCESS_TOKEN` was correctly kept separate from the policy denial.
`pnpm demo:endpoint:codex` now packages same-checkout/build/key/readiness preflight,
explicit Java forwarding, resolved native executable and a fresh synthetic token.
Its isolated launcher tests are not additional model-selection evidence.

**Gate B remains partial:** no full executable-hash/start-time transcript or
independent live dispatch counter was retained for these model calls.

Capture actual parent executable/start-time/hash facts and verifier outcomes,
then exercise missing, changed-request, wrong-credential and replayed proof
rejections. A harness parent is not proof of native-client identity. No production
device enrollment, protected key custody or host-wide egress claim follows from
this test.

## Gate C: deterministic presenter refresh

The [2026-10-15 Gate C qualification record](../demo-scenarios/evidence/gate-c-deterministic-presenters-2026-10-15.md)
documents the refreshed execution and verified invariants across all six
deterministic presenter scenarios. `pnpm demo:gate-c` packages these scripts into
one unified, automated runner:

```bash
pnpm demo:gate-c
```

This completes only the automated presenter-refresh gate for the named local
simulator profile. The scripts use synthetic signers and fixed local targets;
they are not native model calls, production qualification or human narration
evidence. Run one Gradle qualification at a time in each checkout: concurrent
test tasks share output/report paths and can invalidate a run. Use an isolated
checkout for an independent parallel verification.

The individual standalone commands remain available for step-by-step presentation:

```bash
pnpm demo:scenario:semantic:auto
pnpm demo:scenario:pii-exfil:auto
pnpm demo:scenario:delegation:auto
pnpm demo:scenario:prompt-injection:auto
pnpm demo:scenario:qualification
pnpm demo:scenario:scheduled-stop:auto
```

For durable approval failure/restart and recovery foundations, use
`pnpm test:approval-api --rerun-tasks` with Docker and the application tests in
`pnpm check`. Do not demonstrate an automatic executing-to-unknown sweep or a
real lookup integration: neither is composed into the browser launcher.

Hosted OAuth is optional and uses its own [Scenario 6 setup](../demo-scenarios/06-oauth-attributed-hosted-client.md).
It may require manual IdP login. Never route the browser approval fixture through
that tunnel or reuse its bearer-only token as hosted OAuth proof.

## Evidence checklist for each new qualification

Record commit SHA, installed client version, profile, UTC time and displayed
timezone; record action/tool and bounded arguments, policy reason, approval
ID/hash where relevant, durable status and simulator dispatch/effect counts.
Separate automatic tests, actual MCP protocol calls, model-driven choices and
human reviewer steps. Mark an incomplete step explicitly.

Use only synthetic IDs and documentation-range targets. Redact bearer/session
credentials, cookies, CSRF values, private key material, real issuer subjects and
account identifiers. Do not save raw HTTP authentication headers or environment
dumps. A UI success sentence alone is insufficient: correlate server decision,
durable state and simulator effect evidence.
