-- Append-only audit log. Every security-relevant action publishes a Spring
-- ApplicationEvent and an `@TransactionalEventListener(AFTER_COMMIT)` writes a
-- row here. Best-effort delivery for now (a JVM crash between commit and
-- listener execution loses the event); at-least-once arrives with Spring
-- Modulith adoption (architecture doc §6 v3.5) — at that point the listener
-- annotation swaps from `@TransactionalEventListener` to
-- `@ApplicationModuleListener` with no emit-site changes.
--
-- `tenant_id` is nullable to accommodate cross-tenant events (system-admin
-- actions, anonymous failed logins). `actor_user_id` is nullable for the same
-- reasons plus failed-login attempts that can't be resolved to a user.
-- `details` is JSONB so each event type is free to add structured context
-- (attempted email on a failed login, old/new status on a tenant transition,
-- etc.) without ALTER-ing the table.

CREATE TABLE audit_event (
    id              bigserial    PRIMARY KEY,
    tenant_id       bigint       REFERENCES tenants(id) ON DELETE SET NULL,
    actor_user_id   bigint       REFERENCES users(id)   ON DELETE SET NULL,
    event_type      varchar(64)  NOT NULL,
    target_type     varchar(64),
    target_id       varchar(255),
    ip_address      varchar(45),
    user_agent      varchar(512),
    occurred_at     timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    details         jsonb        NOT NULL DEFAULT '{}'::jsonb
);

-- Audit queries are almost always "events for tenant X, newest first".
CREATE INDEX audit_event_tenant_occurred_at_idx
    ON audit_event (tenant_id, occurred_at DESC);
