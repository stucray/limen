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
