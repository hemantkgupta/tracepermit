# Native endpoint observation: bounded-read qualification

Date: 2026-10-16. Qualified clean checkout and runtime:
`84b559d2bf80e7e97bf56d191cf6a0118695f992` (merged PR #23).
Named client: Codex CLI **0.162.1**, model **`gpt-5.6-sol`**, macOS,
Java **21.0.12.1**. All downstream targets were local simulators.

**Result:** one operator-prompted, model-selected bounded read returned
`allow / BOUNDED_LOG_READ / executed / simulated=true`. An independently read
Java action projection gained one matching CLI event. The optional Java capture
retained the actual native executable hash, process start time and terminal
observation before forwarding. This closes the positive process-capture gap
for this named read, **not all of Gate B**.

Requirements supported: FR-003/006/009/014/016/017; SEC-003/012/014; OPS-004.
The [selected evidence JSON](native-endpoint-observation-2026-10-16/evidence.json)
contains the measured observation, actual decoded MCP result and bounded server
projection. It is an operator-reviewed export, not a signed immutable ledger.
The [2026-10-15 allow/block record](codex-native-endpoint-2026-10-15.md) and
[automated preparation record](endpoint-observation-preparation-2026-10-16.md)
remain separate historical evidence.

## Profile and provenance

The shared development checkout advanced during the first attempt. The
launcher correctly returned `BACKEND_BUILD_MISMATCH` when its checkout no
longer matched the running backend. A separate clean Git worktree was then
pinned to the qualified revision above. Both `pnpm demo` and the native
connector launcher ran there. Build caches were reused; no signing key or
session descriptor was copied between checkouts. The demo generated fresh
ephemeral keys, and read-only online preflight checked the matching revision,
Java 21, simulator mode, readiness and private session/key metadata.

The operator launched `pnpm demo:endpoint:codex --capture-observations` and
supplied the prompt in the installed native client. Its selected tool was
`mcp__tracepermit_endpoint__aws_logs_filter_log_events`. The assistant inspected
only the bounded tool-call/result portion of that rehearsal session, selected
allowlisted capture and read-only Java API. No full client log, credential,
compact source proof, signing key or personal account identifier was exported.
No new backend code or policy change was needed for the successful read.

## Request and result

```json
{
  "tool": "aws.logs.filter_log_events",
  "arguments": {
    "logGroupName": "/app/staging",
    "filterPattern": "ERROR",
    "limit": 2
  }
}
```

The prompt restricted selection to the exact `tracepermit_endpoint` server,
required one call, and prohibited retries, shell commands and other MCP tools.
Policy: `pol-aws-agentic-demo-001`, version 1, rule `allow-cloudwatch-read`.
The actual MCP result's simulator output had `matchCount: 0` and `events: []`;
zero matching synthetic log events is a valid read result.

| Source                 | Observation                                               |
| ---------------------- | --------------------------------------------------------- |
| Actual MCP result      | `allow / BOUNDED_LOG_READ / executed`, `simulated: true`  |
| Java action projection | `allow / BOUNDED_LOG_READ / succeeded`, `simulated: true` |
| Total feed entries     | 8 seeded fixtures before; 9 entries after                 |
| CLI-origin entries     | 0 before; 1 after                                         |

The MCP result did **not** return event/trace IDs. These came from the
independently read server projection:

- Event ID: `052bb2d6-b87d-4032-83cf-5baa67cd063b`.
- Trace ID: `60fb46b4c712dfb2c6288e343e557979`.
- Server observation: `2026-10-16T06:27:16.841120Z`
  (`11:57:16.841120 IST`, UTC+05:30).

The feed delta is an observed event count, **not an independently measured
simulator invocation counter or exactly-once guarantee**.

## Process facts and correlation

The selected capture has sequence 4, stage
`stability_checked_before_forward`, and private directory/file modes 0700/0600.
Other retained records preceded the read; initialization/catalog records are
not additional model-selected tool calls.

- Executable: `codex`; measured process ID: `96644`; ancestor: `bash`.
- Process start: `2026-10-16T06:26:33.240Z`; platform: `macos`.
- Actual `terminalAttached`: `true`; assurance: `development`.
- Executable hash:
  `sha256:74c6a6d263f40d65f99c27a815275f5b4ed0bcc2baecc396b4cf3255498bdfc0`.
  A separate hash of the installed native binary matched; the native process's
  working directory matched the pinned worktree.
- Binding profile: `tracepermit-mcp-method-params-v1`.
- Captured request hash:
  `sha256:c39498c131534d776094a20de26388ac45940b1cf1c90dcdf0741024cc43c92f`.

| Boundary                                        | UTC timestamp      |
| ----------------------------------------------- | ------------------ |
| Client's selected tool-orchestration call began | `06:27:13.762Z`    |
| Java stability/capture before forwarding        | `06:27:16.825676Z` |
| Java action event observed                      | `06:27:16.841120Z` |
| Client tool result completed                    | `06:27:16.865Z`    |

These are local recorded timestamps, not a policy/network benchmark.
Correlation uses the single retained call, bounded arguments, process/time
sequence and one new matching event. The public action projection exposes
neither request hash nor executable hash: this is **not an independently
matched cryptographic cross-link**. The request hash covers full MCP
`method`/`params`, potentially including client metadata, not just visible
arguments or the gateway canonical-action/approval hash.

## Setup failures kept separate

An earlier model-generated selector matched `tracepermit` broadly in tool
names and the operation in names or descriptions. It found three matches and
exited before invoking the target. Capture records remained unchanged and no
CLI event appeared. This was a client-selection failure, **not a policy denial**.
Exact server namespace/name selection resolved it in the later session.

The build-mismatch rejection was a separate preflight failure. Neither failure
is relabeled as a successful policy block. The operator deliberately relaunched
with a fresh five-minute synthetic credential; no automatic retry/refresh or
weakened expiry/source check was used.

## Limits and next gate

- This capture is editable by its owner; it does not establish protected key
  custody, managed enrollment, platform code signatures or same-user isolation.
- The configured CLI gateway requires source verification before protected
  dispatch. The accepted call crossed that development boundary, but no JWS
  or separate verifier transcript is retained here. Capture alone would not
  prove gateway acceptance, authorization or execution.
- Terminal attachment does not establish physical-human presence or per-action
  authorization. The synthetic requester is not a personal/provider identity.
- No native missing-proof, mutation, wrong-credential or replay attempt was
  performed in this read. Automated HTTP negatives/counters remain separate.
- Other installed tools were not disabled. Prompt restrictions are not
  host-wide interception, credential isolation, egress enforcement or general
  client compatibility. This TracePermit call performed no real AWS operation.
- This record qualifies only the pinned revision and profile, not the
  concurrently developed policy-lifecycle branch or later deployments.

Use the [capture procedure](../../runbooks/native-endpoint-observation-rehearsal.md)
and [remaining Gate B plan](../../runbooks/flow-rehearsal-plan.md#gate-b-native-endpoint-connector)
for separately scoped negatives. Review future exports and keep evidence
levels distinct; do not overwrite this record with a later run.
