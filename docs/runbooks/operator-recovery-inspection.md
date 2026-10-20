# Inspect durable simulator recovery state

This read-only command reports stored state. **It is not a recovery-success,
quiescence, target-outcome or safe-resume check.** Use a dedicated simulator
database only.

1. Stop agents and effectful services before planned restore or recovery. Keep
   them disconnected after restore or failure. Preserve private incident records
   and the original archive/database; do not initialize or reset recovered state.
2. Supply `TRACEPERMIT_APPROVAL_CONFIG` through your private operator environment.
   It must identify the intended database and contain the complete valid local
   configuration/key files. Do not paste passwords, keys or configuration into
   a command or issue. Use table-owner or inherited owner credentials for this
   command, never effectful runtime configuration.
3. With a matching V10 distribution, run:

   ```bash
   /path/to/distribution/bin/tracepermit-simulator-approval --recovery-inspect
   ```

   From source, the equivalent is:

   ```bash
   pnpm dev:approvals --recovery-inspect
   ```

4. A successful command prints `APPROVAL_RECOVERY_SNAPSHOT: ` followed by one JSON
   object, then an `INSPECTION_ONLY` caveat. It starts no listener, IdP connection,
   target lookup or simulator dispatch. Fields:

   | Field                  | Meaning                                                                           |
   | ---------------------- | --------------------------------------------------------------------------------- |
   | `schemaVersion`        | Report format `2.0`, not database contract 10 or approval API format 1.0          |
   | `transactionStartedAt` | Database transaction start time; not an independently anchored witness            |
   | `recoveryMode`         | Stored `ready` or `quarantined` in the snapshot                                   |
   | `executions`           | Stored `executing`, `succeeded`, `failed`, `unknown` counts                       |
   | `approvals`            | Stored `pending`, `approved`, `denied`, `expired`, `consumed`, `cancelled` counts |
   | `reconciliationQueued` | All queued unknown rows, including leased/deferred rows; not a ready-work count   |

5. A failure returns exit 2 with `APPROVAL_STARTUP_FAILED` and no partial report.
   Check private configuration, ownership, connectivity and compatible metadata.
   Missing schema/history/triggers and unexpected RLS fail closed without repair.
   Ordinary runtime SELECT privileges are intentionally insufficient. Do not
   remove RLS, grant broad permissions, edit migration history or reset state just
   to make this diagnostic pass; investigate the installation mismatch offline.

Counts are installation-wide and contain no per-tenant/request identifiers or
payloads. Treat even aggregate operational reports as private. Counts do not
refresh expiry or validate evidence chains and target outcomes. A nonzero
`executing` count may include abandoned or already-effectful requests; zero does
not prove no previous effect. Never retry or release consumed authority based on
these counts. All reads share one snapshot but concurrent changes may commit
while the report is generated. Stop services independently if quiescence is
required; the command does not drain or cancel them.

The five-second SQL budgets and one-second lock budget can refuse a large/locked
scan; they are not an overall command deadline. Validation and database connection
checks run separately. This command performs no DDL or recovery transition.
Only the separate [stopped-service V9→V10 upgrade](simulator-approval-distribution.md#explicit-stopped-service-upgrade-v9-to-v10)
is supported; V8 and other predecessors remain offline. Cancelled storage
compatibility does not expose an authenticated withdrawal feature.

For explicit containment, see [operator quarantine](operator-recovery-quarantine.md).
Neither command detects old-snapshot rollback or provides safe resume.
Design and qualification: [ADR 0052](../architecture/0052-owner-only-recovery-inspection.md).
