-- Limen initial schema baseline. Future migrations evolve from here using
-- additive ALTER patterns; tenant-scoped OAuth2 tables now hold real data.

CREATE TABLE tenants (
    id           bigserial    PRIMARY KEY,
    slug         varchar(48)  NOT NULL UNIQUE,
    display_name varchar(255) NOT NULL,
    status       varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at   timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE users (
    id                   bigserial    PRIMARY KEY,
    tenant_id            bigint       NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    username             varchar(100) NOT NULL,
    password_hash        varchar(255) NOT NULL,
    enabled              boolean      NOT NULL DEFAULT true,
    must_change_password boolean      NOT NULL DEFAULT false,
    tenant_owner         boolean      NOT NULL DEFAULT false,
    created_at           timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT users_tenant_username_unique UNIQUE (tenant_id, username)
);

-- Spring Security remember-me persistent token store; column types must match
-- the JdbcTokenRepositoryImpl contract.
CREATE TABLE persistent_logins (
    username  varchar(64) NOT NULL,
    series    varchar(64) PRIMARY KEY,
    token     varchar(64) NOT NULL,
    last_used timestamp   NOT NULL
);

CREATE TABLE applications (
    id          bigserial    PRIMARY KEY,
    tenant_id   bigint       NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        varchar(255) NOT NULL,
    description text,
    created_at  timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT applications_tenant_name_unique UNIQUE (tenant_id, name)
);

-- Spring Authorization Server: registered clients. Columns are Spring-owned;
-- application_id is Limen's link from a registered client to its Application.
CREATE TABLE oauth2_registered_client (
    id varchar(100) NOT NULL,
    client_id varchar(100) NOT NULL,
    client_id_issued_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret varchar(200) DEFAULT NULL,
    client_secret_expires_at timestamp DEFAULT NULL,
    client_name varchar(200) NOT NULL,
    client_authentication_methods varchar(1000) NOT NULL,
    authorization_grant_types varchar(1000) NOT NULL,
    redirect_uris varchar(1000) DEFAULT NULL,
    post_logout_redirect_uris varchar(1000) DEFAULT NULL,
    scopes varchar(1000) NOT NULL,
    client_settings varchar(2000) NOT NULL,
    token_settings varchar(2000) NOT NULL,
    application_id bigint REFERENCES applications(id) ON DELETE CASCADE,
    PRIMARY KEY (id)
);

-- Spring Authorization Server: authorization grants (auth codes, access /
-- refresh / id tokens, device codes), tenant-scoped.
CREATE TABLE oauth2_authorization (
    id varchar(100) NOT NULL,
    tenant_id bigint REFERENCES tenants(id) ON DELETE CASCADE,
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorization_grant_type varchar(100) NOT NULL,
    authorized_scopes varchar(1000) DEFAULT NULL,
    attributes text DEFAULT NULL,
    state varchar(500) DEFAULT NULL,
    authorization_code_value text DEFAULT NULL,
    authorization_code_issued_at timestamp DEFAULT NULL,
    authorization_code_expires_at timestamp DEFAULT NULL,
    authorization_code_metadata text DEFAULT NULL,
    access_token_value text DEFAULT NULL,
    access_token_issued_at timestamp DEFAULT NULL,
    access_token_expires_at timestamp DEFAULT NULL,
    access_token_metadata text DEFAULT NULL,
    access_token_type varchar(100) DEFAULT NULL,
    access_token_scopes varchar(1000) DEFAULT NULL,
    oidc_id_token_value text DEFAULT NULL,
    oidc_id_token_issued_at timestamp DEFAULT NULL,
    oidc_id_token_expires_at timestamp DEFAULT NULL,
    oidc_id_token_metadata text DEFAULT NULL,
    refresh_token_value text DEFAULT NULL,
    refresh_token_issued_at timestamp DEFAULT NULL,
    refresh_token_expires_at timestamp DEFAULT NULL,
    refresh_token_metadata text DEFAULT NULL,
    user_code_value text DEFAULT NULL,
    user_code_issued_at timestamp DEFAULT NULL,
    user_code_expires_at timestamp DEFAULT NULL,
    user_code_metadata text DEFAULT NULL,
    device_code_value text DEFAULT NULL,
    device_code_issued_at timestamp DEFAULT NULL,
    device_code_expires_at timestamp DEFAULT NULL,
    device_code_metadata text DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_oauth2_authorization_tenant_id ON oauth2_authorization (tenant_id);

-- Spring Authorization Server: persisted user consents, tenant-scoped.
CREATE TABLE oauth2_authorization_consent (
    tenant_id bigint NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorities varchar(1000) NOT NULL,
    PRIMARY KEY (tenant_id, registered_client_id, principal_name)
);

-- Limen-side join from a Spring registered client to its Tenant + Application,
-- plus Limen-only fields (display_name, confidential).
CREATE TABLE client_metadata (
    id                   bigserial    PRIMARY KEY,
    registered_client_id varchar(100) NOT NULL UNIQUE REFERENCES oauth2_registered_client(id) ON DELETE CASCADE,
    application_id       bigint       NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    tenant_id            bigint       NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    display_name         varchar(255) NOT NULL,
    confidential         boolean      NOT NULL DEFAULT true
);

-- Per-tenant signing keys. private_key_ciphertext is encrypted with the
-- deployment-wide KEK from LIMEN_KEY_ENCRYPTION_KEY using a per-row salt
-- stored in `iv`. The partial unique index enforces "at most one ACTIVE key
-- per tenant".
CREATE TABLE tenant_signing_key (
    id                      bigserial    PRIMARY KEY,
    tenant_id               bigint       NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    kid                     varchar(64)  NOT NULL,
    algorithm               varchar(16)  NOT NULL,
    private_key_ciphertext  bytea        NOT NULL,
    iv                      bytea        NOT NULL,
    public_key_jwk          text         NOT NULL,
    status                  varchar(16)  NOT NULL,
    created_at              timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT tenant_signing_key_status_check CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT tenant_signing_key_kid_unique  UNIQUE (tenant_id, kid)
);

CREATE UNIQUE INDEX tenant_signing_key_one_active_per_tenant
    ON tenant_signing_key (tenant_id)
    WHERE status = 'ACTIVE';
