# Limen — Architecture

> A companion to `UBIQUITOUS_LANGUAGE.md`. That file defines *what we call things*; this file describes *how the system is built*.

## 1. Purpose

Limen is a **multi-tenant OAuth2 / OIDC Authorization Server** built on top of Spring Authorization Server (SAS). It is a SaaS-style product: a single deployment hosts many independent **Tenants**, each with its own User pool, Applications, Clients, signing keys, and issuer URL.

The product is aimed at developers and organisations who need a hosted identity provider for their own applications without standing up Keycloak or paying for a commercial IdP. A new Tenant is created via public self-service signup; from that point on, the Tenant Owner manages everything for their Tenant through the management console at `/manage/t/{slug}/`.

Concretely, Limen provides:

- **Per-tenant OIDC issuer** at `https://{host}/t/{slug}/` with its own `.well-known/openid-configuration`, JWKS, authorization, token, introspection, revocation and userinfo endpoints.
- **Per-tenant signing keys** (RS256, RSA-2048), stored encrypted at rest using a single Key Encryption Key (KEK) supplied via environment variable.
- **Tenant-scoped storage** for all SAS persistence interfaces (`RegisteredClientRepository`, `OAuth2AuthorizationService`, `OAuth2AuthorizationConsentService`).
- **A management console** (Thymeleaf + REST) for managing Applications, Clients, Users and (in v2) Roles and Memberships.
- **A System Tenant** at the slug `system` for cross-tenant operations performed by System Admins.

## 2. Stack

| Layer | Choice |
|---|---|
| Language | Java 26 |
| Concurrency | Java virtual threads (`spring.threads.virtual.enabled=true`); per-request tenant binding via `ScopedValue` (`TenantScope`) |
| Framework | Spring Boot 4.0.5 |
| OAuth2 / OIDC | Spring Authorization Server (via `spring-boot-starter-security-oauth2-authorization-server`) |
| Persistence | Spring Data JDBC (records, no JPA / Hibernate) |
| Database | PostgreSQL |
| Schema migrations | Flyway |
| Web UI | Thymeleaf |
| Crypto | Spring Security Crypto (`Encryptors.stronger`), Nimbus JOSE+JWT |
| Tests | Spring Boot Test + Testcontainers (Postgres) |
| Build | Maven |

## 3. Domain Model

The canonical definitions live in `UBIQUITOUS_LANGUAGE.md`. The diagram below shows how those concepts map to persisted entities in v1.

```mermaid
erDiagram
    TENANT ||--o{ USER : contains
    TENANT ||--o{ APPLICATION : contains
    TENANT ||--o{ TENANT_SIGNING_KEY : "owns"
    TENANT ||--o{ OAUTH2_AUTHORIZATION : "scopes"
    TENANT ||--o{ OAUTH2_AUTHORIZATION_CONSENT : "scopes"
    APPLICATION ||--o{ CLIENT_METADATA : groups
    CLIENT_METADATA ||--|| OAUTH2_REGISTERED_CLIENT : "1:1"

    TENANT {
        uuid id PK
        string slug UK
        string display_name
        enum status "ACTIVE | SUSPENDED"
    }
    USER {
        uuid id PK
        uuid tenant_id FK
        string username
        string password_hash
        bool enabled
        bool must_change_password
        bool tenant_owner
    }
    APPLICATION {
        uuid id PK
        uuid tenant_id FK
        string name
        string description
    }
    CLIENT_METADATA {
        uuid id PK
        uuid tenant_id FK
        uuid application_id FK
        string registered_client_id UK
        string display_name
        bool confidential
    }
    TENANT_SIGNING_KEY {
        uuid id PK
        uuid tenant_id FK
        string kid
        string algorithm
        bytea private_key_ciphertext
        bytea iv
        text public_key_jwk
        enum status "ACTIVE | RETIRED"
    }
    OAUTH2_REGISTERED_CLIENT {
        string id PK
        string client_id
        string client_secret
        string scopes
        string grant_types
        string redirect_uris
    }
```

A few things worth calling out:

- **`User.username` is unique within a Tenant**, not globally. The same username can exist in multiple Tenants.
- **`client_metadata` joins to `oauth2_registered_client` 1:1** via a UNIQUE foreign key. The SAS table holds the OAuth2-wire fields (client id/secret, grant types, redirect URIs); `client_metadata` holds Limen-specific fields (display name, owning Application, owning Tenant). This split keeps Limen's tenant model from leaking into Spring's schema.
- **`tenant_signing_key` enforces "at most one ACTIVE key per Tenant"** with a partial unique index: `UNIQUE(tenant_id) WHERE status = 'ACTIVE'`. This is the enforcement point for key rotation.
- **Roles, Memberships, Scopes are not yet persisted.** The PRD agreed they would be deferred to v2; the current schema does not preclude adding them.

## 4. Architecture Overview

### 4.1 Component layout

```mermaid
flowchart TB
    subgraph "End User Browser"
      EU[OAuth2 client app]
      MGT[Tenant Owner]
    end

    subgraph "Limen JVM"
      direction TB

      subgraph "Servlet filter chain"
        ROUT["TenantOAuth2RoutingFilter<br/>matches /t/{slug}/oauth2|.well-known|connect|userinfo"]
        ISS[TenantIssuerContextFilter]
        SAS["Spring Authorization Server<br/>filter chain"]
        OAL["OAuth2 login filter chain<br/>/t/{slug}/login + change-password"]
        MGTC["Management filter chain<br/>/manage/t/{slug}/**"]
        TAF["TenantAccessFilter<br/>(both UI chains)"]
      end

      subgraph "Tenant-aware storage decorators"
        TRCR[TenantAwareRegisteredClientRepository]
        TAS[TenantAwareOAuth2AuthorizationService]
        TACS[TenantAwareOAuth2AuthorizationConsentService]
        TJWK[TenantJwkSource]
      end

      subgraph "Domain services"
        TPS[TenantProvisioningService]
        SKS[JdbcSigningKeyStore]
        UMS[UserManagementService]
        CMS[ClientManagementService]
      end

      DB[(PostgreSQL)]
    end

    EU -->|/t/acme/oauth2/authorize| ROUT
    EU -->|/t/acme/login| OAL
    MGT -->|/manage/t/acme/applications| MGTC
    MGTC --> TAF
    OAL --> TAF
    ROUT --> ISS --> SAS
    SAS --> TRCR
    SAS --> TAS
    SAS --> TACS
    SAS --> TJWK
    MGTC --> UMS
    MGTC --> CMS
    TPS --> SKS
    TRCR --> DB
    TAS --> DB
    TACS --> DB
    TJWK --> SKS
    SKS --> DB
    UMS --> DB
    CMS --> DB
```

There are effectively **two HTTP surfaces** sharing one process:

1. **The OAuth2 / OIDC surface** under `/t/{slug}/...`, served by Spring Authorization Server using tenant-aware decorators over its JDBC-backed storage.
2. **The management console** under `/manage/t/{slug}/...` and `/signup`, served by ordinary Spring MVC controllers and a separate filter chain.

These surfaces use the same `users` table for authentication but different filter chains and entry points.

### 4.2 Request routing for OAuth2 traffic

```mermaid
sequenceDiagram
    participant C as OAuth2 Client
    participant F as TenantOAuth2RoutingFilter
    participant SCOPE as TenantScope (ScopedValue)
    participant I as TenantIssuerContextFilter
    participant SAS as Spring Authorization Server
    participant J as TenantJwkSource

    C->>F: GET /t/acme/oauth2/authorize?...
    F->>F: regex match, extract slug "acme"
    F->>F: load Tenant from DB, check ACTIVE
    F->>SCOPE: ScopedValue.where(...).run(chain.doFilter)
    F->>I: forward, request URI rewritten to /oauth2/authorize
    I->>I: build issuer = https://host/t/acme
    I->>SAS: AuthorizationServerContext set
    SAS->>J: get JWKSet for current tenant
    J->>SCOPE: read tenantId
    J->>J: load active TenantSigningKey, decrypt
    J-->>SAS: JWKSet
    SAS-->>C: redirect / token response
    Note over F,SCOPE: scope auto-unbound when run() returns
```

The three crucial properties:

- **The tenant is resolved before SAS sees the request** — by the time Spring's filter chain runs, the URI no longer contains `/t/{slug}/` and `TenantScope` already holds the resolved tenant. This is what lets us reuse SAS unmodified.
- **The carrier is `ScopedValue`, not `ThreadLocal`.** The routing filter wraps `chain.doFilter(...)` in `ScopedValue.where(...).run(...)`; the binding is unwound automatically when `run()` returns, with no try/finally and no carrier-thread pinning under virtual threads. Seven SAS-coupled readers (`OAuth2AuthorizationService`, `OAuth2AuthorizationConsentService`, `RegisteredClientRepository`, `JWKSource`, `JwtTokenCustomizer`, the issuer-context filter, the entry point) read the scope on demand.
- **The JWKSource resolves the tenant on demand** rather than being pre-bound at bean construction, which is necessary because there is one shared SAS bean graph but many tenants.

The login path does **not** go through `TenantOAuth2RoutingFilter` (its regex matches only `oauth2|.well-known|connect|userinfo`). `/t/{slug}/login` is handled directly by the OAuth2 login filter chain (§4.5) and the slug rides on the `TenantAuthToken`, not the scope.

### 4.3 OAuth2 storage decorators

All three SAS storage interfaces are wrapped with a tenant-aware decorator. Two of them (`RegisteredClientRepository`, `OAuth2AuthorizationService`) use a *delegate-then-update* pattern: they call into the standard `JdbcXxx` implementation and then run a follow-up `UPDATE ... SET tenant_id = ?`. The third (`OAuth2AuthorizationConsentService`) writes its own SQL directly because the schema requires `tenant_id` to be part of the composite primary key, which makes the delegate-then-update approach unworkable.

The decorators all *read* by adding a `tenant_id = ?` predicate. A query that returns no row for the current Tenant looks identical to a row that does not exist, so cross-tenant lookups are invisible to the caller.

### 4.4 Signing keys

```mermaid
flowchart LR
    KEK[("LIMEN_KEY_ENCRYPTION_KEY<br/>(env)")] --> ENC
    subgraph JdbcSigningKeyStore
      GEN[Generate RSA-2048] --> SERIALISE[Serialise private key]
      SERIALISE --> ENC["Encryptors.stronger<br/>(KEK + per-key salt)"]
      ENC --> ROW
    end
    ROW[("tenant_signing_key row<br/>private_key_ciphertext, iv,<br/>public_key_jwk, status='ACTIVE'")] --> DB[(PostgreSQL)]
```

Properties:

- **One Key Encryption Key for the deployment**, supplied via `LIMEN_KEY_ENCRYPTION_KEY` (base64-encoded). Compromise of the database alone does not yield usable signing keys; compromise of the JVM process plus the database does.
- **Per-key random salt** stored in the `iv` column. The column is named `iv` for historical reasons; it is the salt passed to `Encryptors.stronger`, not an AES IV.
- **Public key stored as a JWK in plaintext** (`public_key_jwk`) so that the JWKS endpoint can be served without decrypting anything.
- **Keys are created during Tenant provisioning** by `TenantProvisioningService` calling `SigningKeyStore.createForTenant(tenantId)`. The System Tenant does not get a signing key — it never issues tokens.
- **Rotation is supported by the schema but not yet automated.** The `status` column (`ACTIVE` / `RETIRED`) plus the partial unique index allows multiple keys per Tenant with one ACTIVE; no scheduled job currently performs rotation.

### 4.5 Authentication flows

There are three login flows in the system: an OAuth2 end-user login at `/t/{slug}/login`, a management-console login at `/manage/t/{slug}/login`, and a forced-password-change interception that overlays both.

**Unified tenant-aware backend.** Both login URLs go through the same `TenantAuthProvider` (in `com.stucray.limen.auth`), against a `TenantAuthToken` whose slug is captured from the URL path before authentication runs. The provider loads the User by `(tenant_id, username)` and returns a `TenantUserDetails` whose `tenantSlug()`/`tenantId()` are used downstream. Two filter subclasses of `AbstractTenantAuthFilter` differ only in URL pattern and slug-extractor: `OAuth2TenantAuthFilter` for `/t/{slug}/login`, `ManagementTenantAuthFilter` for `/manage/t/{slug}/login`. There is no silent fallback to the System Tenant — a missing-or-mismatched slug fails the request loudly.

**Defence in depth.** A `TenantAccessFilter` runs in both UI chains: any authenticated request whose URL slug differs from the principal's tenant slug is force-logged-out and redirected to the URL slug's login. Bare `/login` 302s to `/manage/t/system/login` (matching `/`).

**Tenant-scoped remember-me.** `TenantPersistentTokenBasedRememberMeServices` encodes the slug as a third segment in the cookie value (`series:token:slug`) and rejects mismatched slugs at decode. Storage is keyed by `(tenant_id, series)` via `TenantPersistentTokenRepository`. Both filter chains share the same repository bean; management gained remember-me as part of this work (it had none previously).

**Forced password change on the OAuth2 path:**

```mermaid
sequenceDiagram
    participant U as End User
    participant SAS as SAS authorize
    participant L as /t/{slug}/login
    participant H as TenantLoginSuccessHandler
    participant CP as /t/{slug}/change-password
    participant SR as SavedRequest

    U->>SAS: GET /t/acme/oauth2/authorize?...
    SAS->>SR: save request
    SAS->>L: redirect, unauthenticated
    U->>L: POST credentials
    L->>H: success
    H->>H: load User, must_change_password = true?
    H->>CP: redirect to change-password form
    U->>CP: POST new password
    CP->>SR: replay saved /oauth2/authorize
    SR-->>U: continue OAuth2 flow
```

The same pattern applies to the management console at `/manage/t/{slug}/...`, with `PasswordChangeRequiredInterceptor` doing the interception instead of the success handler.

### 4.6 Database schema (v1)

| Migration | Purpose |
|---|---|
| `V1__initial_schema.sql` | All baseline tables (`tenants`, `users`, `persistent_logins`, `applications`, `oauth2_registered_client`, `oauth2_authorization`, `oauth2_authorization_consent`, `client_metadata`, `tenant_signing_key`) and their indexes, including the partial unique "one ACTIVE signing key per tenant" index |
| `V2__add_tenant_to_persistent_logins.sql` | Adds `tenant_id` to `persistent_logins`, swaps the primary key to `(tenant_id, series)`, and indexes `(tenant_id, username)`. `TRUNCATE`s existing rows — the conservative response to a tenant-isolation gap, since the old two-segment cookie format cannot represent the tenant either |

The schema was originally built up across 12 migrations during the v1 PRD, including two destructive `DROP TABLE / CREATE TABLE` steps to retrofit tenant scoping onto the OAuth2 storage tables. Before any production data existed, those were consolidated into the single baseline above. Future migrations must be **additive** (`ALTER TABLE ADD COLUMN ... NULL` → backfill → `ALTER ... NOT NULL`) — never `DROP TABLE` on the tenant-scoped OAuth2 tables, since they will hold live grants and consents.

### 4.7 HTTP route map

| Surface | Pattern | Notes |
|---|---|---|
| Public | `GET /signup`, `POST /signup` | Self-service Tenant creation |
| Public | `GET /login` | 302 to `/manage/t/system/login`, matching `/`. There is no bare `POST /login` — every login surface is tenant-scoped under `/t/{slug}/login` or `/manage/t/{slug}/login`. |
| OAuth2 | `GET /t/{slug}/.well-known/openid-configuration` | Per-tenant discovery |
| OAuth2 | `GET /t/{slug}/.well-known/jwks.json` | Per-tenant JWKS |
| OAuth2 | `GET /t/{slug}/oauth2/authorize` | Authorization endpoint |
| OAuth2 | `POST /t/{slug}/oauth2/token` | Token endpoint |
| OAuth2 | `POST /t/{slug}/oauth2/introspect`, `POST /t/{slug}/oauth2/revoke` | Introspection and revocation |
| OAuth2 | `GET /t/{slug}/userinfo` | OIDC UserInfo |
| OAuth2 | `GET /t/{slug}/login`, `POST /t/{slug}/login` | End-user login |
| OAuth2 | `GET /t/{slug}/change-password`, `POST /t/{slug}/change-password` | End-user forced password change, with SavedRequest resume |
| Management | `GET /manage/t/{slug}/login`, `POST /manage/t/{slug}/login` | Tenant Owner / System Admin login |
| Management | `GET /manage/t/{slug}/` | Dashboard |
| Management | `/manage/t/{slug}/applications/**` | Application + Client CRUD |
| Management | `/manage/t/{slug}/users/**` | User CRUD |
| Management | `/manage/t/{slug}/change-password` | In-console forced password change |
| System | `/manage/t/system/...` | System Admin operations: suspend / unsuspend / delete Tenants |

### 4.8 Tests

The suite is integration-heavy and runs against real Postgres via Testcontainers. The OAuth2 storage decorators each have a tenant-isolation contract test that proves a row written under Tenant A is invisible to Tenant B. The end-to-end OAuth2 flow has an integration test that exercises the full per-tenant issuer / JWKS / authorize / token chain.

There is currently no separation between unit and integration tests — the fast path is `mvn test`, which boots Testcontainers for everything.

## 5. Current Gaps and Shortcomings

These are known limitations of the v1 surface. None of them block the product working; all of them are fair targets for follow-up work.

### Authentication and identity

- **No MFA, no WebAuthn, no social login.** The PRD scoped v1 to username + password.
- **No email capability.** Forced password change exists, but there is no password-reset-via-email flow, no signup confirmation, and no notification on suspicious login. Tenant Owners must hand out temporary passwords out-of-band.
- **No account lockout or brute-force protection** on either the management login or the end-user login.
- **No session management UI.** Users cannot list or revoke their active sessions, and Tenant Owners cannot terminate a User's sessions.

### Authorization (the v2 hole)

- **Roles, Memberships and Scopes are not yet persisted.** The `roles` JWT claim is currently emitted as `[]` for everyone. This is by design for v1 but it is the largest functional gap in the product.

### Operational concerns

- **No rate limiting** on `/oauth2/token`, `/oauth2/authorize`, `/login`, or `/signup`. A single attacker can hammer the token endpoint or sign up reserved-looking slugs without friction.
- **No audit log.** Login attempts (success or failure), token issuance, client secret rotation, and Tenant lifecycle events are not recorded.
- **No metrics / observability.** Spring Boot Actuator is on the classpath but no Micrometer dashboards or counters are wired beyond defaults.
- **No signing-key rotation job.** The schema supports it; nothing schedules it.
- **No consent revocation UI.** Consents are persisted per Tenant but end-users have no way to view or revoke them; only direct DB or admin action would clear them.

### Code-level

- **No CSRF / origin checks on Client redirect URIs** beyond what Spring's `RegisteredClient` already enforces. The management UI does not validate that user-supplied redirect URIs use HTTPS, are not localhost, or are not open redirectors.
- **System Admin console is partial.** Suspend / unsuspend / delete endpoints exist; the surrounding Thymeleaf UI is thin compared to the Tenant Owner console.

### Schema and data model

- **`oauth2_registered_client` is a global SAS table.** Tenant scoping is enforced by the `client_metadata` join and by the tenant-aware decorators; nothing in the SAS table itself prevents a stray query from returning cross-tenant rows. The decorators are the only defence.

## 6. Roadmap

Roughly grouped by horizon. None of these are committed to dates.

### v2 — close the authorization hole

1. **Roles, Memberships, Scopes** — the v1 PRD explicitly deferred these. The work is to add the schema (`roles`, `application_membership`, `client_membership`, optional `client_scopes`), wire Memberships through to the `roles` JWT claim, and surface the management UI for assigning them.
2. **Application + Client membership UI** in the Tenant Owner console.

### v2.5 — operational hardening

3. **Audit log** for login attempts, token issuance, secret rotations, and Tenant lifecycle changes. Likely an append-only Postgres table plus a small viewer in the System Admin console.
4. **Rate limiting** on `/oauth2/token`, `/login`, and `/signup`. A Bucket4j-style token-bucket per IP and per (tenant, IP) is probably enough for v1.
5. **Metrics** — Micrometer counters for login success / failure, token issuance, key rotation, plus latency histograms on the token endpoint.

### v3 — identity surface

6. **Email capability** (transactional email provider integration), enabling: signup confirmation, password reset, security notifications.
7. **MFA** — TOTP first, WebAuthn / passkeys after.
8. **Session management UI** — for end-users (revoke my own sessions and consents) and for Tenant Owners (revoke a User's sessions).
9. **Signing-key rotation job** — scheduled, with overlap window so old tokens validate against the retired key during grace period.

### v3+ — platform

10. **Federation / social login** as Tenant-configurable identity providers.
11. **Per-Tenant branding** (login page logo, colour scheme, custom domain).
12. **Per-Tenant custom claims** in JWTs.
13. **Hardware-backed KEK** (KMS / HSM) instead of the env-var KEK, with envelope encryption per signing key.
