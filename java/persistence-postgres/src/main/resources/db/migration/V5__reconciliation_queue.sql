ALTER TABLE tp_enforcement_schema_guard
  DROP CONSTRAINT tp_enforcement_schema_guard_contract_version_check;

UPDATE tp_enforcement_schema_guard SET contract_version = 5 WHERE singleton = TRUE;

ALTER TABLE tp_enforcement_schema_guard
  ADD CONSTRAINT tp_enforcement_schema_guard_contract_version_check
  CHECK (contract_version = 5);

CREATE TABLE tp_reconciliation_queue (
  tenant_id VARCHAR(128) NOT NULL,
  attempt_id VARCHAR(256) NOT NULL,
  adapter_id VARCHAR(128) NOT NULL,
  idempotency_key CHAR(71) NOT NULL CHECK (idempotency_key ~ '^sha256:[0-9a-f]{64}$'),
  unknown_since TIMESTAMPTZ NOT NULL,
  next_attempt_at TIMESTAMPTZ NOT NULL,
  lookup_attempts INTEGER NOT NULL DEFAULT 0 CHECK (lookup_attempts >= 0),
  lease_owner VARCHAR(128)
    CHECK (lease_owner IS NULL OR lease_owner ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,127}$'),
  lease_expires_at TIMESTAMPTZ,
  last_reason_code VARCHAR(128)
    CHECK (last_reason_code IS NULL OR last_reason_code ~ '^[A-Z][A-Z0-9_]{0,127}$'),
  PRIMARY KEY (tenant_id, attempt_id),
  UNIQUE (tenant_id, adapter_id, idempotency_key),
  FOREIGN KEY (tenant_id, attempt_id)
    REFERENCES tp_execution_attempts (tenant_id, attempt_id),
  CHECK (next_attempt_at >= unknown_since),
  CHECK ((lease_owner IS NULL) = (lease_expires_at IS NULL)),
  CHECK (lease_expires_at IS NULL OR lease_expires_at > unknown_since)
);

INSERT INTO tp_reconciliation_queue
  (tenant_id, attempt_id, adapter_id, idempotency_key, unknown_since,
   next_attempt_at)
SELECT tenant_id, attempt_id, adapter_id, idempotency_key, updated_at, updated_at
  FROM tp_execution_attempts
 WHERE status = 'unknown';

CREATE INDEX tp_reconciliation_queue_ready_idx
  ON tp_reconciliation_queue (tenant_id, next_attempt_at, unknown_since, attempt_id);

CREATE FUNCTION tp_validate_reconciliation_queue() RETURNS TRIGGER AS $$
DECLARE
  attempt_status VARCHAR(32);
  stored_adapter VARCHAR(128);
  stored_key CHAR(71);
BEGIN
  SELECT status, adapter_id, idempotency_key
    INTO attempt_status, stored_adapter, stored_key
    FROM tp_execution_attempts
   WHERE tenant_id = NEW.tenant_id AND attempt_id = NEW.attempt_id;

  IF NOT FOUND OR attempt_status <> 'unknown' OR
     stored_adapter <> NEW.adapter_id OR stored_key <> NEW.idempotency_key THEN
    RAISE EXCEPTION 'reconciliation queue requires an exact unknown attempt';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tp_reconciliation_queue_guard
BEFORE INSERT OR UPDATE ON tp_reconciliation_queue
FOR EACH ROW EXECUTE FUNCTION tp_validate_reconciliation_queue();
