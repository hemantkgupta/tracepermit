# Operator-declared recovery quarantine

Use this one-way containment command only for a dedicated **simulator** database.
It stops new durable claims, pending approvals and reviews across cooperating
replicas. It does not cancel previously admitted work, detect an old backup,
revoke a signer, or provide a way to resume.

## Stop and inspect first

1. Stop agents and effectful services before a planned restore; keep them
   disconnected after any restore or restore failure. Preserve the database,
   archive and private incident records. Never run `--init-db` on recovered state.
2. Use private operator configuration pointing to the exact intended database.
   Do not paste credentials into commands or logs. `--check` only validates local
   files and is not a database/recovery check.
3. Use the matching V9 distribution. `--recovery-status` validates packaged
   schema/history/trigger metadata and reads the current mode:

   ```bash
   /path/to/distribution/bin/tracepermit-simulator-approval --recovery-status
   ```

   It emits `APPROVAL_RECOVERY_MODE: ready` or `quarantined` with a bounded caveat.
   **Ready is not recovery-success evidence.** A restored older ready row is
   indistinguishable without an independent witness. Missing, incompatible or
   incomplete state fails closed without migration or repair.

## Declare containment

With a separate private config using the quarantine table owner's authority:

```bash
/path/to/distribution/bin/tracepermit-simulator-approval --quarantine-db
```

From a source checkout, the corresponding commands are:

```bash
pnpm dev:approvals --recovery-status
pnpm dev:approvals --quarantine-db
```

`TRACEPERMIT_APPROVAL_CONFIG` must already name that private configuration. No
command creates credentials, databases or roles. The command locks the guard,
waits for previously admitted database transactions, commits the one-way record,
then emits `APPROVAL_DATABASE_QUARANTINED`. It starts no listener/IdP/target.
Do not assume an error means rollback: commit acknowledgement may be lost.
Inspect status when connectivity returns; repeating the command preserves the
first committed record. Never clear the row, disable triggers or edit history.

Both database commands still require the complete private operator configuration,
including valid local key files. Missing/damaged files fail before the database
operation; there is no force flag. Keep agents/services stopped if that validation
or database access fails rather than assuming containment succeeded.

A non-owner runtime role can inspect the state but cannot declare it, even if
granted ordinary DML. Runtime claims need SELECT plus UPDATE row-lock privilege
on `public.tp_recovery_quarantine`; those privileges do not bypass the owner-only
transition trigger. Keep owner credentials out of effectful runtime configuration.
Role provisioning remains operator-owned, not a production least-privilege claim.

## What remains possible

- A service already running may continue authorized reads. New claim/review writes
  fail closed; approval HTTP/MCP uses its existing generic
  `SECURITY_STATE_UNAVAILABLE` response (HTTP 503).
- A claim that committed before quarantine may still invoke/finish its simulator
  afterward. Existing completion, evidence/outbox and lookup-only reconciliation
  operations remain usable, under their original authorization/transition rules.
- A subsequent normal `--run` against an already quarantined database refuses
  startup. Concurrent quarantine after a startup check may leave a listener open,
  but database authority writes are still gated.
- The row records mode, recovery UUID and declaration time. It is not an
  independently anchored audit trail or proof that no prior effect occurred.

There is deliberately **no resume or in-place schema-upgrade command**. V8 and
incomplete installations remain disconnected for separate offline recovery/
upgrade work. Do not replace their state with a fresh installation. Do not
classify executing/unknown attempts as failed or release their authority merely
because they are old. Further recovery must reconcile bound target observations
and define invalidation/re-enrollment before any effectful resume.

## Qualification and remaining gap

Run `pnpm check`, `pnpm test:distribution`, and `pnpm format:check` with Java21
and Docker. `PostgresRecoveryQuarantineTest` exercises actual database races and
SQL bypass prevention; bootstrap and packaged-launcher tests cover commands and
live containment. All targets are simulated. Existing backup/restore tests still
characterize old-snapshot authority revival: quarantine is not a rollback fence.
See [ADR 0051](../architecture/0051-operator-declared-recovery-quarantine.md).
