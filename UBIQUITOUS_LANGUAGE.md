# Ubiquitous Language

## Identity

| Term             | Definition                                                                 | Aliases to avoid              |
| ---------------- | -------------------------------------------------------------------------- | ----------------------------- |
| **User**         | A human identity stored in the system with a username and hashed password  | Account, login, principal     |
| **Username**     | The unique string a User uses to identify themselves at login              | Login name, email, identifier |
| **Password Hash** | The BCrypt-encoded form of a User's password stored in the database       | Encrypted password            |
| **Enabled**      | A flag indicating whether a User may authenticate                          | Active, unlocked              |

## OAuth2 Clients

| Term                      | Definition                                                                              | Aliases to avoid         |
| ------------------------- | --------------------------------------------------------------------------------------- | ------------------------ |
| **Registered Client**     | An OAuth2 application registered with the Authorization Server that may request tokens | App, consumer, service   |
| **Client ID**             | The public identifier for a Registered Client                                           | App ID                   |
| **Client Secret**         | The credential a Registered Client presents when authenticating with the server        | App secret, API key      |
| **BFF Client**            | A Registered Client representing a Backend-for-Frontend web application                | Frontend client          |
| **Redirect URI**          | The URL to which the server returns an Authorization Code after a User authorizes       | Callback URL, return URL |
| **Authorization Grant Type** | The OAuth2 flow a Registered Client is permitted to use                            | Grant, flow type         |

## Authorization & Consent

| Term                       | Definition                                                                                     | Aliases to avoid         |
| -------------------------- | ---------------------------------------------------------------------------------------------- | ------------------------ |
| **Authorization**          | A record that a User has granted a Registered Client permission to act on their behalf         | Grant, approval          |
| **Authorization Code**     | A short-lived, single-use code exchanged for Tokens in the Authorization Code Flow             | Code, auth code          |
| **Authorization Consent**  | The persisted record of scopes a User has approved for a specific Registered Client            | Approved scopes          |
| **Scope**                  | A named permission (e.g. `openid`, `profile`) that a Registered Client requests from a User   | Permission, role         |

## Tokens

| Term              | Definition                                                                            | Aliases to avoid              |
| ----------------- | ------------------------------------------------------------------------------------- | ----------------------------- |
| **Access Token**  | A signed JWT granting the bearer access to a protected resource for a limited time   | Bearer token, API token       |
| **Refresh Token** | A long-lived token used to obtain a new Access Token without re-authenticating       | Session token                 |
| **ID Token**      | A signed JWT containing identity claims about the authenticated User (OIDC)          | Identity token                |
| **Signing Key**   | The RSA private key the Authorization Server uses to sign all JWTs                   | Private key, secret key       |
| **JWK**           | A JSON Web Key — the public-key representation of the Signing Key exposed to clients | Public key                    |
| **JWK Set**       | The collection of JWKs published at `/oauth2/jwks` for token verification            | Key set, JWKS                 |

## Sessions & Persistent Authentication

| Term                  | Definition                                                                                      | Aliases to avoid          |
| --------------------- | ----------------------------------------------------------------------------------------------- | ------------------------- |
| **Session**           | A server-side record of an authenticated User's HTTP context, identified by `JSESSIONID`       | Login session             |
| **Persistent Login**  | A database-backed remember-me token enabling re-authentication across browser restarts         | Remember-me, stay logged in |
| **Series**            | The stable identifier for a Persistent Login chain; rotated on compromise detection            | Token ID                  |

## Authorization Server

| Term                          | Definition                                                                                  | Aliases to avoid             |
| ----------------------------- | ------------------------------------------------------------------------------------------- | ---------------------------- |
| **Authorization Server**      | The Limen service itself — issues Tokens and manages the OAuth2/OIDC lifecycle             | Auth server, identity server |
| **Issuer**                    | The canonical URI of the Authorization Server embedded in every issued Token                | Server URL                   |
| **PKCE**                      | Proof Key for Code Exchange — a security extension requiring a Code Verifier and Code Challenge in the Authorization Code Flow | —          |
| **Code Verifier**             | A random secret generated by the client and later sent to prove it initiated the flow      | —                            |
| **Code Challenge**            | The hashed Code Verifier sent in the authorization request before the secret is revealed   | —                            |
| **Bootstrap**                 | The startup process that seeds the Admin User and BFF Client if they do not yet exist      | Seed, init, setup            |

## Relationships

- A **User** may have zero or more **Persistent Logins** (one per device/browser).
- A **Registered Client** may hold zero or more **Authorizations** from different Users.
- Each **Authorization** contains at most one **Authorization Code**, one **Access Token**, one **Refresh Token**, and one **ID Token**.
- An **Authorization Consent** belongs to exactly one **User** and one **Registered Client**.
- The **Authorization Server** signs all Tokens with the **Signing Key** and publishes the corresponding **JWK Set** for verification.

## Example dialogue

> **Dev:** "When a **User** first logs in through a **BFF Client**, does the **Authorization Server** create an **Authorization** immediately?"

> **Domain expert:** "Not until the **User** approves the **Authorization Consent**. If the **BFF Client** is configured to skip consent, the **Authorization** is created automatically — otherwise the User must confirm the requested **Scopes**."

> **Dev:** "And the **Authorization Code** — when does that expire?"

> **Domain expert:** "It's single-use and very short-lived. The **BFF Client** must exchange it plus the **Code Verifier** at `/oauth2/token` before it expires, and the server hands back an **Access Token**, **Refresh Token**, and **ID Token**."

> **Dev:** "So the **Access Token** is what the client sends to downstream APIs?"

> **Domain expert:** "Exactly. Downstream services verify it by fetching the **JWK Set** and checking the signature against the **Signing Key**'s public half. The **Issuer** claim in the token tells them which **Authorization Server** signed it."

## Flagged ambiguities

- **"Authorization"** is overloaded in Spring Security (the process of checking permissions) and in the OAuth2 domain (a persisted grant record). In this codebase, prefer **Authorization** to mean the OAuth2 grant record (`oauth2_authorization` table). The process of checking permissions should be called **access control**, not "authorization."
- **"Token"** alone is ambiguous — it could mean an **Access Token**, **Refresh Token**, **ID Token**, **Authorization Code**, or **Persistent Login** token. Always qualify with the type.
- **"Admin"** appears in bootstrap env vars (`OVERROUND_ADMIN_USERNAME`) but there is no distinct Admin role in the domain model — the bootstrapped User holds only `ROLE_USER`. Avoid calling this user the "Admin User" unless a privileged role is later introduced.
