# Native Codex endpoint allow/block qualification

Date: 2026-10-15. Runtime revision:
`50937a768b2eecba53d795ffe1a3bfc176ed7350`.
Named client: Codex CLI **0.162.1**, model **`gpt-5.6-sol`**, macOS arm64,
Java **21.0.12.1**. Scope: operator-prompted native MCP calls through the Java
stdio endpoint connector, numeric-loopback Java gateway, safe simulator and
read-only action projection. This is separate from the
[bearer-only durable approval profile](codex-model-driven-approval-2026-10-15.md).

Requirements supported: FR-001/003/006/009/010/014/016/017;
SEC-003/004/011/014; REL-002. This record does not upgrade those requirements
to production implementation or complete every part of Gate B.

## Provenance and exact boundary

The operator launched the installed native binary and supplied the prompts;
the model selected the advertised MCP tools. The assistant independently read
the running Java API before and after the calls. No model call was fabricated
by an automated test driver. Both events belong to the same `pnpm demo`
backend session, at the revision above, with synthetic `cli-readonly` identity
and ephemeral development endpoint signing material.

The invocation-scoped MCP alias was `tracepermit_endpoint`, using the repository
`run-java-endpoint-connector.sh` launcher. The native executable's final symlink
was resolved before launch and selected Java 21 `JAVA_HOME` was explicitly
forwarded. A temporary machine-local shell launcher performed this rehearsal.
The reusable `pnpm demo:endpoint:codex` command was added **after** these model
calls; its tests and later startup checks are separate evidence, not the source
of the historical model results.

The gateway's CLI profile requires request- and credential-bound source proof
before protected dispatch. Accepted calls therefore crossed that configured
development verifier boundary. The retained read projection does **not** expose
the full observed executable hash/start-time snapshot or signed proof. No
independently retained process-fact transcript is claimed. Its fixed
`TracePermit managed CLI (development)` client label is not evidence of a
particular native executable by itself.

Only synthetic targets/identifiers are retained. No bearer, compact proof,
private-key bytes, cookies, personal IdP subject or account login is included.
The [selected server projection](codex-native-endpoint-2026-10-15/action-feed.json)
is an operator-captured artifact, **not** an independently signed audit ledger.

## Correlated results

Times below are exact server observation timestamps, not model round-trip
durations. Local display timezone: **IST, UTC+05:30**.

| Case             | Server UTC / IST                           | Policy                                   | MCP execution / console projection   |
| ---------------- | ------------------------------------------ | ---------------------------------------- | ------------------------------------ |
| Bounded log read | `16:30:14.221706Z` / `22:00:14.221706 IST` | `allow`, `BOUNDED_LOG_READ`              | `executed` / `succeeded`, simulated  |
| EC2 termination  | `16:41:48.623042Z` / `22:11:48.623042 IST` | `block`, `AGENT_EC2_TERMINATION_BLOCKED` | `denied` / `not_executed`, simulated |

Policy: `pol-aws-agentic-demo-001`, version 1. `prod` in the denied event is
a simulated target risk label, not a real account/environment.

### A. Model-selected bounded read

Arguments: `logGroupName: /app/staging`, `filterPattern: ERROR`, `limit: 2`.
The operator instructed exactly one log-read call and no other tool/command.
The CLI screenshot shows the actual tool name, arguments and structured result:
`simulated: true`, `allow / BOUNDED_LOG_READ`, `execution.status: executed`,
`execution.result.mode: simulated`, `matchCount: 0`, `events: []`.
Zero matching simulated events is a valid result, not a failed read.

Event ID: `40b1bfd9-8a15-40d2-97fd-0aab08119fbf`.
Trace ID: `34b701475a65cb5ed5953a3b26beba58`.
The CLI-origin action-feed count changed from **0 to 1**. The assistant's
read-only API check agreed with the policy result and `succeeded` projection.
The MCP response did not return these correlation IDs; they came from the
separate server projection, not model invention.

![Operator screenshot: native model-selected read and simulator result](codex-native-endpoint-2026-10-15/allowed-read.png)

### B. Expired credential rejection, not a policy deny

The first termination attempt returned JSON-RPC `-32000` with
`tracepermitCode: EXPIRED_ACCESS_TOKEN`. The development credential lasts
**300 seconds** and the connector reads its environment when it starts.
This was an authentication rejection, not a termination policy decision.
The CLI action-feed count remained **1**, with only the earlier read event.

The operator exited only the native CLI and deliberately started a fresh
session with a newly issued synthetic credential. The backend/policy/key
configuration was not weakened or restarted to obtain a different decision.
There was no automatic effectful retry or credential-lifetime extension.

### C. Model-selected termination denied by policy

Arguments: `instanceIds: ["i-simulated-native-codex"]`, `region: us-east-1`.
The operator supplied one simulator-only termination prompt after reconnecting.
The user supplied the CLI's structured-result excerpt and returned fields:

```json
{
  "simulated": true,
  "policyOutcome": "block",
  "reasonCode": "AGENT_EC2_TERMINATION_BLOCKED",
  "executionStatus": "denied"
}
```

This block is a selected-field transcription of that user-supplied result,
not a raw full MCP envelope or a screenshot. No deny screenshot was supplied.

Event ID: `e3ed5bfd-8545-4dda-b118-06ace0c170e7`.
Trace ID: `ea71898a5729836ef1abb03e16139c13`.
The assistant's independent read found **2 CLI events** total: the earlier
allow plus this `block / not_executed` event. The CLI's red “Failed” label
wraps the expected structured policy denial; it is not a transport failure.

The bounded JSON capture was refreshed at `2026-10-15T16:53:01.275Z`
(`22:23:01.275 IST`). No MCP tool was called to collect it.

## Setup findings

- A native launch through the installer's final-component symlink failed
  conservative executable measurement. Resolving the binary path restored
  that observation without changing the Java `NOFOLLOW_LINKS` checks.
- Fresh discovery probes failed when selected Java 21 was not forwarded to
  the stdio subprocess. The machine's registered fallback selected Java 25.
  Explicit `JAVA_HOME` forwarding restored two-tool discovery with the normal
  repository launcher. This explains a reproduced setup failure; it is not
  proof that every earlier empty catalog had the same cause.
- A later backend was started from another checkout at `7d6ced7`, while the
  connector pointed at this checkout and its discarded ephemeral key.
  Restarting `pnpm demo` from the active checkout aligned build/session/key
  configuration. No private keys were copied between checkouts.
- `Auth: Unsupported` was the observed stdio client status even when both
  protected calls worked. In this profile the Java connector, not a separate
  client OAuth login, supplied the synthetic Bearer credential.

## What this does not prove

- No independent live simulator invocation counter was captured. Console
  state is not a measured dispatch count or an exactly-once guarantee.
- Missing, changed-request, wrong-credential and replayed source proofs were
  not manually generated by these two model calls. The
  [Java E2E tests](../../../java/adapters-http/src/test/java/io/tracepermit/adapters/http/JavaBackendEndpointConnectorE2eTest.java)
  and [verifier tests](../../../java/identity/src/test/java/io/tracepermit/identity/SourceAttestationVerifierTest.java)
  provide separate automated evidence. Full native process-fact capture and
  remaining negative-path rehearsal are still distinct Gate B work.
- Development signing keys are readable by same-user processes; this is not
  managed enrollment, protected key custody or platform-signature validation.
- An observed terminal and `trigger.kind: human` do not prove physical-human
  presence or authorization of each model-selected action. The synthetic
  bearer principal is not the operator's personal/model-provider identity.
- Other globally installed client tools were not disabled. Prompt restrictions
  are not host-wide interception, egress isolation or alternate-credential
  prevention. No real AWS service was contacted by these TracePermit tools.
- No Claude compatibility, hosted approval composition, arbitrary prompt
  robustness, production deployment or performance SLO was qualified.

## Reproduce

Follow the [native connector runbook](../../runbooks/java-mcp-endpoint-connector.md).
Keep the prompts ready before issuing the five-minute synthetic credential.
Use new event IDs and current server projections; do not reuse this record as
proof of a later revision. See the
[remaining Gate B work](../../runbooks/flow-rehearsal-plan.md#gate-b-native-endpoint-connector).
