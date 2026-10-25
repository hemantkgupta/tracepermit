-- Explicit stopped-writer opt-in. Existing registration/lifecycle/control bytes are unchanged.
CREATE TABLE tp_operation_review_bindings (
  operation_id VARCHAR(36) COLLATE "C" NOT NULL PRIMARY KEY,
  binding_hash VARCHAR(71) COLLATE "C" NOT NULL,
  binding_json TEXT NOT NULL CHECK (OCTET_LENGTH(binding_json) <= 8192),
  FOREIGN KEY (operation_id) REFERENCES tp_operation_lifecycle_heads(operation_id)
);
