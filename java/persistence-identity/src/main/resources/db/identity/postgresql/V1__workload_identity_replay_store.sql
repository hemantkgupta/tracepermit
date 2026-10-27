CREATE TABLE tp_workload_replay_claims (
  claim_hash VARCHAR(71) COLLATE "C" NOT NULL,
  tenant_hash VARCHAR(71) COLLATE "C" NOT NULL,
  token_fingerprint VARCHAR(71) COLLATE "C" NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  claimed_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT tp_workload_replay_claim_pk PRIMARY KEY (claim_hash),
  CONSTRAINT tp_workload_replay_claim_hash_format CHECK (claim_hash ~ '^sha256:[0-9a-f]{64}$'),
  CONSTRAINT tp_workload_replay_tenant_hash_format CHECK (tenant_hash ~ '^sha256:[0-9a-f]{64}$'),
  CONSTRAINT tp_workload_replay_token_hash_format CHECK (token_fingerprint ~ '^sha256:[0-9a-f]{64}$'),
  CONSTRAINT tp_workload_replay_claim_time_order CHECK (claimed_at < expires_at)
);

CREATE TABLE tp_workload_replay_state (
  singleton SMALLINT NOT NULL,
  total_claims BIGINT NOT NULL,
  CONSTRAINT tp_workload_replay_state_pk PRIMARY KEY (singleton),
  CONSTRAINT tp_workload_replay_state_singleton CHECK (singleton = 1),
  CONSTRAINT tp_workload_replay_total_claims_non_negative CHECK (total_claims >= 0)
);

INSERT INTO tp_workload_replay_state (singleton, total_claims) VALUES (1, 0);
