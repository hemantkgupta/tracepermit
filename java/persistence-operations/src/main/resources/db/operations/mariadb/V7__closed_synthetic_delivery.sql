-- Exactly one schema mutation. No mutable lease row or runtime UPDATE privilege.
CREATE TABLE tp_operation_delivery_events (
  operation_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_sequence INTEGER NOT NULL CHECK (event_sequence >= 1),
  event_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (OCTET_LENGTH(event_json) <= 4096),
  PRIMARY KEY (operation_id, event_sequence),
  FOREIGN KEY (operation_id) REFERENCES tp_operation_admission_outbox(operation_id)
) ENGINE=InnoDB;
