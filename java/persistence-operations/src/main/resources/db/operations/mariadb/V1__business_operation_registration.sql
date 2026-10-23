CREATE TABLE tp_business_operations (
  operation_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  scope_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_key_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  binding_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  snapshot_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  snapshot_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (OCTET_LENGTH(snapshot_json) <= 262144),
  CONSTRAINT tp_operation_request_key PRIMARY KEY (scope_hash, request_key_hash),
  CONSTRAINT tp_operation_id UNIQUE (operation_id)
) ENGINE=InnoDB;

CREATE TABLE tp_business_operation_facts (
  operation_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sequence_number INTEGER NOT NULL CHECK (sequence_number BETWEEN 1 AND 3),
  fact_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (OCTET_LENGTH(fact_json) <= 262144),
  PRIMARY KEY (operation_id, sequence_number),
  FOREIGN KEY (operation_id) REFERENCES tp_business_operations(operation_id)
) ENGINE=InnoDB;
