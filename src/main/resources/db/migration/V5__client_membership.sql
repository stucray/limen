-- v2 slice 3: Client Membership. The explicit grant — Membership is the
-- authorization grant; without a Client Membership row a User cannot complete
-- /oauth2/authorize for a Client (gate lands in slice 5 / issue #44).
-- This slice adds the schema; the JWT customizer still emits roles: [] until
-- slice 4 (#43) wires the read query.
--
-- Tenant isolation is transitive: client_metadata already carries tenant_id,
-- application_membership.application_id resolves through applications.tenant_id.
--
-- Eligibility-gate enforcement (PRD #39 decision 4): client_membership has a
-- hard FK + ON DELETE CASCADE to application_membership. Revoking an App
-- Membership atomically revokes every derived Client Membership. user_id is
-- denormalized for read efficiency (JWT lookup keys on user_id) — service
-- enforces the consistency invariant cm.user_id == am.user_id at grant time.
--
-- The cross-table invariant cm.application_membership.application_id ==
-- cm.client_metadata.application_id is enforced in the service layer (DB-level
-- check would need a trigger; integration test enforces it). granted_by is
-- ON DELETE SET NULL, mirroring application_membership.

CREATE TABLE client_membership (
    id                        bigserial    PRIMARY KEY,
    user_id                   bigint       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_metadata_id        bigint       NOT NULL REFERENCES client_metadata(id) ON DELETE CASCADE,
    application_membership_id bigint       NOT NULL REFERENCES application_membership(id) ON DELETE CASCADE,
    granted_at                timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    granted_by                bigint       NULL REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT client_membership_user_client_unique UNIQUE (user_id, client_metadata_id)
);

CREATE TABLE client_membership_role (
    client_membership_id bigint NOT NULL REFERENCES client_membership(id) ON DELETE CASCADE,
    role_id              bigint NOT NULL REFERENCES role(id) ON DELETE RESTRICT,
    PRIMARY KEY (client_membership_id, role_id)
);
