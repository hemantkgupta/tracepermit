ALTER TABLE tp_enforcement_schema_guard
  DROP CONSTRAINT tp_enforcement_schema_guard_contract_version_check;
UPDATE tp_enforcement_schema_guard SET contract_version = 9 WHERE singleton = TRUE;
ALTER TABLE tp_enforcement_schema_guard
  ADD CONSTRAINT tp_enforcement_schema_guard_contract_version_check CHECK (contract_version = 9);

-- Installation-wide, explicitly declared containment. This row is NOT a rollback witness:
-- restoring an earlier valid database can restore 'ready' as well.
CREATE TABLE tp_recovery_quarantine (
  singleton BOOLEAN PRIMARY KEY CHECK (singleton = TRUE),
  mode VARCHAR(16) NOT NULL CHECK (mode IN ('ready', 'quarantined')),
  recovery_id UUID,
  quarantined_at TIMESTAMPTZ,
  CHECK ((mode = 'ready' AND recovery_id IS NULL AND quarantined_at IS NULL)
      OR (mode = 'quarantined' AND recovery_id IS NOT NULL AND quarantined_at IS NOT NULL))
);
INSERT INTO tp_recovery_quarantine(singleton, mode) VALUES (TRUE, 'ready');

CREATE FUNCTION tp_guard_recovery_quarantine() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP <> 'UPDATE' THEN
    RAISE EXCEPTION 'RECOVERY_QUARANTINE_IMMUTABLE' USING ERRCODE = '55000';
  END IF;
  IF NOT pg_catalog.pg_has_role(current_user,
      (SELECT relowner FROM pg_catalog.pg_class WHERE oid = TG_RELID), 'USAGE') THEN
    RAISE EXCEPTION 'RECOVERY_OPERATOR_REQUIRED' USING ERRCODE = '42501';
  END IF;
  IF OLD.mode <> 'ready' OR NEW.mode <> 'quarantined' OR NEW.singleton <> OLD.singleton THEN
    RAISE EXCEPTION 'RECOVERY_QUARANTINE_IMMUTABLE' USING ERRCODE = '55000';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER tp_recovery_quarantine_guard BEFORE INSERT OR UPDATE OR DELETE
ON tp_recovery_quarantine FOR EACH ROW EXECUTE FUNCTION tp_guard_recovery_quarantine();
CREATE TRIGGER tp_recovery_quarantine_no_truncate BEFORE TRUNCATE
ON tp_recovery_quarantine FOR EACH STATEMENT EXECUTE FUNCTION tp_guard_recovery_quarantine();

-- Shared row locks last through commit. Quarantine takes the conflicting UPDATE lock.
-- Completion, reconciliation and evidence publication deliberately have no such gate.
CREATE FUNCTION tp_require_recovery_ready() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE current_mode TEXT;
BEGIN
  EXECUTE pg_catalog.format('SELECT mode FROM %I.tp_recovery_quarantine WHERE singleton = TRUE FOR SHARE', TG_TABLE_SCHEMA)
    INTO current_mode;
  IF current_mode IS DISTINCT FROM 'ready' THEN
    RAISE EXCEPTION 'DATABASE_RECOVERY_QUARANTINED' USING ERRCODE = '55000';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER tp_00_recovery_permit_guard BEFORE INSERT ON tp_consumed_execution_permits
FOR EACH ROW EXECUTE FUNCTION tp_require_recovery_ready();
CREATE TRIGGER tp_00_recovery_attempt_guard BEFORE INSERT ON tp_execution_attempts
FOR EACH ROW EXECUTE FUNCTION tp_require_recovery_ready();
CREATE TRIGGER tp_00_recovery_request_guard BEFORE INSERT ON tp_approval_requests
FOR EACH ROW EXECUTE FUNCTION tp_require_recovery_ready();
CREATE TRIGGER tp_00_recovery_review_guard BEFORE INSERT ON tp_approval_transitions
FOR EACH ROW EXECUTE FUNCTION tp_require_recovery_ready();
