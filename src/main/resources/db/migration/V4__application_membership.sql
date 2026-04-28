-- v2 slice 2: Application Membership. Grants a User access to manage an
-- Application from the Tenant Owner console; App Membership Roles govern
-- console authority over the Application (the JWT roles claim continues to
-- come from Client Membership only — see PRD #39).
-- Tenant isolation is transitive via application_id → applications.tenant_id.
-- granted_by is nullable so deleting the granter does not cascade-destroy
-- the audit trail; granted_at + granted_by together form the minimal forensic
-- record (full audit log deferred to v2.5).

CREATE TABLE application_membership (
    id             bigserial    PRIMARY KEY,
    user_id        bigint       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    application_id bigint       NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    granted_at     timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    granted_by     bigint       NULL REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT application_membership_user_app_unique UNIQUE (user_id, application_id)
);

CREATE TABLE application_membership_role (
    application_membership_id bigint NOT NULL REFERENCES application_membership(id) ON DELETE CASCADE,
    role_id                   bigint NOT NULL REFERENCES role(id) ON DELETE RESTRICT,
    PRIMARY KEY (application_membership_id, role_id)
);
