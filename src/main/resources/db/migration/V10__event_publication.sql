-- Spring Modulith JDBC publication registry — backing table for at-least-once
-- delivery of `@ApplicationModuleListener`-annotated handlers (currently just
-- AuditDispatcher.onAfterCommit). Schema mirrors the v2 PostgreSQL DDL bundled
-- with spring-modulith-events-jdbc 2.0.x; we manage it via Flyway rather than
-- the framework's own initializer (`spring.modulith.events.jdbc.schema-
-- initialization.enabled` stays at its default of false).
--
-- A row lands here transactionally with the publishing event. If the JVM
-- crashes after commit but before the listener completes, the row's
-- completion_date stays null; on next startup Modulith replays the event,
-- re-invokes the listener, and stamps completion. That gives at-least-once
-- semantics for transactionally-published events. Duplicate audit_event rows
-- are possible on rare restart-after-failure (no idempotency key in this
-- slice — see architecture.md §4.8).
--
-- Scope of the guarantee: this only engages for the AFTER_COMMIT-bound
-- AuditDispatcher.onAfterCommit listener. The IMMEDIATE-bound listener for
-- Spring Security auth events and rate-limit hits stays best-effort: those
-- events fire outside any transaction, so there's nothing for the publication
-- registry to attach to.

CREATE TABLE event_publication (
    id                     uuid                     NOT NULL,
    listener_id            text                     NOT NULL,
    event_type             text                     NOT NULL,
    serialized_event       text                     NOT NULL,
    publication_date       timestamp with time zone NOT NULL,
    completion_date        timestamp with time zone,
    status                 text,
    completion_attempts    int,
    last_resubmission_date timestamp with time zone,
    PRIMARY KEY (id)
);

CREATE INDEX event_publication_serialized_event_hash_idx
    ON event_publication USING hash (serialized_event);

CREATE INDEX event_publication_by_completion_date_idx
    ON event_publication (completion_date);
