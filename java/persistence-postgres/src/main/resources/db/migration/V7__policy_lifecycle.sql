ALTER TABLE tp_enforcement_schema_guard
  DROP CONSTRAINT tp_enforcement_schema_guard_contract_version_check;

UPDATE tp_enforcement_schema_guard SET contract_version = 7 WHERE singleton = TRUE;

ALTER TABLE tp_enforcement_schema_guard
  ADD CONSTRAINT tp_enforcement_schema_guard_contract_version_check
  CHECK (contract_version = 7);

-- Policy revisions are durable, validated immutable documents with exact versions and content hashes.
-- Core immutable fields (workspace, policy_id, version, content_hash, document, staged_at) can never be updated or deleted.
CREATE TABLE tp_policy_versions (
  workspace_id VARCHAR(128) NOT NULL CHECK (workspace_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,127}$'),
  policy_id VARCHAR(256) NOT NULL CHECK (policy_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  version INTEGER NOT NULL CHECK (version >= 1 AND version <= 2147483647),
  name VARCHAR(256) NOT NULL,
  description TEXT NOT NULL,
  content_hash CHAR(71) NOT NULL CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$'),
  document JSONB NOT NULL CHECK (
    jsonb_typeof(document) = 'object'
    AND octet_length(document::text) <= 1048576
  ),
  current_state VARCHAR(32) NOT NULL CHECK (current_state IN ('staged', 'active', 'deprecated', 'rolled_back')),
  staged_at TIMESTAMPTZ NOT NULL,
  activated_at TIMESTAMPTZ,
  deprecated_at TIMESTAMPTZ,
  rolled_back_at TIMESTAMPTZ,
  rollback_target_version INTEGER CHECK (rollback_target_version IS NULL OR (rollback_target_version >= 1 AND rollback_target_version <= 2147483647)),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (workspace_id, policy_id, version)
);

CREATE OR REPLACE FUNCTION tp_guard_policy_version_mutation()
RETURNS TRIGGER AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'tp_policy_versions rows cannot be deleted';
  END IF;
  IF TG_OP = 'UPDATE' THEN
    IF NEW.workspace_id <> OLD.workspace_id OR
       NEW.policy_id <> OLD.policy_id OR
       NEW.version <> OLD.version OR
       NEW.content_hash <> OLD.content_hash OR
       NEW.document::text <> OLD.document::text OR
       NEW.staged_at <> OLD.staged_at THEN
      RAISE EXCEPTION 'tp_policy_versions core immutable fields cannot be mutated';
    END IF;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tp_policy_version_guard_trigger
BEFORE UPDATE OR DELETE ON tp_policy_versions
FOR EACH ROW EXECUTE FUNCTION tp_guard_policy_version_mutation();

-- Tracks single active version for each (workspace_id, policy_id) with monotonic sequence for optimistic concurrency.
CREATE TABLE tp_active_policies (
  workspace_id VARCHAR(128) NOT NULL CHECK (workspace_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,127}$'),
  policy_id VARCHAR(256) NOT NULL CHECK (policy_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  active_version INTEGER NOT NULL CHECK (active_version >= 1 AND active_version <= 2147483647),
  activated_at TIMESTAMPTZ NOT NULL,
  activation_sequence BIGINT NOT NULL DEFAULT 1 CHECK (activation_sequence >= 1),
  updated_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (workspace_id, policy_id),
  FOREIGN KEY (workspace_id, policy_id, active_version)
    REFERENCES tp_policy_versions (workspace_id, policy_id, version)
);

-- Append-only audit record of all policy activations and rollbacks.
CREATE TABLE tp_policy_lifecycle_transitions (
  transition_id VARCHAR(256) NOT NULL CHECK (transition_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  workspace_id VARCHAR(128) NOT NULL CHECK (workspace_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,127}$'),
  policy_id VARCHAR(256) NOT NULL CHECK (policy_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  transition_sequence BIGINT NOT NULL CHECK (transition_sequence >= 1),
  transition_type VARCHAR(32) NOT NULL CHECK (transition_type IN ('activate', 'rollback')),
  from_version INTEGER CHECK (from_version IS NULL OR (from_version >= 1 AND from_version <= 2147483647)),
  to_version INTEGER NOT NULL CHECK (to_version >= 1 AND to_version <= 2147483647),
  occurred_at TIMESTAMPTZ NOT NULL,
  actor_id VARCHAR(256) CHECK (actor_id IS NULL OR actor_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-#]{0,255}$'),
  reason TEXT NOT NULL CHECK (octet_length(reason) <= 4096),
  PRIMARY KEY (workspace_id, policy_id, transition_sequence),
  UNIQUE (transition_id),
  FOREIGN KEY (workspace_id, policy_id, to_version)
    REFERENCES tp_policy_versions (workspace_id, policy_id, version)
);

CREATE TRIGGER tp_policy_lifecycle_transitions_immutable
BEFORE UPDATE OR DELETE ON tp_policy_lifecycle_transitions
FOR EACH ROW EXECUTE FUNCTION tp_reject_append_only_mutation();

CREATE INDEX tp_policy_lifecycle_transitions_type_idx
  ON tp_policy_lifecycle_transitions (workspace_id, policy_id, transition_type);
