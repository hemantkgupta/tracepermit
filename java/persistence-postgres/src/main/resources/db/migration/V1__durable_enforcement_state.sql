CREATE TABLE tp_enforcement_schema_guard (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
  contract_version INTEGER NOT NULL CHECK (contract_version = 1)
);

INSERT INTO tp_enforcement_schema_guard (singleton, contract_version) VALUES (TRUE, 1);

CREATE TABLE tp_consumed_execution_permits (
  tenant_id VARCHAR(128) NOT NULL CHECK (tenant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
  permit_id VARCHAR(256) NOT NULL CHECK (permit_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  expires_at TIMESTAMPTZ NOT NULL,
  action_hash CHAR(71) NOT NULL CHECK (action_hash ~ '^sha256:[0-9a-f]{64}$'),
  claimed_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, permit_id)
);

CREATE TABLE tp_execution_attempts (
  tenant_id VARCHAR(128) NOT NULL,
  attempt_id VARCHAR(256) NOT NULL CHECK (attempt_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  permit_id VARCHAR(256) NOT NULL,
  adapter_id VARCHAR(128) NOT NULL CHECK (adapter_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,127}$'),
  idempotency_key CHAR(71) NOT NULL CHECK (idempotency_key ~ '^sha256:[0-9a-f]{64}$'),
  event_id VARCHAR(256) NOT NULL CHECK (event_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  trace_id VARCHAR(256) NOT NULL CHECK (trace_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  evaluation_id VARCHAR(256) NOT NULL CHECK (evaluation_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  subject_id VARCHAR(256) NOT NULL CHECK (subject_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  action_hash CHAR(71) NOT NULL CHECK (action_hash ~ '^sha256:[0-9a-f]{64}$'),
  policy_id VARCHAR(256) NOT NULL CHECK (policy_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  policy_version INTEGER NOT NULL CHECK (policy_version > 0),
  decision VARCHAR(32) NOT NULL CHECK (decision = 'allow'),
  credential_fingerprints JSONB NOT NULL CHECK (jsonb_typeof(credential_fingerprints) = 'array'),
  status VARCHAR(32) NOT NULL CHECK (status IN ('executing', 'succeeded', 'failed', 'unknown')),
  transition_sequence INTEGER NOT NULL CHECK (transition_sequence > 0),
  started_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL CHECK (updated_at >= started_at),
  PRIMARY KEY (tenant_id, attempt_id),
  UNIQUE (tenant_id, adapter_id, idempotency_key),
  UNIQUE (tenant_id, permit_id),
  FOREIGN KEY (tenant_id, permit_id)
    REFERENCES tp_consumed_execution_permits (tenant_id, permit_id)
);

CREATE TABLE tp_execution_transitions (
  tenant_id VARCHAR(128) NOT NULL,
  attempt_id VARCHAR(256) NOT NULL,
  sequence INTEGER NOT NULL CHECK (sequence > 0),
  from_status VARCHAR(32),
  to_status VARCHAR(32) NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  reason_code VARCHAR(128) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,127}$'),
  PRIMARY KEY (tenant_id, attempt_id, sequence),
  FOREIGN KEY (tenant_id, attempt_id)
    REFERENCES tp_execution_attempts (tenant_id, attempt_id),
  CHECK (
    (sequence = 1 AND from_status IS NULL AND to_status = 'executing') OR
    (from_status = 'executing' AND to_status IN ('succeeded', 'failed', 'unknown')) OR
    (from_status = 'unknown' AND to_status IN ('succeeded', 'failed'))
  )
);

CREATE TABLE tp_evidence_streams (
  tenant_id VARCHAR(128) NOT NULL CHECK (tenant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
  stream_id VARCHAR(128) NOT NULL CHECK (stream_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,127}$'),
  head_sequence BIGINT NOT NULL DEFAULT 0 CHECK (head_sequence >= 0),
  head_hash CHAR(71) CHECK (head_hash IS NULL OR head_hash ~ '^sha256:[0-9a-f]{64}$'),
  PRIMARY KEY (tenant_id, stream_id),
  CHECK ((head_sequence = 0 AND head_hash IS NULL) OR (head_sequence > 0 AND head_hash IS NOT NULL))
);

CREATE TABLE tp_evidence_events (
  tenant_id VARCHAR(128) NOT NULL,
  stream_id VARCHAR(128) NOT NULL,
  sequence BIGINT NOT NULL CHECK (sequence > 0),
  evidence_event_id VARCHAR(256) NOT NULL CHECK (evidence_event_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  payload JSONB NOT NULL CHECK (
    jsonb_typeof(payload) = 'object' AND
    NOT (payload ?| ARRAY['arguments', 'result', 'token', 'sourceAttestation', 'credential'])
  ),
  previous_event_hash CHAR(71) CHECK (previous_event_hash IS NULL OR previous_event_hash ~ '^sha256:[0-9a-f]{64}$'),
  event_hash CHAR(71) NOT NULL CHECK (event_hash ~ '^sha256:[0-9a-f]{64}$'),
  recorded_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, stream_id, sequence),
  UNIQUE (tenant_id, evidence_event_id),
  FOREIGN KEY (tenant_id, stream_id)
    REFERENCES tp_evidence_streams (tenant_id, stream_id),
  CHECK (payload ->> 'evidenceEventId' = evidence_event_id),
  CHECK ((payload ->> 'sequence')::BIGINT = sequence)
);

CREATE TABLE tp_evidence_outbox (
  tenant_id VARCHAR(128) NOT NULL,
  outbox_id VARCHAR(256) NOT NULL CHECK (outbox_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  stream_id VARCHAR(128) NOT NULL,
  sequence BIGINT NOT NULL,
  evidence_event_id VARCHAR(256) NOT NULL,
  payload_hash CHAR(71) NOT NULL CHECK (payload_hash ~ '^sha256:[0-9a-f]{64}$'),
  created_at TIMESTAMPTZ NOT NULL,
  published_at TIMESTAMPTZ,
  PRIMARY KEY (tenant_id, outbox_id),
  UNIQUE (tenant_id, evidence_event_id),
  FOREIGN KEY (tenant_id, stream_id, sequence)
    REFERENCES tp_evidence_events (tenant_id, stream_id, sequence),
  FOREIGN KEY (tenant_id, evidence_event_id)
    REFERENCES tp_evidence_events (tenant_id, evidence_event_id),
  CHECK (published_at IS NULL OR published_at >= created_at)
);

CREATE FUNCTION tp_validate_execution_transition() RETURNS TRIGGER AS $$
DECLARE
  current_status VARCHAR(32);
  current_sequence INTEGER;
  last_occurred_at TIMESTAMPTZ;
BEGIN
  SELECT status, transition_sequence
    INTO current_status, current_sequence
    FROM tp_execution_attempts
   WHERE tenant_id = NEW.tenant_id AND attempt_id = NEW.attempt_id
   FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'execution attempt does not exist';
  END IF;

  IF NEW.sequence = 1 THEN
    IF current_status <> 'executing' OR current_sequence <> 1 OR NEW.from_status IS NOT NULL OR NEW.to_status <> 'executing' THEN
      RAISE EXCEPTION 'invalid initial execution transition';
    END IF;
    RETURN NEW;
  END IF;

  SELECT occurred_at INTO last_occurred_at
    FROM tp_execution_transitions
   WHERE tenant_id = NEW.tenant_id AND attempt_id = NEW.attempt_id AND sequence = current_sequence;

  IF NEW.sequence <> current_sequence + 1 OR NEW.from_status <> current_status OR NEW.occurred_at < last_occurred_at THEN
    RAISE EXCEPTION 'non-monotonic execution transition';
  END IF;

  UPDATE tp_execution_attempts
     SET status = NEW.to_status, transition_sequence = NEW.sequence, updated_at = NEW.occurred_at
   WHERE tenant_id = NEW.tenant_id AND attempt_id = NEW.attempt_id;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tp_execution_transition_guard
BEFORE INSERT ON tp_execution_transitions
FOR EACH ROW EXECUTE FUNCTION tp_validate_execution_transition();

CREATE FUNCTION tp_reject_direct_attempt_mutation() RETURNS TRIGGER AS $$
BEGIN
  IF pg_trigger_depth() < 2 THEN
    RAISE EXCEPTION 'execution attempts transition only through the transition ledger';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tp_execution_attempt_update_guard
BEFORE UPDATE ON tp_execution_attempts
FOR EACH ROW EXECUTE FUNCTION tp_reject_direct_attempt_mutation();

CREATE FUNCTION tp_validate_evidence_append() RETURNS TRIGGER AS $$
DECLARE
  current_sequence BIGINT;
  current_hash CHAR(71);
BEGIN
  SELECT head_sequence, head_hash
    INTO current_sequence, current_hash
    FROM tp_evidence_streams
   WHERE tenant_id = NEW.tenant_id AND stream_id = NEW.stream_id
   FOR UPDATE;

  IF NOT FOUND OR NEW.sequence <> current_sequence + 1 OR NEW.previous_event_hash IS DISTINCT FROM current_hash THEN
    RAISE EXCEPTION 'non-monotonic evidence append';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tp_evidence_append_guard
BEFORE INSERT ON tp_evidence_events
FOR EACH ROW EXECUTE FUNCTION tp_validate_evidence_append();

CREATE FUNCTION tp_reject_append_only_mutation() RETURNS TRIGGER AS $$
BEGIN
  RAISE EXCEPTION 'append-only records cannot be changed';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tp_execution_transition_append_only
BEFORE UPDATE OR DELETE ON tp_execution_transitions
FOR EACH ROW EXECUTE FUNCTION tp_reject_append_only_mutation();

CREATE TRIGGER tp_evidence_event_append_only
BEFORE UPDATE OR DELETE ON tp_evidence_events
FOR EACH ROW EXECUTE FUNCTION tp_reject_append_only_mutation();
