-- Storage compatibility only. No public cancellation route or requester authority is added.
-- Preserve V1-V9 unchanged. PostgreSQL/Flyway applies this migration transactionally.
LOCK TABLE tp_recovery_quarantine IN ACCESS EXCLUSIVE MODE;
LOCK TABLE tp_execution_attempts, tp_approval_requests, tp_approval_transitions IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
  IF (SELECT mode FROM tp_recovery_quarantine WHERE singleton = TRUE) IS DISTINCT FROM 'ready' THEN
    RAISE EXCEPTION 'DATABASE_RECOVERY_QUARANTINED' USING ERRCODE = '55000';
  END IF;
  IF EXISTS (SELECT 1 FROM tp_execution_attempts WHERE status IN ('executing', 'unknown')) THEN
    RAISE EXCEPTION 'DATABASE_UPGRADE_UNRESOLVED_EXECUTION' USING ERRCODE = '55000';
  END IF;
END;
$$;

ALTER TABLE tp_approval_requests DROP CONSTRAINT tp_approval_requests_status_check;
ALTER TABLE tp_approval_requests ADD CONSTRAINT tp_approval_requests_status_check
  CHECK (status IN ('pending', 'approved', 'denied', 'expired', 'consumed', 'cancelled'));
ALTER TABLE tp_approval_requests DROP CONSTRAINT tp_approval_requests_check3;
ALTER TABLE tp_approval_requests ADD CONSTRAINT tp_approval_requests_check3 CHECK (
  (status IN ('pending', 'cancelled') AND approval_decision_id IS NULL
    AND reviewer_subject_id IS NULL AND reviewer_authentication_method IS NULL
    AND reviewer_credential_fingerprint IS NULL AND decision_reason_code IS NULL AND decided_at IS NULL
    AND consumed_permit_id IS NULL AND consumed_attempt_id IS NULL)
  OR (status IN ('approved', 'denied') AND approval_decision_id IS NOT NULL
    AND consumed_permit_id IS NULL AND consumed_attempt_id IS NULL)
  OR (status = 'expired' AND consumed_permit_id IS NULL AND consumed_attempt_id IS NULL)
  OR (status = 'consumed' AND approval_decision_id IS NOT NULL
    AND consumed_permit_id IS NOT NULL AND consumed_attempt_id IS NOT NULL)
);

ALTER TABLE tp_approval_transitions
  ADD COLUMN cancellation_subject_id VARCHAR(256),
  ADD COLUMN cancellation_authentication_method VARCHAR(128),
  ADD COLUMN cancellation_credential_fingerprint CHAR(71),
  ADD COLUMN cancellation_authority_expires_at TIMESTAMPTZ;
ALTER TABLE tp_approval_transitions DROP CONSTRAINT tp_approval_transitions_check;
ALTER TABLE tp_approval_transitions ADD CONSTRAINT tp_approval_transitions_check CHECK (
  (sequence = 1 AND from_status IS NULL AND to_status = 'pending')
  OR (from_status = 'pending' AND to_status IN ('approved', 'denied', 'expired', 'cancelled'))
  OR (from_status = 'approved' AND to_status IN ('consumed', 'expired'))
);
ALTER TABLE tp_approval_transitions DROP CONSTRAINT tp_approval_transitions_check1;
ALTER TABLE tp_approval_transitions ADD CONSTRAINT tp_approval_transitions_check1 CHECK (
  (to_status IN ('approved', 'denied') AND approval_decision_id IS NOT NULL
    AND reviewer_subject_id IS NOT NULL AND reviewer_authentication_method IS NOT NULL
    AND reviewer_credential_fingerprint IS NOT NULL AND permit_id IS NULL AND attempt_id IS NULL)
  OR (to_status = 'consumed' AND approval_decision_id IS NULL AND reviewer_subject_id IS NULL
    AND reviewer_authentication_method IS NULL AND reviewer_credential_fingerprint IS NULL
    AND permit_id IS NOT NULL AND attempt_id IS NOT NULL)
  OR (to_status IN ('pending', 'expired', 'cancelled') AND approval_decision_id IS NULL
    AND reviewer_subject_id IS NULL AND reviewer_authentication_method IS NULL
    AND reviewer_credential_fingerprint IS NULL AND permit_id IS NULL AND attempt_id IS NULL)
);
ALTER TABLE tp_approval_transitions ADD CONSTRAINT tp_cancellation_actor_check CHECK (
  (to_status <> 'cancelled' AND cancellation_subject_id IS NULL
    AND cancellation_authentication_method IS NULL AND cancellation_credential_fingerprint IS NULL
    AND cancellation_authority_expires_at IS NULL)
  OR (to_status = 'cancelled' AND reason_code = 'REQUESTER_CANCELLED'
    AND cancellation_subject_id IS NOT NULL
    AND cancellation_subject_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-#]{0,255}$'
    AND cancellation_authentication_method IS NOT NULL
    AND cancellation_authentication_method ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,127}$'
    AND cancellation_credential_fingerprint IS NOT NULL
    AND cancellation_credential_fingerprint ~ '^sha256:[0-9a-f]{64}$'
    AND cancellation_authority_expires_at IS NOT NULL
    AND cancellation_authority_expires_at > occurred_at)
);

CREATE FUNCTION tp_validate_approval_cancellation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE requester TEXT; request_status TEXT; request_expiry TIMESTAMPTZ;
BEGIN
  IF NEW.to_status = 'cancelled' THEN
    SELECT subject_id, status, expires_at INTO requester, request_status, request_expiry
      FROM tp_approval_requests
      WHERE tenant_id = NEW.tenant_id AND approval_request_id = NEW.approval_request_id FOR UPDATE;
    IF NOT FOUND OR request_status <> 'pending'
        OR NEW.cancellation_subject_id IS DISTINCT FROM requester
        OR NEW.occurred_at >= request_expiry THEN
      RAISE EXCEPTION 'APPROVAL_NOT_CANCELLABLE' USING ERRCODE = '55000';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
-- Recovery guard first, then cancellation identity, then the existing aggregate transition guard.
CREATE TRIGGER tp_10_approval_cancellation_guard BEFORE INSERT ON tp_approval_transitions
FOR EACH ROW EXECUTE FUNCTION tp_validate_approval_cancellation();

ALTER TABLE tp_enforcement_schema_guard
  DROP CONSTRAINT tp_enforcement_schema_guard_contract_version_check;
UPDATE tp_enforcement_schema_guard SET contract_version = 10 WHERE singleton = TRUE;
ALTER TABLE tp_enforcement_schema_guard
  ADD CONSTRAINT tp_enforcement_schema_guard_contract_version_check CHECK (contract_version = 10);
