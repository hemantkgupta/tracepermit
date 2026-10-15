# Flow rehearsal and qualification plan

Scope: simulator-only, local customer-owned pilot development. This is a plan,
not evidence that the remaining manual steps have passed. Run one profile at a
time and stop its own launcher before changing profiles. Do not kill unrelated
processes, reuse expired synthetic credentials, weaken policy or expose the
loopback browser approval profile through a tunnel.

The [developer flow guide](../flows-guide.md) maps these paths to Java code and
tests; the [scenario index](../demo-scenarios/README.md) has independent presenters.

## Current proof levels

| Flow             | Existing evidence                                                                                                        | Next manual gate                                                                                     |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------- |
| 1–2: read/block  | Deterministic adapter/executor tests; prior CLI and hosted-client simulator qualification                                | Refresh the chosen client proof on the final demo revision                                           |
| 3: approval      | Native Codex 0.142.4 app-server MCP calls, synthetic Chrome reviewer, explicit resume and replay rejection on 2026-10-15 | Model-driven Codex prompt → review → explicit resume → deny/replay                                   |
| 4: sanitization  | Signed-workload deterministic fixture and application tests                                                              | Presenter rehearsal of 500 → 100, re-evaluation and distinct hashes                                  |
| 5: sequence deny | Synthetic bulk-read/external-send fixture and tests                                                                      | Presenter rehearsal of both baseline and sequence-specific denies                                    |
| 6: endpoint      | Local process/source-proof harness and verifier/connector tests                                                          | Register the actual source-attested connector with native Codex, then qualify observed process facts |
| 7: hosted OAuth  | One dated Auth0 + unmodified ChatGPT + private-tunnel composition                                                        | Reconnect/rehearse only that configured profile; collect fresh evidence if used                      |
| 8: delegation    | Signed narrow-chain fixture plus four amplification rejects                                                              | Presenter rehearsal; do not call fixture signers real sub-agents                                     |
| 9: uncertainty   | Approval lost-completion/restart test plus separate unknown-queue worker tests                                           | Test-guided explanation; no live automatic recovery daemon is qualified                              |

Historical records: [hosted/semantic qualification](../demo-scenarios/QUALIFICATION-EVIDENCE-2026-10-11.md)
and [native Codex approval qualification](../demo-scenarios/evidence/codex-durable-approval-2026-10-15.md).
Do not overwrite dated evidence or convert an automated protocol test into a
claim that the model selected a tool.

## Gate A: model-driven Codex approval

This is the recommended next rehearsal, **not completed by this documentation
update**. Follow [the exact runbook](codex-durable-approvals.md), including the
installed-version checks and private synthetic token handling.

1. Run `pnpm demo:approvals:codex` with Java 21 and Docker. Keep the printed
   private token path and environment off-screen; use the printed ports.
2. Connect a read-only Codex session with its invocation-only MCP configuration.
   Confirm the actual handshake and three-tool catalog, not just configuration.
3. Send the runbook's request-only prompt. Verify `pending / not_executed`, save
   the exact approval ID/hash, and check the exact `sg-demo` arguments.
4. Sign in separately as synthetic Bob in the browser. Approve the same hash.
   Verify `approved / not_executed` before any requester resume.
5. Ask Codex to read status, then explicitly resume once using the original ID/hash.
   Verify `consumed / succeeded` and that the target is a simulator.
6. Replaying the resume must return `APPROVAL_NOT_EXECUTABLE` with no second
   dispatch. With a fresh request, denial must prevent resume as well.
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

Capture actual parent executable/start-time/hash facts and verifier outcomes,
then exercise missing, changed-request, wrong-credential and replayed proof
rejections. A harness parent is not proof of native-client identity. No production
device enrollment, protected key custody or host-wide egress claim follows from
this test.

## Gate C: deterministic presenter refresh

Run the standalone commands one at a time; these scripts use synthetic signers
and fixed local targets. They are not native model calls.

```bash
pnpm demo:scenario:semantic:auto
pnpm demo:scenario:pii-exfil:auto
pnpm demo:scenario:delegation:auto
pnpm demo:scenario:prompt-injection:auto
pnpm demo:scenario:qualification
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
