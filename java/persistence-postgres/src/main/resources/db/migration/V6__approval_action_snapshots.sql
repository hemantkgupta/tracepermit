ALTER TABLE tp_enforcement_schema_guard
  DROP CONSTRAINT tp_enforcement_schema_guard_contract_version_check;
UPDATE tp_enforcement_schema_guard SET contract_version = 6 WHERE singleton = TRUE;
ALTER TABLE tp_enforcement_schema_guard
  ADD CONSTRAINT tp_enforcement_schema_guard_contract_version_check CHECK (contract_version = 6);

-- Operational, narrow simulator actions, not raw prompts or reusable credentials.
-- Separate from append-only audit metadata; retention requires a later reviewed lifecycle.
CREATE TABLE tp_approval_action_snapshots (
  tenant_id VARCHAR(128) NOT NULL,
  approval_request_id VARCHAR(256) NOT NULL,
  canonical_action JSONB NOT NULL CHECK (
    jsonb_typeof(canonical_action) = 'object'
    AND octet_length(canonical_action::text) <= 65536
  ),
  PRIMARY KEY (tenant_id, approval_request_id),
  FOREIGN KEY (tenant_id, approval_request_id)
    REFERENCES tp_approval_requests (tenant_id, approval_request_id)
);

CREATE TRIGGER tp_approval_action_snapshot_immutable
BEFORE UPDATE OR DELETE ON tp_approval_action_snapshots
FOR EACH ROW EXECUTE FUNCTION tp_reject_append_only_mutation();
