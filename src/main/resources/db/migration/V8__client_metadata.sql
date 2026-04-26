CREATE TABLE client_metadata (
    id                   bigserial    PRIMARY KEY,
    registered_client_id varchar(100) NOT NULL UNIQUE REFERENCES oauth2_registered_client(id) ON DELETE CASCADE,
    application_id       bigint       NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    tenant_id            bigint       NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    display_name         varchar(255) NOT NULL,
    confidential         boolean      NOT NULL DEFAULT true
);
