# Native endpoint process-observation rehearsal

Scope: Java development stdio connector and local simulators. This procedure
is not itself evidence of a pass. The
[2026-10-16 positive capture record](../demo-scenarios/evidence/native-endpoint-observation-2026-10-16.md)
retains one successful named native bounded read; negative paths remain separate.
The earlier [model allow/block record](../demo-scenarios/evidence/codex-native-endpoint-2026-10-15.md)
is unchanged. Requirements: FR-016/017; SEC-003/012/014; OPS-004.

The [automated preparation record](../demo-scenarios/evidence/endpoint-observation-preparation-2026-10-16.md)
retains separate suite/counter results; it does not complete the native gate.

## 1. Prepare one matching profile

Use the active checkout and Java 21. Run one Gradle task per checkout. Stop only
your own previous fixture/client when changing profiles; do not kill unrelated
listeners, copy signing keys or weaken an observer/expiry check.

If another task is changing the shared checkout, use an isolated clean worktree
pinned to the revision being qualified. Start both launchers there and generate
fresh keys/session state; never copy them from the other checkout. The launcher
must continue to reject `BACKEND_BUILD_MISMATCH`. Build caches may be reused;
source/runtime provenance must still refer to the pinned revision.

In one terminal, start `pnpm demo` from the revision being qualified and leave
it running. This builds the Java connector and creates a fresh ephemeral enrolled
key/session. A merge/commit does not update an already-running backend.

Prepare the prompts in the [connector runbook](java-mcp-endpoint-connector.md)
before starting the five-minute synthetic credential. In a second attached
terminal in the same checkout:

```bash
codex --version
pnpm demo:endpoint:codex --check
pnpm demo:endpoint:codex --capture-observations
```

Record the actual installed client/version; the prior qualified version was
0.162.1, not a latest-version requirement. Keep `gpt-5.6-sol` for comparison.
No TracePermit personal-account login is needed. If Codex itself requests model-
provider authentication, the operator completes it privately; do not collect
login cookies, account tokens or full client logs.

The stdio environment allowlist is documented by
[OpenAI](https://developers.openai.com/codex/mcp#stdio-servers). Configuration is
invocation-only. Other installed tools are not disabled; this is not a whole-
client sandbox or host-wide anti-bypass guarantee.

## 2. Stop first at discovery

Run `/mcp verbose` and verify the `tracepermit_endpoint` catalog contains only
`aws.logs.filter_log_events` and `aws.ec2.terminate_instances`. Discovery can
produce process observations but is not a policy result or model-selected call.
The normal launcher without the capture flag makes no observation files.

The launcher prints a new private directory under `.cache/endpoint-observations.*`.
Each Java connector process exclusively creates its own
`process-observations-<random-id>.jsonl` file there. The directory is 0700 and the
file 0600. Do not read or send the signing key/session descriptor instead.

Inspect the capture locally. Expect:

- `kind: endpoint-process-observation`, `schemaVersion: 1.0`;
- `stage: stability_checked_before_forward`, not gateway acceptance;
- a monotonically increasing per-process `sequence` and UTC `capturedAt`;
- request hash/profile, measured process ID, executable basename and SHA-256,
  ancestor basename and actual process `startedAt`;
- platform, development assurance and the actual `terminalAttached` value.

Confirm the measured executable is the actual native client, not a shell/test
harness. Keep the exact hash/start time. Never overwrite a false/unknown terminal
observation with a claim that a human approved a tool call. No full executable
path, device ID, credential, compact proof, key or raw MCP arguments belong in
the record. The hash covers canonical method/params, not the transport ID or a
gateway canonical-action hash.

## 3. One bounded read, then stop

Use only the connector runbook's **Prompt A** operation: one `/app/staging`
`ERROR` read with limit 2 through `tracepermit_endpoint`. Do not request a batch,
automatic retry, token refresh or a destructive real operation.
Select by exact server namespace and fully qualified tool name, not by a broad
`tracepermit` substring or description search across other installed servers.
On ambiguity, report only candidate names and stop without invoking a tool.

Correlate the new capture's request hash/time and actual process facts with the
MCP `allow / BOUNDED_LOG_READ / executed / simulated` result and one fresh Java
action-feed event. If the result omits an event/trace ID, do not invent it; use
the independently read server projection as in the prior evidence record.
Capture alone proves neither gateway verification nor a simulator dispatch.
The current public action projection omits the request/executable hashes. Record
the temporal/single-call correlation honestly; do not claim independent server
hash agreement. The binding covers full MCP method/params, including any client
metadata, not just the visible tool arguments.
The normal console is read-only and does not expose this private capture file.

If any step fails, stop and retain only its safe code. An empty/partial file or
observation followed by a transport/authentication failure is not successful
policy proof. `ENDPOINT_CAPTURE_UNAVAILABLE` stops forwarding for that request;
fix/relaunch deliberately instead of disabling capture inside the run.

After 32 records or 64 KiB the connector refuses another captured forward. A
fresh connector gets a new file; do not append to, truncate or reuse an old one.
Review retained files locally before exporting a bounded evidence artifact. They
are editable local diagnostics, not signed/immutable evidence. They survive
ordinary key cleanup and need operator-managed retention; there is no global
cross-restart quota.

## 4. Negative cases have a separate evidence level

```bash
pnpm test:endpoint-connector-e2e
```

The real loopback HTTP test
`fourSourceProofRejectionsCannotDispatchAnOtherwiseAllowedRead` sends:

| Case                      | Expected result                     | Dispatch counter                       |
| ------------------------- | ----------------------------------- | -------------------------------------- |
| Missing source proof      | 401 / `MISSING_SOURCE_ATTESTATION`  | 0                                      |
| Different valid bearer    | 403 / `INVALID_SOURCE_ATTESTATION`  | 0                                      |
| Changed read arguments    | 403 / `INVALID_SOURCE_ATTESTATION`  | 0                                      |
| Valid exact signed read   | 200 / simulated `executed`          | 1                                      |
| Replay of that same proof | 409 / `SOURCE_ATTESTATION_REPLAYED` | Still 1; no extra gateway ledger event |

These requests use generated keys, two synthetic valid CLI credentials and a
synthetic `ProcessObservation`, not native Codex process facts or a model-driven
attack. Do not relabel them as a manual native negative rehearsal. Invalid
requests have no policy event; the unchanged ledger is not an audit stream for
those pre-policy rejections. Keep native observation, HTTP verifier tests,
model-selection evidence and measured invocation counters separately labeled.

The dated positive record supplies fresh native capture/result correlation for
one read. Gate B still needs the remaining native negative-path rehearsal and
separately measured invocation evidence where required. Protected key custody/enrollment, platform code
signatures, production replay state and target/host egress isolation remain out
of scope. See [ADR 0038](../architecture/0038-private-endpoint-observation-capture.md).
