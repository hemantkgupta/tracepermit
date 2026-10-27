-- Exactly one additive DDL statement. Interrupted upgrades require stopped-writer recovery.
CREATE TABLE tp_operation_admissions (
  operation_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  permit_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
  attempt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
  admission_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  admission_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (OCTET_LENGTH(admission_json) <= 16384),
  FOREIGN KEY (operation_id) REFERENCES tp_operation_lifecycle_heads(operation_id)
) ENGINE=InnoDB;
