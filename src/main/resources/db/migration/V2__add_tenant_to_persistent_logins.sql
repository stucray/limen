-- Tenant-scope remember-me storage. Series alone was previously the primary key —
-- replace with (tenant_id, series) so that a stolen or replayed cookie cannot
-- authenticate as a different tenant's user. Existing rows are TRUNCATEd: the
-- old cookie format (`series:token`) cannot represent the tenant either, so the
-- conservative response to a tenant-isolation gap is to invalidate in-flight
-- cookies. Users will simply re-authenticate.

TRUNCATE persistent_logins;

ALTER TABLE persistent_logins DROP CONSTRAINT persistent_logins_pkey;

ALTER TABLE persistent_logins
    ADD COLUMN tenant_id bigint NOT NULL REFERENCES tenants(id) ON DELETE CASCADE;

ALTER TABLE persistent_logins ADD PRIMARY KEY (tenant_id, series);

CREATE INDEX persistent_logins_tenant_username_idx
    ON persistent_logins (tenant_id, username);
