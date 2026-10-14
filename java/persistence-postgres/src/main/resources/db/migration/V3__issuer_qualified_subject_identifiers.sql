ALTER TABLE tp_enforcement_schema_guard
  DROP CONSTRAINT tp_enforcement_schema_guard_contract_version_check;

UPDATE tp_enforcement_schema_guard SET contract_version = 3 WHERE singleton = TRUE;

ALTER TABLE tp_enforcement_schema_guard
  ADD CONSTRAINT tp_enforcement_schema_guard_contract_version_check
  CHECK (contract_version = 3);

ALTER TABLE tp_execution_attempts
  DROP CONSTRAINT tp_execution_attempts_subject_id_check;

ALTER TABLE tp_execution_attempts
  ADD CONSTRAINT tp_execution_attempts_subject_id_check
  CHECK (subject_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-#]{0,255}$');

ALTER TABLE tp_approval_requests
  DROP CONSTRAINT tp_approval_requests_subject_id_check;

ALTER TABLE tp_approval_requests
  ADD CONSTRAINT tp_approval_requests_subject_id_check
  CHECK (subject_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/+\-#]{0,255}$');
