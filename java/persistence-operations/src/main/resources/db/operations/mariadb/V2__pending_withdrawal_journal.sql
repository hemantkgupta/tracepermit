-- MariaDB DDL can implicitly commit: interrupted installs fail closed and require operator recovery.
CREATE TABLE tp_operation_lifecycle_heads (
  operation_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  snapshot_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  snapshot_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (OCTET_LENGTH(snapshot_json) <= 262144),
  FOREIGN KEY (operation_id) REFERENCES tp_business_operations(operation_id)
) ENGINE=InnoDB;

CREATE TABLE tp_operation_lifecycle_facts (
  operation_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sequence_number INTEGER NOT NULL CHECK (sequence_number >= 4),
  previous_snapshot_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  result_snapshot_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  fact_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (OCTET_LENGTH(fact_json) <= 262144),
  actor_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (OCTET_LENGTH(actor_json) <= 4096),
  PRIMARY KEY (operation_id, sequence_number),
  FOREIGN KEY (operation_id) REFERENCES tp_operation_lifecycle_heads(operation_id)
) ENGINE=InnoDB;

INSERT INTO tp_operation_lifecycle_heads (operation_id, snapshot_hash, snapshot_json)
  SELECT operation_id, snapshot_hash, snapshot_json FROM tp_business_operations;
