# Demo qualification evidence — 2026-10-11

**Local automated baseline:** `5fc6b15f28c19f36986da10a77801c5f843b6696`
(Add semantic sequence enforcement and POC qualification, #17)

**Hosted OAuth evidence revision:**
`ee47c88d88fc3899738c499ab6d4c5f2d70cb06c` (Qualify hosted-client OAuth
enforcement)

**Result:** Local automated and presenter-path gates passed; the
Auth0/ChatGPT OAuth simulator composition also passed its live profile,
allow-read, and block-without-execution checks

The original fresh-checkout run below qualifies the local automated baseline.
Scenario 6 did not exist at that revision. Its live captures were introduced
with `ee47c88`; the runtime did not persist a source revision inside those JSON
captures, so this record does not retroactively claim one. Future live evidence
must record the runtime-reported build revision at capture time.

## Environment

| Component | Qualified value         |
| --------- | ----------------------- |
| Host      | macOS 27.2 (`26B5086k`) |
| Node.js   | 25.6.1                  |
| pnpm      | 11.25.0                 |
| Java      | OpenJDK 21.0.12.1       |
| Docker    | Client/server 29.8.2    |

This host's Node distribution does not include the optional `corepack`
executable. The frozen checkout qualification therefore verified the already
installed pnpm version as exactly `11.25.0` before running
`pnpm install --frozen-lockfile`. No dependency or lockfile changed.

## Fresh-checkout gate

A disposable clone of `origin/main` was created under `/tmp` with no project
`node_modules` or Gradle distribution. The following completed successfully:

```text
pnpm 11.25.0
pnpm install --frozen-lockfile       PASS
pnpm check                           PASS
pnpm format:check                    PASS
git status --short                   CLEAN
resolved HEAD                        5fc6b15f28c19f36986da10a77801c5f843b6696
```

The run downloaded the checksum-pinned Gradle 8.14.3 distribution and executed
all 50 Java aggregate check tasks instead of relying on an existing checkout's
Gradle configuration cache.

## Current-main revalidation

After the hosted OAuth evidence was merged, the clean `ee47c88` checkout was
revalidated locally. `pnpm check`, `pnpm format:check`, `pnpm demo:verify`, the
Java endpoint-connector E2E, PostgreSQL integration suite, approval scenario,
semantic scenario, and latency/fail-secure qualification all passed. The
forced Java aggregate rerun executed 275 tests with zero failures and measured
7,117 covered / 445 missed lines (94.12%) and 2,709 covered / 889 missed
branches (75.29%). This revalidation proves the current local source and test
state; it does not replace the separately observed external ChatGPT/IdP run.

## Local acceptance and live presenter paths

| Gate                               | Result | Relevant observation                                               |
| ---------------------------------- | ------ | ------------------------------------------------------------------ |
| `pnpm demo:verify`                 | Pass   | Java pin, Java-only workspace, and scripts verified                |
| `pnpm test:endpoint-connector-e2e` | Pass   | Real Java stdio connector crossed loopback enforcement             |
| Scenario 0                         | Pass   | Real endpoint proof accepted; one final console projection         |
| Scenario 1                         | Pass   | Same CLI identity: bounded read allowed, termination blocked       |
| Scenario 3                         | Pass   | Independent approval; mutation/replay denied; exactly one dispatch |
| Scenario 4                         | Pass   | Sanitize/re-evaluate, workload replay reject, third read block     |
| Scenario 5                         | Pass   | Both latency objectives and both zero-dispatch outage probes       |
| Scenario 2                         | Pass   | Unmodified ChatGPT reached Java through Secure MCP Tunnel          |
| Scenario 6                         | Pass   | Authenticated profile/read/block through Auth0 and ChatGPT         |

## Measured local results

```text
Deterministic classification + policy
  samples: 10,000
  p50:     0.031 ms
  p95:     0.049 ms
  objective: <= 25 ms p95 — PASS

Signed authorization + permit + in-memory simulator
  samples: 1,000
  p50:     0.534 ms
  p95:     1.037 ms
  objective: <= 100 ms p95 — PASS

Sequence-state outage
  target dispatches: 0 — PASS

Missing sanitizer
  target dispatches: 0 — PASS
```

These are local POC results. They do not establish production throughput,
regional latency, availability, shared-state contention, IdP latency, or real
target latency.

## Shutdown and safety

- The combined demo stopped via its normal interrupt path.
- Ports 5173, 8080, and 8090 were released after shutdown.
- The disposable approval PostgreSQL container was removed by the scenario.
- No real target or cloud credential was configured.
- No bearer credential, private key, compact source proof, or replay token was
  printed in the presenter output.

## Hosted-client OAuth evidence

The Scenario 6 live run used an unmodified ChatGPT web conversation, the
installed OAuth plugin, an Auth0 development tenant, OpenAI Secure MCP Tunnel,
the Java resource server, and the fixed simulator. The successful sequence was:

```text
provider-authenticated profile
  -> bounded staging log read: read / low / allow / succeeded
  -> simulated EC2 termination: destructive / critical / block / not_executed
```

A fresh exact-once positive rerun recorded one protected
`aws.logs.filter_log_events` request with `/app/staging`, `ERROR`, and limit
`2`. ChatGPT reported `allow`, `executed`, `BOUNDED_LOG_READ`, zero matching
events, simulator-only execution, and no retry. The Java action projection
recorded an authenticated human subject and separate asserted orchestrator
actor, `read`/`low`, policy rule `allow-cloudwatch-read`, `succeeded`, and 25 ms
policy-path latency. Its integrity projection was valid:

```text
action.observed -> policy.evaluated -> execution.succeeded
```

The tool result did not contain a profile label, and ChatGPT did not invent one.
Use `tracepermit.get_profile` for client-facing opaque-profile proof; the Java
projection remains authoritative for enforcement attribution. The sanitized
positive capture is
[`evidence/06-oauth-attributed-hosted-client-allow-2026-10-11.json`](evidence/06-oauth-attributed-hosted-client-allow-2026-10-11.json).

The blocked call was observed exactly once. The Java action projection recorded
an authenticated human subject and separate asserted orchestrator actor,
`AGENT_EC2_TERMINATION_BLOCKED`, policy
`pol-aws-agentic-demo-001` version 1, rule
`block-agent-ec2-termination`, and 6 ms policy-path latency. Its integrity
projection was valid and contained only:

```text
action.observed -> policy.evaluated -> execution.not_executed
```

No permit or target-dispatch event existed for the blocked call. Its sanitized
capture is checked in
at
[`evidence/06-oauth-attributed-hosted-client-2026-10-11.json`](evidence/06-oauth-attributed-hosted-client-2026-10-11.json).
Neither capture contains a provider subject, profile ID, email,
bearer/refresh token,
authorization code, token fingerprint, private key, client secret, evidence
hash, or private ChatGPT URL.

ChatGPT also rendered `INVALID_ARGUMENT` after correctly reporting the policy
block. The Java projections prove that normalization and policy evaluation
completed, so that extra string is tracked as a client-presentation issue and
is not part of the enforcement claim.

## Qualification scope

Scenario 6 depends on a short-lived development IdP token, ChatGPT account
connection, tunnel session, and local process, so each new live run requires
fresh qualification evidence. The dated result qualifies one simulator-only
composition; it does not establish
general IdP/client compatibility, production availability, revocation, shared
state, real target credentials, or anti-bypass egress.
