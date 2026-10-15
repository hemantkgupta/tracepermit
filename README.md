# TracePermit

TracePermit is an open-source **Agentic Visibility & Control (AVC) proof of
concept**. Its Java gateway attributes and normalizes supported AI-agent tool
calls, derives intent and risk, evaluates policy, and verifies a request-bound
execution permit **before** a safe simulator can perform a side effect.

This repository is **not a production security control**. No demo route holds
real AWS or Jira credentials or dispatches to those services. The public
[website](https://tracepermit.org) hosts documentation, not the Java gateway.

## How it works

```text
Human / scheduled workload / CLI / hosted client
              | structured tool proposal
              v
      Trusted MCP or API adapter
      authenticate + derive source facts
              |
              v
      Canonical action -> intent/risk -> policy
              | allow / sanitize / approval / block
              v
      Exact, short-lived execution permit
              | executor verifies and claims once
              v
      Deterministic AWS/Jira simulator
              | bounded evidence and outcome
              v
      Read-only React console
```

The model's private reasoning is not visible to TracePermit. Enforcement
occurs at the tool/action boundary. A source attestation proves bounded origin
facts, not that the action is safe; causal lineage is not authority delegation.
The [architecture and flow](docs/end-to-end-flow.md), [capability
matrix](docs/specs/03-current-state-baseline.md), and [threat
model](docs/specs/10-security-threat-model.md) state the exact boundaries.

## Run locally

Prerequisites: Java 21, Node.js 22 or newer, and Corepack/pnpm 11.25.0.
Docker is needed only for PostgreSQL-backed scenarios and tests.

```bash
corepack enable
pnpm install --frozen-lockfile
pnpm check
pnpm format:check
pnpm docs:links
pnpm demo
```

Open the read-only console at <http://127.0.0.1:5173>. Stop the demo with
`Ctrl+C`. Run the [numbered simulator scenarios](docs/demo-scenarios/README.md)
for endpoint attribution, allow/block, approval, scheduled delegation,
semantic-sequence controls, and fail-secure behavior. The hosted-client OAuth
scenario is a separate, optional profile requiring an external test IdP and
private tunnel; it is not needed for the default demo.

## What is qualified

- Java 21 is the sole backend. The React/TypeScript console is a read-only
  frontend; `contracts/` contains checked-in language-neutral schemas and
  conformance vectors.
- Local loopback MCP, endpoint stdio connector, signed scheduled-workload
  adapter, PostgreSQL-backed approval/execution tests, and AWS/Jira simulators
  are exercised in CI and the numbered scenarios.
- One Auth0 + unmodified ChatGPT + OpenAI private-tunnel profile was
  live-qualified on 2026-10-11 for OAuth attribution and simulated read/block
  behavior. This does **not** qualify Claude web, every ChatGPT environment,
  or a production public edge.
- The React console displays bounded Java action, evidence, lineage, and
  policy projections. It cannot approve or execute actions.
- A separate loopback-only authenticated approval API foundation is exercised
  with signed synthetic identities, PostgreSQL, exact action snapshots and
  simulators. Run `pnpm test:approval-api --rerun-tasks`; see the
  [API qualification runbook](docs/runbooks/authenticated-approval-api-foundation.md).
  It is not enabled by the demo launchers and provides no browser login or
  React approval inbox yet.

The [current-state capability matrix](docs/specs/03-current-state-baseline.md)
distinguishes implemented code, local demonstrations, live-client
qualification, and target architecture. In particular, production endpoint
key custody, real-target credential isolation, shared edge state, remote
reviewer authentication, and production evidence anchoring remain future work.

## Repository map

| Path                                                    | Role                                                                                         |
| ------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| [`java/`](java/)                                        | Canonical contracts, identity, policy, enforcement, adapters, simulators, persistence, tests |
| [`apps/console/`](apps/console/)                        | Read-only React UI                                                                           |
| [`contracts/`](contracts/)                              | Frozen language-neutral schemas, protocol snapshots, vectors                                 |
| [`docs/specs/`](docs/specs/README.md)                   | Requirements, HLD/LLD, interfaces, threat model, traceability                                |
| [`docs/architecture/`](docs/architecture/)              | Architecture decision records                                                                |
| [`docs/demo-scenarios/`](docs/demo-scenarios/README.md) | Reproducible simulator demonstrations                                                        |
| [`site/`](site/)                                        | Static documentation site; not a gateway deployment                                          |

## Security and contributions

Please report suspected vulnerabilities **privately** using [GitHub private
vulnerability reporting](https://github.com/hemantkgupta/tracepermit/security/advisories/new),
not a public issue. See [SECURITY.md](SECURITY.md) for scope and handling.
Changes to enforcement must preserve pre-execution mediation, add negative
tests, and update the affected requirement IDs in the
[traceability matrix](docs/specs/12-traceability-matrix.md). Read
[AGENTS.md](AGENTS.md) and [CONTRIBUTING.md](CONTRIBUTING.md) before changing
code or security claims.

Apache-2.0 licensed; see [LICENSE](LICENSE). The project name and artwork are
not a trademark grant.
