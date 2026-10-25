-- Exactly one additive DDL statement. A V5-only installation is not runtime-ready.
CREATE TABLE tp_operation_admission_outbox (
  operation_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  outbox_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  outbox_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (OCTET_LENGTH(outbox_json) <= 4096),
  FOREIGN KEY (operation_id) REFERENCES tp_operation_admissions(operation_id)
) ENGINE=InnoDB;
