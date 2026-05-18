-- Optional free-form name carried on the user row, populated by the OIDC
-- `name` claim when a relying-party client is granted the `profile` scope at
-- /oauth2/authorize. Nullable, no backfill: existing users keep `null` and the
-- discovery-honest `name` claim is simply omitted from their ID token and
-- /userinfo response until they fill it in. Single field rather than
-- first/last because OIDC's `name` claim is itself a free-form display string
-- and Limen has no UX requirement to compose parts.

ALTER TABLE users
    ADD COLUMN full_name varchar(255) NULL;
