CREATE TABLE tenants (
    id           bigserial    PRIMARY KEY,
    slug         varchar(48)  NOT NULL UNIQUE,
    display_name varchar(255) NOT NULL,
    status       varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at   timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE users ADD COLUMN tenant_id          bigint  REFERENCES tenants(id) ON DELETE CASCADE;
ALTER TABLE users ADD COLUMN must_change_password boolean NOT NULL DEFAULT false;
ALTER TABLE users DROP CONSTRAINT users_username_key;
ALTER TABLE users ADD CONSTRAINT users_tenant_username_unique UNIQUE (tenant_id, username);
