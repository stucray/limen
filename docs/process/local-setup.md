# Local development setup

First-time setup for running Limen on your own machine. After these steps
you'll be able to `./mvnw spring-boot:run` and reach the app on
<http://localhost:8090> (set via `server.port` in `application.yaml`).

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 26 | `pom.xml` pins `<java.version>26</java.version>`. Older JDKs won't compile. |
| Docker | recent | For Postgres (auto-started by Spring Boot Docker Compose support) and the optional observability stack. |
| [direnv](https://direnv.net) | recent | Loads `.env` into your shell so `mvn spring-boot:run` sees `LIMEN_SECURITY_KEK` etc. without manual exports. |

Maven itself doesn't need to be installed — the bundled wrapper (`./mvnw`)
takes care of it.

## Install direnv

macOS (Homebrew):

```bash
brew install direnv
```

Linux: use your package manager (`apt install direnv`, `dnf install direnv`,
…) or follow <https://direnv.net/docs/installation.html>.

Hook it into your shell so it auto-loads `.envrc` files when you `cd` into
the repo. Add **one** of the following to your shell rc, then start a new
shell (`exec $SHELL`):

```bash
# ~/.zshrc
eval "$(direnv hook zsh)"

# ~/.bashrc
eval "$(direnv hook bash)"

# ~/.config/fish/config.fish
direnv hook fish | source
```

zsh users on macOS can shortcut this with the bundled
`scripts/install-direnv-hook.sh` (idempotent; appends the zsh hook to
`~/.zshrc` and runs `direnv allow` on the repo).

## Configure the repo

From the repo root:

```bash
cp .envrc.example .envrc
cp .env.example .env
```

Edit `.env` and fill in the required values:

- **`LIMEN_SECURITY_KEK`** — 32-byte AES key, base64-encoded. Generate a
  fresh one with `openssl rand -base64 32`. Required; the app fails fast
  without it.
- **`LIMEN_SECURITY_KEK_PREVIOUS`** — optional. Set this to the prior
  `LIMEN_SECURITY_KEK` while rotating the deployment KEK; reads that fail
  to decrypt with the active key try this one as a fallback and lazily
  re-wrap the row with the active KEK + a fresh salt on success. Remove
  it once you're confident no rows are still wrapped under the old value.
- **`LIMEN_BOOTSTRAP_ADMIN_EMAIL`** / **`LIMEN_BOOTSTRAP_ADMIN_PASSWORD`** —
  optional. If both are set, a system-admin account is created on first
  boot. If both are left blank, create the first admin via signup instead.
  They must be set together or both blank.

Then approve the `.envrc` (one-time per repo path):

```bash
direnv allow
```

You'll see direnv export the variables on your next prompt.

## Run

```bash
./mvnw spring-boot:run
```

Spring Boot's Docker Compose support auto-starts the Postgres container
from `docker-compose.yml`. The app is at <http://localhost:8090>.

The `mailpit` profile auto-activates on `mvn spring-boot:run` (configured
on `spring-boot-maven-plugin`), so SMTP outbound email lands in the
Mailpit web inbox at <http://localhost:8025>. To run with the dev
observability stack instead, see [observability.md](observability.md).

### Profiles inventory

Three Spring profiles, each single-purpose, each activated automatically
by its context:

| Profile  | Activated by | Purpose |
|----------|--------------|---------|
| `mailpit` | `spring-boot-maven-plugin` `<profiles>` config in `pom.xml` — auto on `mvn spring-boot:run` | Dev SMTP via the Mailpit container in `docker-compose.yml` (web inbox at <http://localhost:8025>) |
| `test`    | `@ActiveProfiles("test")` on `@SpringBootTest` classes | Test-DB + Testcontainer wiring; loads `application-test.yaml` |
| `resend`  | `SPRING_PROFILES_ACTIVE=resend` in the deployment env | Production SMTP via Resend (`smtp.resend.com:587`, STARTTLS required) |

The `resend` profile is vendor-shaped, not environment-shaped — it encodes
the Resend SMTP wiring, not the notion of "production." Staging and prod
both activate it; only `LIMEN_EMAIL_FROM` and `SPRING_MAIL_PASSWORD` vary
per-environment.

### Sending via Resend

Activate the `resend` profile and set two env vars:

```bash
SPRING_PROFILES_ACTIVE=resend \
LIMEN_EMAIL_FROM=no-reply@your-verified-domain.com \
SPRING_MAIL_PASSWORD=re_xxxxxxxxxxxx \
java -jar target/limen-*.jar
```

The profile (`src/main/resources/application-resend.yaml`) bakes in the
Resend SMTP shape — host, port, username, STARTTLS-required — so deploy-time
config is just the From-address and the API key. If `LIMEN_EMAIL_FROM` is
unset under the `resend` profile, the context fails to start (`@NotBlank`
on `EmailProperties.from`) — a refused deploy rather than a runtime 500.

Before your domain's DNS is verified in the Resend dashboard, smoke-test
with `LIMEN_EMAIL_FROM=onboarding@resend.dev`. Resend will only deliver
from that address to your own logged-in Resend-account email, but it's
enough to confirm the API key, profile activation, and the SMTP wiring
are all correct.

## Registering a loopback redirect URI

When a client is registered with an HTTP loopback redirect URI
(`http://127.0.0.1:<port>/<path>` or the IPv6 form
`http://[::1]:<port>/<path>`), the port is wildcarded at sign-in and
sign-out: register the URI once with any port, and the OAuth2 client may
use any port it likes on `/authorize` and on RP-initiated
`/connect/logout`. Same rule applies to the post-logout redirect URI
registration. This mirrors RFC 8252 §7.3 and removes the registration
ceremony when a consumer rotates between local stack shapes (dev server,
direct AS-client port, prod-shape rehearsal, e2e harness — each on a
different port).

Canonical example: register once with

```
http://127.0.0.1:8080/callback
```

then any of `http://127.0.0.1:5173/callback`,
`http://127.0.0.1:9001/callback`, … is accepted at request time.

Caveats:

- **`localhost` is NOT a loopback host for matching purposes** —
  registering `http://localhost:8080/callback` requires the request to
  hit `http://localhost:8080/callback` exactly (port included). RFC 8252
  §8.3 + Spring Authorization Server policy.
- **HTTPS loopback is exact-port-only** —
  `https://127.0.0.1:<port>/<path>` requires the request port to match
  the registered port. The relaxation is HTTP-only per RFC 8252 §7.3.
- **Scheme, host, and path still match exactly** — only the port is
  wildcarded.

## Troubleshooting

- **`direnv: error .envrc is blocked`** — you skipped `direnv allow`.
  Run it from the repo root.
- **App fails on startup with `limen.security.kek must be base64 decoding to exactly 32 bytes`** — `LIMEN_SECURITY_KEK` is missing, malformed, or the wrong length. Re-generate with `openssl rand -base64 32`.
- **`/oauth2/token` returns `500 server_error` with `"Unable to invoke Cipher due to bad padding"` in the server log** — the active `LIMEN_SECURITY_KEK` doesn't match the KEK that wrapped the tenant's signing key at insert time. If you've just rotated KEKs, set the prior value as `LIMEN_SECURITY_KEK_PREVIOUS` so reads fall back to it and lazily re-wrap on success. If neither key works the row is unrecoverable — easiest reset in dev is `docker compose down -v` to start with an empty database.
- **App fails with `limen.bootstrap.admin.email and password must both be set or both be unset`** — set both or clear both; half-set is rejected.
- **Stale `LIMEN_BOOTSTRAP_ADMIN_*` after editing `.env`** — direnv reloads
  on `cd`, but a shell that was already in the repo before you saved the
  edit may still hold the old values. `cd ..` and back, or `direnv reload`.
