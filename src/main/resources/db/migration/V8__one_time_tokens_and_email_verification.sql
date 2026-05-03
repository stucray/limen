-- One-Time Token storage plus the user-side flag the verification flow flips.
--
-- Spring Security 7 ships JdbcOneTimeTokenService with a fixed schema
-- ("classpath:org/springframework/security/core/ott/jdbc/one-time-tokens-schema.sql")
-- of (token_value, username, expires_at). We keep the same primary-key + value
-- columns Spring's RowMapper expects, then add two columns the framework does
-- not know about:
--
--   * tenant_id — enforced by TenantAwareOneTimeTokenService so a token issued
--     under tenant A cannot be consumed when the request resolves to tenant B
--     (mirrors the TenantAwareOAuth2AuthorizationService isolation pattern).
--
--   * intent    — verify-email | password-reset, so one storage layer carries
--     both flows. The OTT email notifier and the post-OTT login dispatcher
--     read it to choose template + redirect target.
--
-- The framework's varchar_ignorecase(50) for username is HSQL-only syntax and
-- 50 chars is too narrow for emails; we widen to varchar(255).

CREATE TABLE one_time_tokens (
    token_value varchar(36)  NOT NULL PRIMARY KEY,
    username    varchar(255) NOT NULL,
    expires_at  timestamp    NOT NULL,
    tenant_id   bigint       NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    intent      varchar(32)  NOT NULL
        CHECK (intent IN ('verify-email', 'password-reset'))
);

CREATE INDEX one_time_tokens_tenant_idx  ON one_time_tokens (tenant_id);
CREATE INDEX one_time_tokens_expires_idx ON one_time_tokens (expires_at);

-- Email verification gate. Defaults to FALSE so any user inserted without
-- explicit verification (signup, system-admin tenant create) lands unverified;
-- the OTT magic link flips it to TRUE. The PostLoginIntent.emailVerificationRequired()
-- intent reads this column to block /oauth2/authorize until it is set.
ALTER TABLE users
    ADD COLUMN email_verified boolean NOT NULL DEFAULT false;
