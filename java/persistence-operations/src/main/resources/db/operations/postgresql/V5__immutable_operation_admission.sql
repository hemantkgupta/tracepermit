-- One additive mutation; no default upgrade or runtime migration.
CREATE TABLE tp_operation_admissions (
  operation_id VARCHAR(36) COLLATE "C" NOT NULL PRIMARY KEY,
  permit_id VARCHAR(36) COLLATE "C" NOT NULL UNIQUE,
  attempt_id VARCHAR(36) COLLATE "C" NOT NULL UNIQUE,
  admission_hash VARCHAR(71) COLLATE "C" NOT NULL,
  admission_json TEXT NOT NULL CHECK (OCTET_LENGTH(admission_json) <= 16384),
  FOREIGN KEY (operation_id) REFERENCES tp_operation_lifecycle_heads(operation_id)
);
