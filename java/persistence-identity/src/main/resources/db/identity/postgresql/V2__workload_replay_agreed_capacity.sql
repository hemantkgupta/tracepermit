ALTER TABLE tp_workload_replay_state
  ADD COLUMN capacity BIGINT NOT NULL DEFAULT 10000;

ALTER TABLE tp_workload_replay_state
  ADD CONSTRAINT tp_workload_replay_capacity_positive CHECK (capacity > 0);
