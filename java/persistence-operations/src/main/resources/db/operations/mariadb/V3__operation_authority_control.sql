-- One additive DDL statement; stopped-writer operator recovery on interrupted DDL.
CREATE TABLE tp_operation_authority_controls (
  scope_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  profile_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  lock_marker SMALLINT NOT NULL DEFAULT 0 CHECK (lock_marker = 0),
  recovery_generation BIGINT NOT NULL CHECK (recovery_generation BETWEEN 1 AND 9007199254740991),
  control_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  CHECK (control_state IN ('staged', 'ready', 'quarantined'))
) ENGINE=InnoDB;
