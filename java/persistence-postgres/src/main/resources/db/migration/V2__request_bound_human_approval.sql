ALTER TABLE tp_enforcement_schema_guard
  DROP CONSTRAINT tp_enforcement_schema_guard_contract_version_check;

UPDATE tp_enforcement_schema_guard SET contract_version = 2 WHERE singleton = TRUE;

ALTER TABLE tp_enforcement_schema_guard
  ADD CONSTRAINT tp_enforcement_schema_guard_contract_version_check
  CHECK (contract_version = 2);

CREATE TABLE tp_approval_requests (
  tenant_id VARCHAR(128) NOT NULL CHECK (tenant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
  approval_request_id VARCHAR(256) NOT NULL CHECK (approval_request_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  request_hash CHAR(71) NOT NULL CHECK (request_hash ~ '^sha256:[0-9a-f]{64}$'),
  action_hash CHAR(71) NOT NULL CHECK (action_hash ~ '^sha256:[0-9a-f]{64}$'),
  event_id VARCHAR(256) NOT NULL CHECK (event_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  evaluation_id VARCHAR(256) NOT NULL CHECK (evaluation_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  subject_id VARCHAR(256) NOT NULL CHECK (subject_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  actor_lineage_hash CHAR(71) NOT NULL CHECK (actor_lineage_hash ~ '^sha256:[0-9a-f]{64}$'),
  policy_id VARCHAR(256) NOT NULL CHECK (policy_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  policy_version INTEGER NOT NULL CHECK (policy_version > 0),
  original_decision VARCHAR(32) NOT NULL CHECK (original_decision = 'require_approval'),
  tool_name VARCHAR(256) NOT NULL CHECK (tool_name ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  resource_hash CHAR(71) NOT NULL CHECK (resource_hash ~ '^sha256:[0-9a-f]{64}$'),
  approval_group VARCHAR(128) NOT NULL CHECK (approval_group ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,127}$'),
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
  max_uses INTEGER NOT NULL CHECK (max_uses = 1),
  status VARCHAR(32) NOT NULL CHECK (status IN ('pending', 'approved', 'denied', 'expired', 'consumed')),
  transition_sequence INTEGER NOT NULL CHECK (transition_sequence > 0),
  approval_decision_id VARCHAR(256),
  reviewer_subject_id VARCHAR(256),
  reviewer_authentication_method VARCHAR(128),
  reviewer_credential_fingerprint CHAR(71),
  decision_reason_code VARCHAR(128),
  decided_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL CHECK (updated_at >= created_at),
  consumed_permit_id VARCHAR(256),
  consumed_attempt_id VARCHAR(256),
  PRIMARY KEY (tenant_id, approval_request_id),
  UNIQUE (tenant_id, request_hash),
  UNIQUE (tenant_id, event_id, evaluation_id),
  FOREIGN KEY (tenant_id, consumed_permit_id)
    REFERENCES tp_consumed_execution_permits (tenant_id, permit_id),
  FOREIGN KEY (tenant_id, consumed_attempt_id)
    REFERENCES tp_execution_attempts (tenant_id, attempt_id),
  CHECK (
    (approval_decision_id IS NULL AND reviewer_subject_id IS NULL
      AND reviewer_authentication_method IS NULL
      AND reviewer_credential_fingerprint IS NULL
      AND decision_reason_code IS NULL AND decided_at IS NULL)
    OR
    (approval_decision_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'
      AND reviewer_subject_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'
      AND reviewer_authentication_method ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,127}$'
      AND reviewer_credential_fingerprint ~ '^sha256:[0-9a-f]{64}$'
      AND decision_reason_code ~ '^[A-Z][A-Z0-9_]{0,127}$'
      AND decided_at IS NOT NULL)
  ),
  CHECK (
    (status = 'pending' AND approval_decision_id IS NULL AND consumed_permit_id IS NULL AND consumed_attempt_id IS NULL)
    OR (status IN ('approved', 'denied') AND approval_decision_id IS NOT NULL AND consumed_permit_id IS NULL AND consumed_attempt_id IS NULL)
    OR (status = 'expired' AND consumed_permit_id IS NULL AND consumed_attempt_id IS NULL)
    OR (status = 'consumed' AND approval_decision_id IS NOT NULL AND consumed_permit_id IS NOT NULL AND consumed_attempt_id IS NOT NULL)
  )
);

CREATE TABLE tp_approval_transitions (
  tenant_id VARCHAR(128) NOT NULL,
  approval_request_id VARCHAR(256) NOT NULL,
  sequence INTEGER NOT NULL CHECK (sequence > 0),
  from_status VARCHAR(32),
  to_status VARCHAR(32) NOT NULL,
  approval_decision_id VARCHAR(256),
  reviewer_subject_id VARCHAR(256),
  reviewer_authentication_method VARCHAR(128),
  reviewer_credential_fingerprint CHAR(71),
  reason_code VARCHAR(128) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,127}$'),
  occurred_at TIMESTAMPTZ NOT NULL,
  permit_id VARCHAR(256),
  attempt_id VARCHAR(256),
  PRIMARY KEY (tenant_id, approval_request_id, sequence),
  FOREIGN KEY (tenant_id, approval_request_id)
    REFERENCES tp_approval_requests (tenant_id, approval_request_id),
  FOREIGN KEY (tenant_id, permit_id)
    REFERENCES tp_consumed_execution_permits (tenant_id, permit_id),
  FOREIGN KEY (tenant_id, attempt_id)
    REFERENCES tp_execution_attempts (tenant_id, attempt_id),
  CHECK (
    (sequence = 1 AND from_status IS NULL AND to_status = 'pending') OR
    (from_status = 'pending' AND to_status IN ('approved', 'denied', 'expired')) OR
    (from_status = 'approved' AND to_status IN ('consumed', 'expired'))
  ),
  CHECK (
    (to_status IN ('approved', 'denied')
      AND approval_decision_id IS NOT NULL
      AND reviewer_subject_id IS NOT NULL
      AND reviewer_authentication_method IS NOT NULL
      AND reviewer_credential_fingerprint IS NOT NULL
      AND permit_id IS NULL AND attempt_id IS NULL)
    OR
    (to_status = 'consumed' AND approval_decision_id IS NULL
      AND reviewer_subject_id IS NULL
      AND reviewer_authentication_method IS NULL
      AND reviewer_credential_fingerprint IS NULL
      AND permit_id IS NOT NULL AND attempt_id IS NOT NULL)
    OR
    (to_status IN ('pending', 'expired') AND approval_decision_id IS NULL
      AND reviewer_subject_id IS NULL
      AND reviewer_authentication_method IS NULL
      AND reviewer_credential_fingerprint IS NULL
      AND permit_id IS NULL AND attempt_id IS NULL)
  )
);

CREATE FUNCTION tp_validate_approval_transition() RETURNS TRIGGER AS $$
DECLARE
  current_status VARCHAR(32);
  current_sequence INTEGER;
  last_occurred_at TIMESTAMPTZ;
BEGIN
  SELECT status, transition_sequence
    INTO current_status, current_sequence
    FROM tp_approval_requests
   WHERE tenant_id = NEW.tenant_id AND approval_request_id = NEW.approval_request_id
   FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'approval request does not exist';
  END IF;

  IF NEW.sequence = 1 THEN
    IF current_status <> 'pending' OR current_sequence <> 1 OR NEW.from_status IS NOT NULL OR NEW.to_status <> 'pending' THEN
      RAISE EXCEPTION 'invalid initial approval transition';
    END IF;
    RETURN NEW;
  END IF;

  SELECT occurred_at INTO last_occurred_at
    FROM tp_approval_transitions
   WHERE tenant_id = NEW.tenant_id
     AND approval_request_id = NEW.approval_request_id
     AND sequence = current_sequence;

  IF NEW.sequence <> current_sequence + 1
      OR NEW.from_status <> current_status
      OR NEW.occurred_at < last_occurred_at THEN
    RAISE EXCEPTION 'non-monotonic approval transition';
  END IF;

  UPDATE tp_approval_requests
     SET status = NEW.to_status,
         transition_sequence = NEW.sequence,
         approval_decision_id = COALESCE(NEW.approval_decision_id, approval_decision_id),
         reviewer_subject_id = COALESCE(NEW.reviewer_subject_id, reviewer_subject_id),
         reviewer_authentication_method = COALESCE(NEW.reviewer_authentication_method, reviewer_authentication_method),
         reviewer_credential_fingerprint = COALESCE(NEW.reviewer_credential_fingerprint, reviewer_credential_fingerprint),
         decision_reason_code = CASE
           WHEN NEW.to_status IN ('approved', 'denied') THEN NEW.reason_code
           ELSE decision_reason_code
         END,
         decided_at = COALESCE(decided_at, CASE
           WHEN NEW.to_status IN ('approved', 'denied') THEN NEW.occurred_at
           ELSE NULL
         END),
         updated_at = NEW.occurred_at,
         consumed_permit_id = COALESCE(NEW.permit_id, consumed_permit_id),
         consumed_attempt_id = COALESCE(NEW.attempt_id, consumed_attempt_id)
   WHERE tenant_id = NEW.tenant_id AND approval_request_id = NEW.approval_request_id;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tp_approval_transition_guard
BEFORE INSERT ON tp_approval_transitions
FOR EACH ROW EXECUTE FUNCTION tp_validate_approval_transition();

CREATE FUNCTION tp_reject_direct_approval_mutation() RETURNS TRIGGER AS $$
BEGIN
  IF pg_trigger_depth() < 2 THEN
    RAISE EXCEPTION 'approval requests transition only through the transition ledger';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tp_approval_request_update_guard
BEFORE UPDATE ON tp_approval_requests
FOR EACH ROW EXECUTE FUNCTION tp_reject_direct_approval_mutation();

CREATE TRIGGER tp_approval_transition_append_only
BEFORE UPDATE OR DELETE ON tp_approval_transitions
FOR EACH ROW EXECUTE FUNCTION tp_reject_append_only_mutation();
