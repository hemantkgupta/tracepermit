# Authenticated approval API foundation

This is a simulator-only Java API composition, not a deployable product
profile. It is intentionally separate from `pnpm demo`, the hosted OAuth
demo and the read-only React console. No personal login or cloud credential is
needed for its automated qualification.

## Run the executable qualification

Prerequisites: Java 21, the pinned pnpm installation and running Docker.

```bash
pnpm test:approval-api --rerun-tasks
```

The tests start disposable PostgreSQL 16, generate ephemeral synthetic IdP
and Ed25519 permit keys in memory, and bind the API to an OS-assigned loopback
port. HTTP requests use actual signed access tokens through the existing
verifier. No real IdP, AWS/Jira service or personal account is contacted. The
test fixture closes its HTTP servers and removes its own database container.
The command produces the normal Gradle test report under
`java/adapters-http/build/reports/tests/test/`.

## API contract

Every route requires one `Authorization: Bearer` header, an allowlisted
issuer/audience/client/tenant, and an operator-enrolled principal. POST bodies
must use exactly `Content-Type: application/json`. Unknown/duplicate fields,
trailing JSON, invalid UTF-8 and bodies above 64 KiB are rejected. No query
parameters, cookies, browser origins or forwarded identity headers are used.
Responses use `Cache-Control: no-store` and contain no reusable credentials.

| Route                                  | Required authority                       | Body / outcome                                                    |
| -------------------------------------- | ---------------------------------------- | ----------------------------------------------------------------- |
| `POST /api/v1/approvals`               | Requester role + `approvals.request`     | Fixed simulator arguments; creates pending, no dispatch           |
| `GET /api/v1/approvals`                | Enrolled role + `approvals.read`         | Up to 50 newest snapshots in tenant; requester-only role sees own |
| `GET /api/v1/approvals/{id}`           | Same read authority, tenant/owner check  | Exact action, request hash, policy, expiry, status/outcome        |
| `POST /api/v1/approvals/{id}/decision` | Human reviewer role + `approvals.decide` | Exact hash and `approve` or `deny`; no self-approval              |
| `POST /api/v1/approvals/{id}/execute`  | Original requester + `approvals.execute` | Exact hash only; load stored action and consume once              |

Create body:

```json
{ "groupId": "sg-demo", "port": 443, "cidr": "192.0.2.0/24" }
```

The only operation is `aws.ec2.update_security_group` on account
`simulated-123`. `prod` is a policy-criticality label for the simulated target,
not a real production environment. Ports are integers 1–65535; only
`192.0.2.0/24`, `198.51.100.0/24` and `203.0.113.0/24` are accepted.

A response includes `approvalRequestId` and `requestHash`. A reviewer sends:

```json
{ "requestHash": "sha256:<exact-returned-hash>", "decision": "approve" }
```

The original requester sends:

```json
{ "requestHash": "sha256:<exact-returned-hash>" }
```

The placeholders above are not credentials or runnable identifiers. Never put
real bearer tokens into shell history, screenshots, source, evidence or public
support reports. There is no development token endpoint on this server.

## Security and recovery interpretation

Approval is not execution. `pending`, `approved`, `denied`, `expired` and
`consumed` are approval states; `executionStatus` separately reports the
durable attempt. A GET can show `expired: true` before a transition persists
the expired state. Failed or unknown attempts are not automatically retried.
An unavailable state service returns a bounded `503`; do not infer from a
network/server error that a previous target effect did not occur.

The exact action snapshot, pending approval, transition and evidence/outbox
insert commit together. Execution re-evaluates the current configured policy;
a changed policy binding invalidates old approval. PostgreSQL consumes and
claims single-use authority before simulator dispatch. Restarting the API
does not reset that database state, but simulator resources remain in memory.

The trusted composition calls `SimulatorApprovalServer.start` with its
verifier, memberships, PostgreSQL state, policy, permit authority, simulator
and clock. The integration test is the current executable composition; no
general-purpose operator bootstrap, browser login or React inbox is supplied
yet. Do not expose it through a tunnel/public proxy as a production service.

See [ADR 0034](../architecture/0034-authenticated-loopback-approval-api.md)
for enrollment, token, operational snapshot and remaining product boundaries.
