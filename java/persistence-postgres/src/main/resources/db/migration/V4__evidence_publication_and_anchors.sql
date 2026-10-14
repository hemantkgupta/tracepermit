ALTER TABLE tp_enforcement_schema_guard
  DROP CONSTRAINT tp_enforcement_schema_guard_contract_version_check;

UPDATE tp_enforcement_schema_guard SET contract_version = 4 WHERE singleton = TRUE;

ALTER TABLE tp_enforcement_schema_guard
  ADD CONSTRAINT tp_enforcement_schema_guard_contract_version_check
  CHECK (contract_version = 4);

ALTER TABLE tp_evidence_outbox
  ADD COLUMN publication_attempts INTEGER NOT NULL DEFAULT 0 CHECK (publication_attempts >= 0),
  ADD COLUMN next_attempt_at TIMESTAMPTZ,
  ADD COLUMN lease_owner VARCHAR(128)
    CHECK (lease_owner IS NULL OR lease_owner ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,127}$'),
  ADD COLUMN lease_expires_at TIMESTAMPTZ,
  ADD COLUMN last_failure_code VARCHAR(128)
    CHECK (last_failure_code IS NULL OR last_failure_code ~ '^[A-Z][A-Z0-9_]{0,127}$'),
  ADD CHECK ((lease_owner IS NULL) = (lease_expires_at IS NULL));

UPDATE tp_evidence_outbox SET next_attempt_at = created_at;

ALTER TABLE tp_evidence_outbox
  ALTER COLUMN next_attempt_at SET NOT NULL,
  ADD CHECK (next_attempt_at >= created_at),
  ADD CHECK (lease_expires_at IS NULL OR lease_expires_at > created_at);

CREATE INDEX tp_evidence_outbox_ready_idx
  ON tp_evidence_outbox (tenant_id, next_attempt_at, stream_id, sequence)
  WHERE published_at IS NULL;

CREATE TABLE tp_evidence_anchors (
  tenant_id VARCHAR(128) NOT NULL,
  anchor_id VARCHAR(256) NOT NULL
    CHECK (anchor_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  outbox_id VARCHAR(256) NOT NULL,
  schema_version VARCHAR(16) NOT NULL CHECK (schema_version = '1.0'),
  stream_id VARCHAR(128) NOT NULL,
  sequence BIGINT NOT NULL CHECK (sequence > 0),
  evidence_event_id VARCHAR(256) NOT NULL,
  payload_hash CHAR(71) NOT NULL CHECK (payload_hash ~ '^sha256:[0-9a-f]{64}$'),
  provider VARCHAR(128) NOT NULL
    CHECK (provider ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,127}$'),
  key_id VARCHAR(128) NOT NULL
    CHECK (key_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,127}$'),
  anchored_at TIMESTAMPTZ NOT NULL,
  signature_algorithm VARCHAR(16) NOT NULL CHECK (signature_algorithm = 'Ed25519'),
  signature VARCHAR(128) NOT NULL CHECK (signature ~ '^[A-Za-z0-9_-]{86}$'),
  stored_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, anchor_id),
  UNIQUE (tenant_id, outbox_id),
  UNIQUE (tenant_id, evidence_event_id),
  FOREIGN KEY (tenant_id, outbox_id)
    REFERENCES tp_evidence_outbox (tenant_id, outbox_id),
  FOREIGN KEY (tenant_id, stream_id, sequence)
    REFERENCES tp_evidence_events (tenant_id, stream_id, sequence),
  FOREIGN KEY (tenant_id, evidence_event_id)
    REFERENCES tp_evidence_events (tenant_id, evidence_event_id),
  CHECK (stored_at >= anchored_at)
);

CREATE TRIGGER tp_evidence_anchor_append_only
BEFORE UPDATE OR DELETE ON tp_evidence_anchors
FOR EACH ROW EXECUTE FUNCTION tp_reject_append_only_mutation();
