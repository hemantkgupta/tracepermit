-- A complete opt-in admission installation ends at V6, never the partial V5.
CREATE TABLE tp_operation_admission_outbox (
  operation_id VARCHAR(36) COLLATE "C" NOT NULL PRIMARY KEY,
  outbox_hash VARCHAR(71) COLLATE "C" NOT NULL,
  outbox_json TEXT NOT NULL CHECK (OCTET_LENGTH(outbox_json) <= 4096),
  FOREIGN KEY (operation_id) REFERENCES tp_operation_admissions(operation_id)
);
