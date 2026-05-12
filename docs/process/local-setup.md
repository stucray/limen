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

### Sending via a real SMTP relay

To exercise a real provider (Resend, Brevo, SES, …) instead of Mailpit,
override the dev defaults via env vars and run with the `mailpit` profile
disabled. Resend example (using a verified-domain From-address):

```bash
mvn spring-boot:run -Dspring-boot.run.profiles= \
  -DLIMEN_EMAIL_DRIVER=smtp \
  -DLIMEN_EMAIL_FROM=no-reply@yourdomain.com \
  -DSPRING_MAIL_HOST=smtp.resend.com \
  -DSPRING_MAIL_PORT=587 \
  -DSPRING_MAIL_USERNAME=resend \
  -DSPRING_MAIL_PASSWORD=re_xxxxxxxxxxxx \
  -DSPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true \
  -DSPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true
```

In practice these live in your `.env` (gitignored); see `.env.example` for
the full set. The empty `-Dspring-boot.run.profiles=` overrides the
auto-activated `mailpit` profile so the env vars actually take effect.

## Troubleshooting

- **`direnv: error .envrc is blocked`** — you skipped `direnv allow`.
  Run it from the repo root.
- **App fails on startup with `limen.security.kek must be base64 decoding to exactly 32 bytes`** — `LIMEN_SECURITY_KEK` is missing, malformed, or the wrong length. Re-generate with `openssl rand -base64 32`.
- **App fails with `limen.bootstrap.admin.email and password must both be set or both be unset`** — set both or clear both; half-set is rejected.
- **Stale `LIMEN_BOOTSTRAP_ADMIN_*` after editing `.env`** — direnv reloads
  on `cd`, but a shell that was already in the repo before you saved the
  edit may still hold the old values. `cd ..` and back, or `direnv reload`.
