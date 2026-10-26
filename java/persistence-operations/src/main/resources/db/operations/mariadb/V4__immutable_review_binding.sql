-- One additive DDL statement. Interrupted DDL requires explicit stopped-writer recovery.
CREATE TABLE tp_operation_review_bindings (
  operation_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  binding_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  binding_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (OCTET_LENGTH(binding_json) <= 8192),
  FOREIGN KEY (operation_id) REFERENCES tp_operation_lifecycle_heads(operation_id)
) ENGINE=InnoDB;
