# ADR 0059 — Explicit V9-to-V10 simulator database upgrade

**Status: Implemented storage/upgrade foundation; authenticated withdrawal remains proposed.**

Subsequent [ADR 0060](0060-authenticated-pending-request-withdrawal.md) implements
authenticated Java pending withdrawal. This ADR retains its original upgrade-only
scope; it is not the evidence for that later service/API or a requester button.

Requirements: FR-007/008/009/010; SEC-003/005/006/009/012;
REL-001/002/004/005; DATA-001; OPS-002/004; PORT-001/003.

## Decision

Implement only the schema/consumer prerequisite of
[ADR 0058](0058-pending-approval-cancellation-contract.md). Operator startup
requires V1–V10. Empty-only `--init-db` installs the packaged schema; `--run`
still never migrates. A separate local `--upgrade-db` accepts exactly V9→V10
using table-owner or inherited-owner authority, not ordinary runtime grants.
It starts no listener, identity lookup, key enrollment, permit or simulator.

The owner stops all clients/services and verifies a private backup beforehand.
The command takes the same database-local session advisory lock as initialization,
validates public schema ownership/table set, successful V1–V9 history/checksums,
guard 9 and enabled invariant triggers. Only this predecessor validation resolves
packaged V10 above an explicit Flyway target9; ordinary startup has no ignored
pending/future migrations. V1–V9 resources remain unchanged.

V10 locks the recovery row's table first, then execution/approval tables, checks
ready mode and refuses executing/unknown attempts. Its transactional PostgreSQL
DDL adds terminal `cancelled`, separate requester/authentication/fingerprint/
authority-deadline transition metadata, and an owner/pending/request-expiry
trigger. It preserves append-only and direct-update guards, sequence/time
monotonicity and reviewer separation. Only pending→cancelled is legal; cancelled
cannot approve or consume. No existing row is deleted or rewritten except guard 9→10.

Migration DDL, guard 10 and successful Flyway V10 history commit together.
Final strict validation precedes the fixed success message. Failed migration
rolls back its transaction; a failure after commit is not falsely reported as
success and does not automatically reverse the committed schema. The operator
must inspect matching binaries/database privately, not blindly retry or restore.
The advisory lease is explicitly unlocked even if a future pool retains its
physical connection. Initialization and upgrade fail fast on a cooperating holder.

Consumers accept the stored status: Java state/history and the React parser
recognize `cancelled`, without offering review/execution for it. Owner recovery
inspection adds a cancelled count and changes its **report format to 2.0**;
this is distinct from database contract 10 and unchanged approval API format 1.0.

## Boundaries and alternatives

- No application cancellation method, `approvals.cancel` enrollment, HTTP/browser
  route, MCP tool or Withdraw button exists yet. The database structural guard
  is not an authenticator: DB owners/DML holders can supply SQL provenance.
  Authenticated cancellation, clock sampling after locks and atomic lifecycle
  evidence/outbox must be implemented and tested in a later service slice.
- Session advisory locking coordinates cooperating installers, **not online
  schema migration, a global service drain or protection from a malicious DBA**.
  Metadata/history/trigger-name validation does not attest function bodies or
  externally anchor database authenticity. Do not bypass failed checks.
- Old binaries must refuse guard 10, not omit new states. No automatic startup
  upgrade, history repair/baseline/clean, general migration endpoint or in-place
  downgrade is added. Unsupported predecessors remain offline.
- Recovery quarantine is not used as a temporary migration mutex. Quarantined
  state cannot upgrade. Installing a schema is not recovery authorization: an
  older ready backup can still resurrect authority. No resume command is added.
- Statement/lock timeouts and finite connection budgets limit individual waits,
  not total runtime, online availability or a production SLO.
- Trusted development constructors still migrate their disposable schemas;
  the strict operator profile remains explicitly separate.

## Qualification

`PostgresDatabaseUpgradeTest` uses genuine Flyway predecessor histories and
PostgreSQL16: exact row/history preservation; unsupported/empty/modified/future
state; owner/RLS/trigger/schema failures; quarantine/unresolved execution;
competing/retained leases; DDL lock timeout/rollback; post-commit validation
acknowledgement loss; and SQL cancellation provenance/terminal constraints.
Its synthetic SQL content-preservation fixture is not an authenticated action
or cryptographic evidence-chain qualification.

`SimulatorApprovalBootstrapTest` proves command dispatch never enters the
service runner or creates claims. The installed-distribution test generates a
genuine V9 fixture using JDK-only **test setup**, then invokes the source-free
launcher with source tools absent from PATH: V9 run/init refusal, upgrade,
repeat refusal and format 2 inspection with zero claims/permits. Ordinary
authenticated review/execute/replay and restore regressions remain controls.

Operator steps and uncertainty handling:
[distribution runbook](../runbooks/simulator-approval-distribution.md#explicit-stopped-service-upgrade-v9-to-v10).
Flyway behavior is checked against the pinned11.8.2 implementation; upstream
[validation](https://documentation.red-gate.com/flyway/reference/commands/validate)
and [target documentation](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-target-setting)
describe the mechanisms, not an independently qualified release claim.
