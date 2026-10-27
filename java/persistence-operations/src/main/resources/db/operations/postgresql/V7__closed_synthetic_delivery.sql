-- One append-only journal, explicitly installed with every older writer stopped.
CREATE TABLE tp_operation_delivery_events (
  operation_id VARCHAR(36) COLLATE "C" NOT NULL,
  event_sequence INTEGER NOT NULL CHECK (event_sequence >= 1),
  event_hash VARCHAR(71) COLLATE "C" NOT NULL,
  event_json TEXT NOT NULL CHECK (OCTET_LENGTH(event_json) <= 4096),
  PRIMARY KEY (operation_id, event_sequence),
  FOREIGN KEY (operation_id) REFERENCES tp_operation_admission_outbox(operation_id)
);
