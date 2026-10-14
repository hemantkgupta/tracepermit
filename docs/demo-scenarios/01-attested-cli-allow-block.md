# Scenario 1: attested CLI allow then block

**Requirements demonstrated:** FR-001, FR-003, FR-010, FR-017, SEC-003,
SEC-008, SEC-011, SEC-014, OPS-004

## Scenario objective

Show that a valid identity and valid endpoint signature establish attribution,
not blanket authorization. The same endpoint-attested CLI identity requests a
bounded read and then a destructive simulated operation; policy allows the
first and blocks the second before dispatch.

## Run it

With `pnpm demo` running in Terminal 1 and the React console open, run in
Terminal 2:

```bash
pnpm demo:scenario:cli
```

The script pauses after the read. Filter the console to origin `cli`, open the
new ALLOW row, and explain identity and lineage. Return to Terminal 2, press
Enter, then open the BLOCK row for the simulated termination.

For acceptance without the pause:

```bash
pnpm demo:scenario:cli:auto
```

## Expected results

| Proposed action                              | Risk meaning                        | Decision | Target effect                      |
| -------------------------------------------- | ----------------------------------- | -------- | ---------------------------------- |
| Read two matching events from `/app/staging` | Bounded read/query                  | `ALLOW`  | One call to the local simulator    |
| Terminate `i-simulated-live-demo`            | Destructive infrastructure mutation | `BLOCK`  | `not_executed`; no target dispatch |

Both rows must retain the same CLI-origin human and endpoint actor. A changed
decision therefore cannot be explained by failed authentication; it comes from
the semantic action, resource context, risk classification, and policy.

## Live narration

For the allowed action:

> This request came through the real Java stdio endpoint connector. TracePermit
> attributes it to the human principal, device, executable process, CLI client,
> and credential fingerprint. Because this is a bounded read allowed by policy,
> TracePermit issues a request-bound execution permit and invokes only the safe
> simulator.

For the blocked action:

> This is the same authenticated identity using the same connector. Authentication
> alone is not authorization. The requested action is destructive, so
> TracePermit classifies it as critical risk and blocks it before target
> dispatch. `not_executed` is the important enforcement result.

Close with:

> TracePermit decides using identity, authority, intent, action, resource,
> lineage, and policy—not merely possession of a valid OAuth token.

## Real and simulated components

Real POC components:

- process observation and source-attestation signing;
- request and credential binding;
- Java stdio connector and MCP/HTTP calls;
- gateway verification, normalization, classification, policy, and permits;
- final evidence projection and React console.

Deliberately simulated components:

- AWS logs and EC2 resources;
- all downstream execution effects.

Do not connect the destructive demo path to a real AWS account. The value of
the demonstration is that the enforcement path is real while target effects
remain deterministic and safe.

## Evidence to point at

- the same human subject and endpoint actor on both rows;
- `read/query` versus destructive/critical risk classification;
- policy rule and reason code;
- `succeeded` for the read and `not_executed` for termination;
- causal lineage kept separate from authority delegation; and
- the terminal verification that exactly two evaluations entered the console
  and no bearer value appears in the projection.

## Failure explanation

The terminal pause is only presentation timing. It is not a human approval and
does not grant authority. If source verification, policy, permit issuance, or
another required security dependency fails, the request fails closed before
dispatch.
