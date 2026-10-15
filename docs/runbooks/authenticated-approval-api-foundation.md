# Authenticated approval API foundation

This is a local simulator-only Java API composition, not a production product
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

It also exercises the operator bootstrap and a real child JVM: strict private
configuration, offline validation with unreachable dependencies, signed HTTP
requests, durable restart/replay behavior, DB failure before listening and
SIGTERM shutdown. All identities and credentials in these tests are synthetic.

## Operator setup (macOS/Linux only)

This optional path requires an operator-controlled local PostgreSQL database
and a compatible external IdP registration. The checked-in
[`config/simulator-approvals.example.json`](../../config/simulator-approvals.example.json)
contains placeholders, **not working login credentials or a qualified IdP**.
Use the automated qualification above if those dependencies are not configured.

Prepare a private directory outside source control (or under ignored `.cache/`).
The following generates local **simulator-only** keys; it does not create a
database or configure an IdP:

```bash
umask 077
mkdir -p .cache/local-approvals
chmod 700 .cache/local-approvals
cp config/simulator-approvals.example.json .cache/local-approvals/operator.json
chmod 600 .cache/local-approvals/operator.json
openssl genpkey -algorithm ED25519 -outform DER -out .cache/local-approvals/permit.pk8
openssl pkey -inform DER -in .cache/local-approvals/permit.pk8 -pubout -outform DER -out .cache/local-approvals/permit.spki
```

Generate these keys only for a new local setup. **Do not overwrite existing keys
on a restart.** Use a private editor to create `database.secret`, containing the
dedicated local database role's password (at least 16 non-control characters;
one terminal LF is allowed), and to update `operator.json`. Do not embed a password
in JSON, command history, Git or public support reports. Never reuse real-target
credentials. Use current-user-owned regular files with mode `600` or `400` for
the JSON, password and both keys. Protect their directory and all ancestors;
symlinks in ancestors/same-UID tampering are not a qualified defense boundary.

All JSON fields are required; extras and duplicate keys fail. Relative file
references resolve from the configuration file's directory, not the shell's
directory. Server and PostgreSQL host are fixed to `127.0.0.1`; choose valid
nonzero ports. The local DB connection uses no TLS and has 3-second connect and
5-second socket timeouts; secure its PostgreSQL authentication/bind separately.
The role needs migration rights in its dedicated database/schema. Do not point
this composition at a shared or production database.

Replace the `.invalid` issuer/audience/JWKS, client and tenant mapping with
operator-approved IdP configuration. Membership `subject` is the exact verified
`sub`, not email/display name; `human` and roles are independent enrollment facts.
Roles are `REQUESTER`, `REVIEWER`, `VIEWER`; non-human reviewers and a setup lacking
a distinct requester/reviewer are rejected. The API requires access-token scope
`approvals.request`, `approvals.read`, `approvals.decide` or `approvals.execute`
for the corresponding route, not token-provided role claims. The existing
verifier requires RS256, `typ: at+jwt`, `kid`, issuer, audience, client, configured
tenant claim, scopes and `iat`/`exp` within its 15-minute lifetime bound. ID tokens,
opaque access tokens and sender-constrained tokens are not accepted here.
Only the four `approvals.*` scopes listed above are allowlisted by default;
the optional browser profile additionally allows `openid`. Other IdP-added
scope names are not silently accepted. Optional browser sign-in requires the
separate configuration and qualification described in the
[browser inbox runbook](browser-approval-inbox.md). This service never issues
provider tokens itself.

```bash
export TRACEPERMIT_APPROVAL_CONFIG="$PWD/.cache/local-approvals/operator.json"
pnpm dev:approvals --check
pnpm dev:approvals
```

`--check` is entirely offline: it checks schema, files, enrollment and key pair,
**not** DB connectivity, IdP compatibility/JWKS availability or port availability.
It can pass for the `.invalid` placeholders; that does not mean login will work.
`--run` (the default launcher mode) validates before database migration, then
opens only the approval listener, by default sample port 8085. Use `Ctrl+C` to stop.
The fixed policy `pol-local-simulator-approval` v2 requires exact review for
`aws.ec2.update_security_group` from API/CLI and blocks unmatched actions.
There is no public health endpoint or development token route. Optional browser
configuration adds the [React approval inbox](browser-approval-inbox.md);
authenticated [local MCP request/status/resume](codex-durable-approvals.md)
shares this durable service. These are not the default demo console backend.
Previously pending/approved v1 records cannot execute under v2: request fresh
review of the current policy rather than rewriting the stored approval.

Startup errors return code 2 with `APPROVAL_STARTUP_FAILED`, without raw exception
details. Check file paths/ownership/permissions, configuration fields, key formats,
DB availability/schema and port conflicts privately. Never enable driver/debug
credential logging. A successful startup is not evidence of IdP readiness: the
first token requiring an uncached JWKS key triggers bounded HTTPS verification.

Back up the database before startup: Flyway applies migrations through V6 and
checks the schema guard. Migrations may commit even if the later port bind fails.
Do not downgrade an older binary against this schema. Membership/key configuration
is a startup snapshot; use a controlled stop/edit/restart for changes. Protect
the persistent keys and DB together. This is not managed key custody/rotation,
immediate IdP revocation or a backup/restore qualification.

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
and clock. `SimulatorApprovalMain` now supplies a narrow operator-configured
local entry point; browser login, React inbox and production bootstrap remain
absent. Do not expose it through a tunnel/public proxy as a production service.

See [ADR 0034](../architecture/0034-authenticated-loopback-approval-api.md)
for enrollment, token, operational snapshot and remaining product boundaries.
See [ADR 0035](../architecture/0035-local-approval-operator-bootstrap.md) for
file custody, configuration validation and startup/shutdown constraints.
