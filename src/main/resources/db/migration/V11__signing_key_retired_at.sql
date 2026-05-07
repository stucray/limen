-- Adds the timestamp column needed by signing-key rotation.
--
-- `created_at` already exists from V1. `retired_at` is set the moment a key
-- transitions from ACTIVE to RETIRED, and read by the prune query that deletes
-- keys older than the configured grace period.
ALTER TABLE tenant_signing_key
    ADD COLUMN retired_at timestamp NULL;
