CREATE TABLE tp_business_operations (
  operation_id VARCHAR(36) COLLATE "C" NOT NULL,
  scope_hash VARCHAR(71) COLLATE "C" NOT NULL,
  request_key_hash VARCHAR(71) COLLATE "C" NOT NULL,
  binding_hash VARCHAR(71) COLLATE "C" NOT NULL,
  snapshot_hash VARCHAR(71) COLLATE "C" NOT NULL,
  snapshot_json TEXT NOT NULL CHECK (OCTET_LENGTH(snapshot_json) <= 262144),
  CONSTRAINT tp_operation_request_key PRIMARY KEY (scope_hash, request_key_hash),
  CONSTRAINT tp_operation_id UNIQUE (operation_id)
);

CREATE TABLE tp_business_operation_facts (
  operation_id VARCHAR(36) COLLATE "C" NOT NULL,
  sequence_number INTEGER NOT NULL CHECK (sequence_number BETWEEN 1 AND 3),
  fact_json TEXT NOT NULL CHECK (OCTET_LENGTH(fact_json) <= 262144),
  PRIMARY KEY (operation_id, sequence_number),
  FOREIGN KEY (operation_id) REFERENCES tp_business_operations(operation_id)
);
