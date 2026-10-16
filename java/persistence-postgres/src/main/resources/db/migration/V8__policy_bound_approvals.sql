ALTER TABLE tp_enforcement_schema_guard
  DROP CONSTRAINT tp_enforcement_schema_guard_contract_version_check;

UPDATE tp_enforcement_schema_guard SET contract_version = 8 WHERE singleton = TRUE;

ALTER TABLE tp_enforcement_schema_guard
  ADD CONSTRAINT tp_enforcement_schema_guard_contract_version_check
  CHECK (contract_version = 8);

-- Records the exact policy version and monotonic activation generation under which a pending
-- approval was evaluated. Stale approvals under older generations fail closed on execution.
CREATE TABLE tp_approval_policy_bindings (
  tenant_id VARCHAR(128) NOT NULL CHECK (tenant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
  approval_request_id VARCHAR(256) NOT NULL CHECK (approval_request_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  workspace_id VARCHAR(128) NOT NULL CHECK (workspace_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,127}$'),
  policy_id VARCHAR(256) NOT NULL CHECK (policy_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-]{0,255}$'),
  policy_version INTEGER NOT NULL CHECK (policy_version >= 1 AND policy_version <= 2147483647),
  policy_generation BIGINT NOT NULL CHECK (policy_generation >= 1),
  policy_content_hash CHAR(71) NOT NULL CHECK (policy_content_hash ~ '^sha256:[0-9a-f]{64}$'),
  bound_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, approval_request_id),
  FOREIGN KEY (tenant_id, approval_request_id)
    REFERENCES tp_approval_requests (tenant_id, approval_request_id),
  FOREIGN KEY (workspace_id, policy_id, policy_version)
    REFERENCES tp_policy_versions (workspace_id, policy_id, version)
);

CREATE TRIGGER tp_approval_policy_binding_immutable
BEFORE UPDATE OR DELETE ON tp_approval_policy_bindings
FOR EACH ROW EXECUTE FUNCTION tp_reject_append_only_mutation();
