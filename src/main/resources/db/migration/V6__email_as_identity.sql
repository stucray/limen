-- Switch the user identifier from `username` to `email`. The PRD scopes Limen
-- to per-tenant user pools (Auth0 / Cognito shape), so the uniqueness constraint
-- is `(tenant_id, email)` — the same email may identify two distinct Users in
-- two different Tenants.
--
-- Existing dev data is dropped (no real customers yet); the alternative — a
-- nullable email column with a forced-fill flow — leaves permanent technical
-- debt for the sake of throwaway seeded rows. See PRD #120 §Implementation
-- Decisions → Slice 0 prerequisite.
--
-- `persistent_logins` mirrors the rename so the remember-me token row is
-- consistent with the new identifier; the table is TRUNCATEd because the prior
-- cookies referenced usernames that no longer exist.

TRUNCATE persistent_logins;
TRUNCATE users CASCADE;

ALTER TABLE users DROP CONSTRAINT users_tenant_username_unique;
ALTER TABLE users DROP COLUMN username;
ALTER TABLE users ADD COLUMN email varchar(255) NOT NULL;
ALTER TABLE users ADD CONSTRAINT users_tenant_email_unique UNIQUE (tenant_id, email);

DROP INDEX persistent_logins_tenant_username_idx;
ALTER TABLE persistent_logins RENAME COLUMN username TO email;
ALTER TABLE persistent_logins ALTER COLUMN email TYPE varchar(255);
CREATE INDEX persistent_logins_tenant_email_idx
    ON persistent_logins (tenant_id, email);
