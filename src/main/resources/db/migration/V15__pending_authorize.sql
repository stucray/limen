-- Durable, short-lived store for pending OAuth2 /authorize requests (issue #327).
-- When an unauthenticated /oauth2/authorize bounces to the tenant login page, the
-- request is stashed here under an opaque single-use reference so the flow can be
-- replayed after the HTTP session (and its in-session SavedRequest) has been
-- evicted. The reference is carried on the login URL as ?ref=; rows are consumed
-- on successful replay and swept on expiry by PendingAuthorizeSweep. The stashed
-- parameters are non-secret (they were already in the client's query string) and
-- redirect_uri is re-validated by the Authorization Server at /authorize, so this
-- is a resume-convenience mechanism, not a trust boundary.
CREATE TABLE pending_authorize (
    ref           varchar(64)  NOT NULL,
    tenant_slug   varchar(255) NOT NULL,
    authorize_url text         NOT NULL,
    expires_at    timestamptz  NOT NULL,
    PRIMARY KEY (ref)
);

-- Supports the scheduled expiry sweep and the expires_at filter on read.
CREATE INDEX idx_pending_authorize_expires_at ON pending_authorize (expires_at);
