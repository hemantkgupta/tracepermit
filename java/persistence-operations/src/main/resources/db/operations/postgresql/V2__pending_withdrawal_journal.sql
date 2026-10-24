-- Immutable V1 registrations/facts are deliberately untouched.
CREATE TABLE tp_operation_lifecycle_heads (
  operation_id VARCHAR(36) COLLATE "C" NOT NULL PRIMARY KEY,
  snapshot_hash VARCHAR(71) COLLATE "C" NOT NULL,
  snapshot_json TEXT NOT NULL CHECK (OCTET_LENGTH(snapshot_json) <= 262144),
  FOREIGN KEY (operation_id) REFERENCES tp_business_operations(operation_id)
);

CREATE TABLE tp_operation_lifecycle_facts (
  operation_id VARCHAR(36) COLLATE "C" NOT NULL,
  sequence_number INTEGER NOT NULL CHECK (sequence_number >= 4),
  previous_snapshot_hash VARCHAR(71) COLLATE "C" NOT NULL,
  result_snapshot_hash VARCHAR(71) COLLATE "C" NOT NULL,
  fact_json TEXT NOT NULL CHECK (OCTET_LENGTH(fact_json) <= 262144),
  actor_hash VARCHAR(71) COLLATE "C" NOT NULL,
  actor_json TEXT NOT NULL CHECK (OCTET_LENGTH(actor_json) <= 4096),
  PRIMARY KEY (operation_id, sequence_number),
  FOREIGN KEY (operation_id) REFERENCES tp_operation_lifecycle_heads(operation_id)
);

INSERT INTO tp_operation_lifecycle_heads (operation_id, snapshot_hash, snapshot_json)
  SELECT operation_id, snapshot_hash, snapshot_json FROM tp_business_operations;
