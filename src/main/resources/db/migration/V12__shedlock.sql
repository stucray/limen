-- ShedLock JDBC schema. One row per named lock; the JdbcTemplateLockProvider
-- inserts/updates rows here to coordinate @SchedulerLock-annotated methods
-- across multiple Limen instances. Currently the only producer is
-- SigningKeyRotationSchedule (lock name 'rotate-signing-keys').
CREATE TABLE shedlock (
    name       varchar(64)  NOT NULL,
    lock_until timestamptz  NOT NULL,
    locked_at  timestamptz  NOT NULL,
    locked_by  varchar(255) NOT NULL,
    PRIMARY KEY (name)
);
