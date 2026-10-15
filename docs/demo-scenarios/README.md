# TracePermit live demo scenarios

These scenarios are a reproducible path through the TracePermit POC.
Run them in number order. The sequence starts where an agent action originates,
then crosses the endpoint connector and enforcement gateway, and finally uses
the React console to explain the verified outcome.

The numbered files are the source of detailed commands, expected output, and
safety boundaries. Distinguish live-qualified compositions from independently
tested foundations and future production work using the
[current capability matrix](../specs/03-current-state-baseline.md).
The multi-origin rehearsal is recorded in
[qualification evidence for 2026-10-11](QUALIFICATION-EVIDENCE-2026-10-11.md).
The separate [2026-10-15 model-driven Codex approval record](evidence/codex-model-driven-approval-2026-10-15.md)
captures request/review/resume, replay, expiry and explicit reviewer-denial
evidence for the named local durable approval profile. The separate
[native endpoint record](evidence/codex-native-endpoint-2026-10-15.md) correlates
model-selected read/block with Java CLI-origin events; remaining endpoint
security qualification is not implied. The [Gate C deterministic record](evidence/gate-c-deterministic-presenters-2026-10-15.md)
refreshes automated qualification across Scenarios 4, 5, 7, 9, 10, and 11 via `pnpm demo:gate-c`.

## Safety and claim boundary

- Every downstream target is a deterministic local simulator.
- The Java connector, MCP/HTTP calls, source signing and verification, policy
  evaluation, execution-permit path, evidence projection, and React console are
  real POC components.
- Never configure AWS, Jira, model-provider, or enterprise credentials for
  these scenarios.
- Never display the development bearer token, endpoint private key, compact
  source-attestation JWS, or replay token ID.
- A source attestation proves bounded source facts. It does not prove that the
  requested action is safe or authorized.

## Common setup

In Terminal 1:

```bash
cd tracepermit
pnpm demo
```

Open the console at <http://127.0.0.1:5173>. Keep Terminal 1 running. Use a
second terminal for Scenarios 0–2. Stop the composition with `Ctrl+C` before
Scenario 6: the development and OAuth profiles both use ports 5173, 8080, and
8090 and are deliberately not co-hosted. Scenarios 3–5 and 7–11 are standalone:
Scenarios 3 and 8 use disposable PostgreSQL for approval, while semantic,
scheduled-stop, synthetic-exfiltration, and latency qualification use local Java application boundaries.

An intentional stop exits successfully and removes the local session descriptor
and endpoint signing key. A child crash still fails the launcher. Start a fresh
`pnpm demo` session before running `pnpm demo:run` after Scenarios 0–1 or repeating
the same reads: the gateway retains recent reads for one minute, so accumulated
requests can correctly trigger the rapid-read policy instead of another allow.

## Recommended order

| Order | Scenario                                                                                         | Audience question answered                                                                                 | Command                                      |
| ----- | ------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| 0     | [Endpoint connector trust construction](00-connector-origin-and-egress.md)                       | What originates on the endpoint, what does the connector add, and what crosses the server trust boundary?  | `pnpm demo:scenario:connector`               |
| 1     | [Attested CLI allow then block](01-attested-cli-allow-block.md)                                  | How can the same authenticated identity receive different decisions based on action and intent?            | `pnpm demo:scenario:cli`                     |
| 2     | [Unmodified ChatGPT secure-tunnel path](02-unmodified-chatgpt-secure-tunnel.md)                  | Can a hosted unmodified agent reach the real Java enforcement path, and what identity does that prove?     | `pnpm demo:tunnel`                           |
| 3     | [Request-bound human approval](03-request-bound-human-approval.md)                               | Can one independent reviewer authorize one exact mutation without permitting mutation or replay?           | `pnpm demo:scenario:approval`                |
| 4     | [Signed workload and semantic sequence](04-signed-workload-semantic-sequence.md)                 | Can verified automation be sanitized, replay-protected, and blocked when benign reads form a toxic chain?  | `pnpm demo:scenario:semantic`                |
| 5     | [Latency and fail-secure qualification](05-latency-and-fail-secure-qualification.md)             | Is the deterministic path measured with enough local samples, and do dependency failures prevent dispatch? | `pnpm demo:scenario:qualification`           |
| 6     | [OAuth-attributed hosted client](06-oauth-attributed-hosted-client.md)                           | Can an unmodified hosted client carry a provider-verified user identity through the same simulator path?   | `pnpm demo:oauth` + `pnpm demo:tunnel:oauth` |
| 7     | [Signed scheduled EC2 stop block](07-signed-scheduled-ec2-stop-block.md)                         | Can a valid signed workload and delegation still be denied before execution?                               | `pnpm demo:scenario:scheduled-stop`          |
| 8     | [Production deletion requiring approval](08-production-deletion-request-bound-approval.md)       | Can a signed destructive request execute exactly once only after independent, request-bound approval?      | `pnpm demo:scenario:production-deletion`     |
| 9     | [Synthetic PII read then external-send block](09-synthetic-pii-read-external-send-block.md)      | Can gateway-owned history correlate two target systems and prevent a follow-on send?                       | `pnpm demo:scenario:pii-exfil`               |
| 10    | [Tool-result injection then IAM block](10-tool-result-injection-iam-block.md)                    | Can untrusted tool text induce a proposal without overriding action policy?                                | `pnpm demo:scenario:prompt-injection`        |
| 11    | [Sub-agent delegation amplification rejected](11-sub-agent-delegation-amplification-rejected.md) | Can a correctly signed child grant exceed its parent authority?                                            | `pnpm demo:scenario:delegation`              |

Use the `:auto` variant of either command for acceptance without presenter
pauses:

```bash
pnpm demo:scenario:connector:auto
pnpm demo:scenario:cli:auto
pnpm demo:scenario:approval:auto
pnpm demo:scenario:semantic:auto
pnpm demo:scenario:scheduled-stop:auto
pnpm demo:scenario:production-deletion:auto
pnpm demo:scenario:pii-exfil:auto
pnpm demo:scenario:prompt-injection:auto
pnpm demo:scenario:delegation:auto
```

The endpoint CLI profile still requires an attached terminal. `:auto` skips
presenter input; it does not turn an interactive human trigger into an
automated one or weaken source verification.

The automated multi-origin qualification remains available as
`pnpm demo:run`; it is useful as evidence, but it is too dense to be the first
guided demonstration.

Scenario 2 requires one-time OpenAI tunnel/plugin setup and external environment
references described in its runbook. Start `pnpm demo:tunnel` immediately
before the live prompts because its local MCP credential lasts five minutes.
The scenario proves unmodified ChatGPT transport and enforcement, not the
signed-in ChatGPT user's identity.

Scenario 6 is the stronger replacement when the development IdP and OAuth
plugin connection are configured. It removes the shared injected MCP bearer
and attributes each request from the provider-issued token. Its 2026-10-11
Auth0/ChatGPT/tunnel simulator composition passed profile, allow-read, and
block-without-execution checks; the sanitized allow and block evidence is linked from the
scenario. Keep Scenario 2 as the deterministic transport fallback, and do not
generalize the Scenario 6 result into production or universal provider
qualification.

Scenario 3 requires Docker but no hosted-client configuration. It starts a
disposable PostgreSQL container, executes only against the local simulator,
and removes the container when complete. Its reviewer adapter is explicitly a
development-only identity boundary, not production OIDC.

Scenario 4 requires neither the combined demo nor Docker. It creates ephemeral
Ed25519 keys in memory and exercises the real signed-workload adapter,
delegation verifier, semantic policy, sanitizer, permit, evidence, and local
simulator. Scenario 5 is non-interactive and reports bounded local performance
qualification plus two zero-dispatch fail-secure probes. Neither scenario is a
production identity, durability, capacity, or SLO claim.

Scenario 7 reuses the signed-workload path with a separate simulated EC2 stop
proposal. It runs standalone, verifies the workload and narrow delegation,
then proves an explicit policy block with no permit or simulator call. The
`prod` environment is a risk label on a simulated target.

Scenario 8 combines a freshly signed scheduled deletion with the durable
request-bound approval path. Its only destructive effect removes one fixed
group from an in-memory simulator after an independently authorized development
reviewer approves the exact request. Docker is required; no real AWS target or
production reviewer login is involved.

Scenario 9 is a standalone signed-workload cross-tool exercise. A fixed local
read returns invented customer records, and a separate external-send proposal
is denied with a sequence-specific reason before the in-memory outbox is
called. It uses no network sender, real PII, or Docker.

Scenario 10 reads a fixed local runbook containing attacker-style text. A
deterministic agent emulator follows that text and proposes a signed simulated
IAM action, which Java blocks before dispatch. It tests the action-control
boundary, not an actual model's prompt-injection resistance; no AWS service
or credential is involved.

Scenario 11 signs a parent grant and a sub-agent child grant at runtime. One
narrow child read executes against the local simulator; four attempts to widen
scope, resource, audience, or lifetime are rejected by the trusted adapter
before policy or dispatch. These are identity-boundary failures, not policy
`block` decisions or gateway evidence records.

## Boundary explanation rule

At each boundary say three things explicitly:

1. what the previous component supplied;
2. what this component independently derived or verified; and
3. what this component is not authorized to claim or execute.

This prevents authentication, provenance, policy, and execution from being
collapsed into one vague idea of “trusted.”
