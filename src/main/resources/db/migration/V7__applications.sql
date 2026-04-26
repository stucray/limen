CREATE TABLE applications (
    id          bigserial    PRIMARY KEY,
    tenant_id   bigint       NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        varchar(255) NOT NULL,
    description text,
    created_at  timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT applications_tenant_name_unique UNIQUE (tenant_id, name)
);

ALTER TABLE oauth2_registered_client ADD COLUMN application_id bigint REFERENCES applications(id) ON DELETE CASCADE;
