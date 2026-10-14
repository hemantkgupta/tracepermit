# Product requirements

**Status:** Product baseline with proposed enterprise direction  
**Applies to:** Current POC and target product

## 1. Product vision

TracePermit is an Agentic Runtime Control Plane that attributes, classifies,
authorizes, and records AI-agent actions immediately before they reach
enterprise tools.

The product should let an enterprise answer:

> Who or what caused this action, what authority did it have, what exact
> operation was attempted, which policy applied, what was approved, and what
> outcome can be proven?

## 2. Problem statement

AI agents can translate broad goals into executable calls across cloud,
ticketing, code, data, and internal systems. Existing identity, network, and
target logs usually see only fragments of that chain. Enterprises need an
inline control point that preserves provenance without trusting model-supplied
claims and that prevents unauthorized effects rather than merely reporting
them later.

## 3. Primary users

| User                  | Need                                                                       |
| --------------------- | -------------------------------------------------------------------------- |
| Security architect    | Define trust boundaries, delegation rules, and fail-safe behavior          |
| Platform engineer     | Integrate MCP/API/CLI/scheduler traffic and target adapters                |
| Policy administrator  | Author, test, version, and roll out enforcement policies                   |
| Human approver        | Review an exact request and issue bounded approval                         |
| Auditor/investigator  | Reconstruct identity, lineage, policy, approval, execution, and outcome    |
| Application developer | Add tools without reimplementing authorization and evidence controls       |
| Product operator      | Monitor availability, latency, denial rates, replays, and unknown outcomes |

## 4. Core use cases

1. Authenticate a ChatGPT or Claude connector at a remote MCP boundary.
2. Normalize a narrow tool call into a canonical action.
3. Attribute human, service, API, CLI, and scheduled triggers with explicit
   assurance.
4. Classify action semantics and risk without trusting declared intent.
5. Apply ordered policy and return allow, block, sanitize, or approval-required.
6. Bind an allow or approval to the exact subject, action, resource, audience,
   policy version, and lifetime.
7. Execute through a constrained adapter using simulated targets first.
8. Prevent replay, mutation, and bypass.
9. Persist evidence and reconcile uncertain target outcomes.
10. Provide a control-plane console for policies, approvals, lineage, and audit.

## 5. Product principles

- **Evidence before claims:** Store what can be proved and label assertions.
- **Action before prompt:** Enforce the structured operation about to execute.
- **Complete mediation:** Every effect crosses a controlled enforcement point.
- **Least authority:** Delegations, permits, and target credentials are narrow
  and short-lived.
- **Fail closed:** Missing or invalid authority never becomes implicit allow.
- **Simulator first:** New effectful integrations prove behavior safely before
  receiving real credentials.
- **Audit honesty:** Unknown outcomes remain unknown until reconciled.
- **Portable core:** Canonical contracts and decision semantics remain stable
  across Java runtime evolution and versioned protocol migrations.

## 6. Scope by horizon

### Current POC

- Canonical identity/action/lineage contracts and frozen portability vectors
- Java endpoint, CLI, scheduled-workload, fixed-tunnel, and hosted OAuth paths
- Deterministic argument/sequence classifier and ordered JSON policy
- Allow, block, derived-action sanitize, and standalone durable approval flows
- Request-bound process-local permits plus a separately tested PostgreSQL
  durable enforcement/approval foundation
- Stateful safe AWS/Jira simulators, read-only React console, qualification
  scenarios, tests, and educational documentation

### Enterprise minimum viable product

- Production-composed OAuth/OIDC, workload identity, sender constraint, and
  revocation
- Shared durable actions, evaluations, approvals, permits, sequence state,
  attempts, evidence, and abuse controls
- Authenticated human approval API/UI and tenant administration
- Managed endpoint/workload key custody, enrollment, rotation, and revocation
- Java public edge with production certificates, telemetry, alerting, and
  operational rollback
- Tenant-aware policy validation, simulation, staged activation, and rollback

### Later enterprise product

- Reviewed real target adapters with least-privilege credentials
- Hybrid/customer-managed data plane options
- External audit anchoring and compliance exports
- Rich policy lifecycle, simulation, staged rollout, and console
- Additional agent protocols, targets, and semantic-analysis paths

## 7. Explicit non-goals for the current POC

- Direct destructive access to real AWS or Jira
- Capturing private model reasoning
- Inferring cryptographic delegation from causal order
- Treating a network tunnel as semantic authorization
- General-purpose secret storage
- Claiming exactly-once effects across independent systems
- Production multi-region availability or regulatory certification

## 8. Product success criteria

The product succeeds when it can demonstrate that:

- unauthorized or malformed requests never reach an executor;
- an allowed request cannot be changed before execution;
- delegated authority cannot expand across hops;
- sensitive requests can wait for exact, replay-resistant approval;
- direct routes and credentials cannot bypass enforcement;
- evidence reconstructs what was known and decided at each stage; and
- the Java enforcement core remains compatible with frozen language-neutral
  vectors and versioned artifact profiles.
