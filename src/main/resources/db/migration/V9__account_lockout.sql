-- Account lockout state, scoped to the user row rather than a separate table —
-- the lookup happens on every login attempt and the data lifetime matches the
-- user's. failed_login_attempts is a monotonic counter the LoginAttemptTracker
-- listener increments on AuthenticationFailureEvent and resets to zero on
-- AuthenticationSuccessEvent. locked_until carries the expiry timestamp set
-- when the counter crosses limen.lockout.threshold; the auth provider
-- pre-checks this before verifying the password and rejects with
-- LockedException when locked_until > now().
--
-- Both columns are scoped per-user, so locking user A in tenant T does not
-- affect user B in the same tenant. The admin "Unlock account" path clears
-- locked_until and resets failed_login_attempts atomically.

ALTER TABLE users
    ADD COLUMN failed_login_attempts integer   NOT NULL DEFAULT 0,
    ADD COLUMN locked_until          timestamp NULL;
