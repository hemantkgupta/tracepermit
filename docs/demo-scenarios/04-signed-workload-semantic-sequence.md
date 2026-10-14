# Scenario 4: signed workload, sanitization, and sequence control

**Requirements demonstrated:** FR-001, FR-002, FR-005, FR-006, FR-008,
FR-009, FR-010, FR-011, FR-012, FR-014, SEC-001, SEC-002, SEC-005,
SEC-006, SEC-012, REL-001, REL-009, OPS-004

## Scenario objective

Show one automated origin whose identity and delegated authority are verified
cryptographically, then prove that authentication is not authorization. An
oversized read is transformed and re-authorized; a credential replay and a
toxic read sequence are denied without extra target calls.

> Valid identity explains who caused the request. Policy still decides what
> that identity may do now, with these arguments and this recent history.

## Run it

This scenario is standalone and does not require `pnpm demo` or Docker:

```bash
pnpm demo:scenario:semantic
```

Automated acceptance:

```bash
pnpm demo:scenario:semantic:auto
```

The command strips common AWS/Jira credential variables. It generates two
Ed25519 key pairs in memory, trusts their public keys only inside the process,
and never prints compact credentials or private key material.

## Data flow

```mermaid
sequenceDiagram
  participant S as Local scheduler
  participant A as ScheduledActionAdapter
  participant Q as Sequence store
  participant P as Policy
  participant Z as Sanitizer
  participant E as Permit + simulator

  S->>A: Signed workload + narrowed delegation + limit 500
  A->>A: Verify signature, binding, replay, scope, resource
  A->>Q: Canonical authenticated workload action
  Q-->>P: Trusted recent-action context
  P-->>Z: sanitize oversized result window
  Z->>P: New event/hash with limit 100
  P-->>E: explicit allow for derived event
  E-->>S: simulated result
  S->>A: Replay first workload credential
  A-->>S: WORKLOAD_CREDENTIAL_REPLAYED
  S->>A: Two new independently signed reads
  Q-->>P: third read has RAPID_REPEATED_READ_SEQUENCE
  P-->>S: block; no third dispatch
```

## Proof points

1. The adapter—not the body—constructs the workload subject and delegated hop.
2. The first proposed limit is 500; policy returns `sanitize`.
3. The transformer creates a distinct canonical event at limit 100.
4. Policy explicitly allows the derived event before a permit is issued.
5. Reusing the signed workload credential fails replay validation.
6. The third rapid read is blocked from gateway-owned history.
7. The final simulator dispatch count is exactly two.
8. No real scheduler, AWS endpoint, reusable secret, or production credential
   participates.

## Code map

| Responsibility                   | Implementation                                                                                       |
| -------------------------------- | ---------------------------------------------------------------------------------------------------- |
| Launcher                         | `scripts/run-signed-scheduled-semantic-scenario.sh`                                                  |
| Presenter composition            | `java/adapters-http/src/test/java/io/tracepermit/adapters/http/SignedScheduledSemanticDemoMain.java` |
| Workload/delegation verification | `java/identity/src/main/java/io/tracepermit/identity/ScheduledWorkloadVerifier.java`                 |
| Trusted normalization            | `java/identity/src/main/java/io/tracepermit/identity/ScheduledActionAdapter.java`                    |
| Semantic policy                  | `policies/aws-agentic-demo.json`                                                                     |
| Sequence state                   | `java/application/src/main/java/io/tracepermit/application/InMemoryActionSequenceStore.java`         |
| Sanitization and execution       | `java/application/src/main/java/io/tracepermit/application/SecureExecutionService.java`              |

## Honest limitation

Runtime keys and history are process-local, revocation is in memory, and the
target is a deterministic simulator. Production requires managed scheduler
keys, a durable replay/revocation service, shared sequence state, KMS-backed
permit signing, and controlled target egress.
