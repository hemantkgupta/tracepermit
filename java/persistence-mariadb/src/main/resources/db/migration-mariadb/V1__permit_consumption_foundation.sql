CREATE TABLE tp_mariadb_schema_guard (
  singleton TINYINT UNSIGNED NOT NULL,
  contract_version INTEGER UNSIGNED NOT NULL,
  engine_family VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (singleton),
  CONSTRAINT tp_mariadb_guard_singleton CHECK (singleton = 1),
  CONSTRAINT tp_mariadb_guard_version CHECK (contract_version = 1),
  CONSTRAINT tp_mariadb_guard_engine CHECK (engine_family = 'mariadb')
) ENGINE=InnoDB DEFAULT CHARACTER SET ascii COLLATE ascii_bin;

INSERT INTO tp_mariadb_schema_guard (singleton, contract_version, engine_family)
VALUES (1, 1, 'mariadb');

CREATE TABLE tp_consumed_execution_permits (
  tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  permit_id VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  action_hash CHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  claimed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id, permit_id),
  CONSTRAINT tp_mariadb_permit_tenant CHECK (
    tenant_id REGEXP '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
  ),
  CONSTRAINT tp_mariadb_permit_id CHECK (
    permit_id REGEXP '^[A-Za-z0-9][-A-Za-z0-9._:@/+]{0,255}$'
  ),
  CONSTRAINT tp_mariadb_permit_hash CHECK (
    action_hash REGEXP '^sha256:[0-9a-f]{64}$'
  ),
  CONSTRAINT tp_mariadb_permit_time CHECK (claimed_at < expires_at)
) ENGINE=InnoDB DEFAULT CHARACTER SET ascii COLLATE ascii_bin;
