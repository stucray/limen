-- v2 slice 1: per-Application Role catalogue. Discrete role rows replace
-- freeform string roles, allowing typo-proof assignment, safe renames, and a
-- natural "Manage Roles" screen on the Application. Tenant isolation is
-- transitive via application_id → applications.tenant_id.

CREATE TABLE role (
    id             bigserial    PRIMARY KEY,
    application_id bigint       NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    name           varchar(64)  NOT NULL,
    description    text         NULL,
    created_at     timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT role_application_name_unique UNIQUE (application_id, name)
);
