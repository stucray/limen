# Ubiquitous Language

## System Level

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **System Tenant** | The reserved built-in Tenant that houses System Admins; cannot be deleted | Master realm, platform |
| **System Admin** | A User in the System Tenant whose Role grants authority to operate across all Tenants | Super admin, root user, admin |

## Tenants

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **Tenant** | A named isolation boundary with its own scoped User pool, Applications, and Clients | Realm, organisation, workspace, account |
| **Tenant Owner** | A Role held by one or more Users within a Tenant, granting full management authority over it | Tenant admin, tenant manager |
| **Slug** | The URL-safe identifier for a Tenant, appearing as the path segment in `/t/{slug}/…` and `/manage/t/{slug}/…` | Tenant key, tenant name, code, handle |
| **Display Name** | The human-readable name of a Tenant, distinct from its Slug | Title, label |
| **Tenant Isolation** | The guarantee that data, credentials, sessions, and cookies scoped to one Tenant cannot be accessed from another Tenant's HTTP surface | Tenant separation, multi-tenancy boundary |

## Identity

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **User** | A single entity type representing any authenticated identity, always scoped to exactly one Tenant | Principal, account, member |
| **Username** | The unique string a User uses to identify themselves at login within their Tenant | Login name, email, identifier |
| **Password Hash** | The BCrypt-encoded form of a User's password stored in the database | Encrypted password |
| **Enabled** | A flag indicating whether a User may authenticate | Active, unlocked |

## Applications and Clients

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **Application** | A named grouping of Clients within a Tenant that defines the Roles available across those Clients | Project, service, suite |
| **Client** | An OAuth2 client registration belonging to an Application | Registered client, app |
| **Client ID** | The public identifier for a Client | App ID |
| **Client Secret** | The credential a Client presents when authenticating with the server | App secret, API key |
| **Redirect URI** | The URL to which the server returns an Authorization Code after a User authorises | Callback URL, return URL |
| **Authorization Grant Type** | The OAuth2 flow a Client is permitted to use | Grant, flow type |

## Access Control

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **Application Membership** | A User's grant of access to an Application, carrying zero or more App Roles. Eligibility prerequisite for any Client Membership in the same Application | App membership, app assignment |
| **Client Membership** | A User's grant of access to a specific Client within an Application, carrying zero or more Client Roles. Required for the User to complete `/oauth2/authorize` against that Client | Client assignment |
| **Membership** | Umbrella term covering both Application Membership and Client Membership; always qualify in writing if the distinction matters | Assignment, enrollment, participation |
| **App Role** | A Role attached to an Application Membership. Governs management-console authority over the Application. Never appears in JWTs | Application role, console role |
| **Client Role** | A Role attached to a Client Membership. Emitted as a value in the `roles` claim of JWTs issued for that Client | Runtime role |
| **Role** | A named string defined per Application (`role` table). The same Role can be assigned as either an App Role or a Client Role; its meaning depends on which Membership type it is attached to. Distinct from tenant-level "Role" — see disambiguation below | Authority, permission, grant |
| **Tenant-level Role** | An out-of-band administrative status implemented as a boolean column on `users` — currently `tenant_owner` (Tenant Owner) and the System-Tenant-only equivalent (System Admin). Not a row in the `role` table; never appears in JWTs | App-level role |
| **Scope** | An OAuth2 permission defined per Client, requested during token flows, and emitted as the `scope` claim in JWTs | Role, permission |

## Authorization & Consent

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **Authorization** | A record that a User has granted a Client permission to act on their behalf | Grant, approval |
| **Authorization Code** | A short-lived, single-use code exchanged for Tokens in the Authorization Code Flow | Code, auth code |
| **Authorization Consent** | The persisted record of Scopes a User has approved for a specific Client | Approved scopes |

## Tokens

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **Access Token** | A signed JWT granting the bearer access to a protected resource for a limited time | Bearer token, API token |
| **Refresh Token** | A long-lived token used to obtain a new Access Token without re-authenticating | Session token |
| **ID Token** | A signed JWT containing identity claims about the authenticated User (OIDC) | Identity token |
| **Signing Key** | The RSA private key the Authorization Server uses to sign all JWTs | Private key, secret key |
| **Key Encryption Key** | The deployment-wide AES-256 key, supplied via `LIMEN_KEY_ENCRYPTION_KEY`, that encrypts every Tenant's Signing Key at rest | KEK, master key, root key, encryption secret |
| **JWK** | A JSON Web Key — the public-key representation of the Signing Key exposed to clients | Public key |
| **JWK Set** | The collection of JWKs published at `/oauth2/jwks` for token verification | Key set, JWKS |

## Login Surfaces

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **Landing Page** | The public page served at `/`. Carries a sign-in form (slug input that forwards to the matching **Management Login**) and a sign-up call-to-action. Is **not** a login surface itself — there is no bare `POST /login` | Home page, root |
| **Management Login** | The login surface at `/manage/t/{slug}/login` used by Tenant Owners and System Admins to access the management console | Console login, admin login |
| **End-User Login** | The login surface at `/t/{slug}/login` used by OAuth2 end-users during the authorization code flow | OAuth2 login, app login |
| **Forced Password Change** | The state, signalled by the `must_change_password` flag on a User, requiring a new password to be set before any other authenticated action proceeds | Password reset, mandatory change |

## Sessions & Persistent Authentication

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **Session** | A server-side record of an authenticated User's HTTP context, identified by `JSESSIONID` | Login session |
| **Persistent Login** | A database-backed remember-me token enabling re-authentication across browser restarts; bound to exactly one Tenant | Remember-me, stay logged in |
| **Series** | The stable identifier for a Persistent Login chain; rotated on compromise detection | Token ID |

## Authorization Server

| Term | Definition | Aliases to avoid |
| --- | --- | --- |
| **Authorization Server** | The Limen service itself — issues Tokens and manages the OAuth2/OIDC lifecycle | Auth server, identity server |
| **Issuer** | The canonical URI of the Authorization Server embedded in every issued Token | Server URL |
| **PKCE** | Proof Key for Code Exchange — a security extension requiring a Code Verifier and Code Challenge in the Authorization Code Flow | — |
| **Code Verifier** | A random secret generated by the client and later sent to prove it initiated the flow | — |
| **Code Challenge** | The hashed Code Verifier sent in the authorization request before the secret is revealed | — |

## Relationships

- Each **Tenant** has a unique **Slug** and an independent **Display Name**
- **Tenant Isolation** applies across credentials, **Sessions**, **Persistent Logins**, OAuth2 **Authorizations**, and **Authorization Consents** — none of these may cross **Tenant** boundaries
- A **User** belongs to exactly one **Tenant** (including the **System Tenant**)
- The **Landing Page** at `/` is the only public, non-tenant-scoped surface other than `/signup`. Bare `/login` is a slug-aware forwarder, not a login surface: with `?slug=X` it 302s to `/manage/t/X/login`, otherwise to the **Landing Page**
- **Management Login** and **End-User Login** both validate against the **User** pool of the named **Tenant** — the same **Username** in two **Tenants** authenticates two different **Users**
- A **Persistent Login** is bound to one **Tenant**; presenting its cookie at another **Tenant**'s URL is rejected
- A **User** with **Forced Password Change** set must complete it before any **Session** or **Authorization** proceeds
- A **Tenant** contains zero or more **Applications**
- An **Application** contains zero or more **Clients** and defines the **Roles** assignable as either **App Roles** or **Client Roles** under it
- A **User** may have an **Application Membership** in an Application and/or a **Client Membership** in individual Clients within it
- **Application Membership** makes a **User** eligible for **Client Membership** but does not automatically grant it — **Client Membership** is always explicit. Revoking an **Application Membership** cascades to revoke all of that User's **Client Memberships** under that Application
- Each **Membership** carries zero or more **Roles**; an empty Role set is a valid Membership and means the User has access but no specific authority
- Only **Client Roles** travel in JWTs as the `roles` claim — App Roles never do. **Tenant-level Roles** (Tenant Owner, System Admin) also never appear in JWTs. **Scopes** travel as the `scope` claim — they are distinct concepts serving different consumers
- A User without a **Client Membership** for the requested Client is rejected at `/oauth2/authorize` with `access_denied`, even after a successful End-User Login
- A **User** may have zero or more **Persistent Logins** (one per device/browser)
- Each **Authorization** contains at most one **Authorization Code**, one **Access Token**, one **Refresh Token**, and one **ID Token**
- An **Authorization Consent** belongs to exactly one **User** and one **Client**
- The **Authorization Server** signs all Tokens with the **Signing Key** and publishes the corresponding **JWK Set** for verification
- Each **Tenant** has its own **Signing Key**; every **Signing Key** is encrypted at rest by the single deployment-wide **Key Encryption Key**

## Example dialogue

> **Dev:** "A new customer wants to use Limen. What do we create for them?"

> **Domain expert:** "First create a **Tenant**. Then create a **User** in that **Tenant** and assign them the **Tenant Owner** Role — that gives them management authority over the Tenant."

> **Dev:** "They have two products, each with a web frontend and a mobile app. How does that map?"

> **Domain expert:** "Each product is an **Application**. Define the **Roles** on the **Application** — say `viewer` and `editor`. Then register a **Client** per OAuth2 integration: web and mobile each get their own **Client** under their **Application**."

> **Dev:** "When an end-user logs in via a **Client**, how does the resource server know what they can do?"

> **Domain expert:** "The resource server reads the `roles` claim in the JWT. That's populated from the **User**'s **Membership** in that **Client** — specifically their **Roles** on the **Membership**. The `scope` claim is separate; it reflects what the **Client** is permitted to request, not who the **User** is."

> **Dev:** "Can I assign someone to the whole **Application** so they automatically get access to all its **Clients**?"

> **Domain expert:** "**Application Membership** makes them eligible, but each **Client Membership** must still be created explicitly. Nothing is inherited automatically."

> **Dev:** "What signs the **Tokens** the **Authorization Server** issues?"

> **Domain expert:** "Each **Tenant** has its own **Signing Key**, generated when the **Tenant** is provisioned. The private half is stored encrypted under the deployment's **Key Encryption Key**; the public half is served from the **JWK Set**."

> **Dev:** "If two **Tenants** both have a **User** named `alice`, what stops the wrong **User** from authenticating?"

> **Domain expert:** "**Tenant Isolation**. **Management Login** and **End-User Login** both carry the **Slug** from the URL into the auth backend, so credentials are validated against `(tenant_id, username)`, not `username` alone. A **Persistent Login** for tenant A presented at tenant B's URL is also rejected — the cookie is bound to the **Tenant** that issued it."

> **Dev:** "And **Forced Password Change**?"

> **Domain expert:** "Set the flag on a **User** and their next successful authentication — either surface — diverts to a change-password form before any **Session** is fully established or any **Authorization** can resume."

## Flagged ambiguities

- **"User"** was previously used only for management-level identities. It now covers all authenticated identities — management users and OAuth2 end-users are the **same entity type**, differentiated by **Role** and **Membership**, not by entity type. Do not introduce separate entity types for these personas.
- **"Principal"** must be avoided — it has a specific meaning in Spring Security (`java.security.Principal`) and will cause confusion in a Spring Boot codebase.
- **"Registered Client"** is a Spring implementation term (`RegisteredClient`). Use **Client** in all domain conversations and documentation.
- **"Role" vs "Scope"** are frequently conflated. **Role** is a management concept (assigned to Users via Memberships, defined per Application, `roles` JWT claim, consumed by Spring Resource Servers). **Scope** is an OAuth2 wire concept (defined per Client, requested during token flows, `scope` JWT claim). Both appear in JWTs but as separate claims.
- **"Role"** is itself overloaded across two planes. The `role` table is **per Application** and holds the Roles assignable as either an **App Role** (Application Membership) or a **Client Role** (Client Membership). The JWT `roles` claim only contains **Client Roles** for the Client being authorized — App Roles never travel in JWTs. Separately, **Tenant Owner** and **System Admin** are *tenant-level* Roles implemented as boolean flags on `users` (`tenant_owner` and the System-Tenant equivalent) — they are not rows in the `role` table and they govern console authority, not JWT contents. In writing, qualify as **App Role**, **Client Role**, **Tenant Owner**, or **System Admin** rather than the bare word "role" wherever the plane is not obvious from context.
- **"Authorization"** is overloaded in Spring Security (the process of checking permissions) and in the OAuth2 domain (a persisted grant record). In this codebase, **Authorization** means the OAuth2 grant record. The process of checking permissions should be called **access control**.
- **"Token"** alone is ambiguous. Always qualify with the type: **Access Token**, **Refresh Token**, **ID Token**.
- **"Admin"** unqualified is ambiguous now that both **System Admin** and **Tenant Owner** exist. Always qualify.
- **"Login"** unqualified is ambiguous between **Management Login** (`/manage/t/{slug}/login`) and **End-User Login** (`/t/{slug}/login`). They share an auth backend but serve different actors and different post-login destinations — always qualify.
- **"Slug"** vs **"Display Name"** must be kept distinct. The **Slug** is the URL identifier and is immutable in practice; the **Display Name** is the human-readable label and may change. Avoid "tenant name" — it conflates the two.
- **"TenantContext"** (the deprecated `ThreadLocal` carrier) is no longer in the codebase. Per-request tenant binding is now `TenantScope` — a Spring/Loom-friendly `ScopedValue`. This is implementation detail rather than a domain term, but stale references in old discussions should be read as `TenantScope`.
