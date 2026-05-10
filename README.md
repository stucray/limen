# Limen

A multi-tenant OAuth2 / OIDC Authorization Server built on
[Spring Authorization Server](https://spring.io/projects/spring-authorization-server).
A single deployment hosts many independent tenants — each with its own
user pool, applications, clients, signing keys, and issuer URL — created
via public self-service signup and managed through `/manage/t/{slug}/`.

> **Status**: pre-1.0, single-developer project. APIs and admin UI are
> stable enough to use, but expect breaking changes between minor versions
> until 1.0.

## Getting started

See **[docs/process/local-setup.md](docs/process/local-setup.md)** for the
first-time setup walkthrough (JDK 26, direnv, KEK generation, run).

The short version, once direnv is installed:

```bash
cp .envrc.example .envrc
cp .env.example .env
# edit .env; generate LIMEN_SECURITY_KEK with: openssl rand -base64 32
direnv allow
./mvnw spring-boot:run
```

## Running from the published image

The container image lives at `ghcr.io/stucray/limen` and is private — see
[`docs/process/container.md`](docs/process/container.md) for the
`docker login` setup. Once authenticated, bring up the dev infrastructure
and run the image against it:

```bash
docker compose up -d postgres            # publishes Postgres on host :5433
docker run --rm -p 8090:8090 \
  --network limen_default \
  -e SPRING_DATASOURCE_URL='jdbc:postgresql://postgres:5432/auth' \
  --env-file .env \
  ghcr.io/stucray/limen:latest
```

`--network limen_default` joins the compose network so the container
reaches Postgres by service name. `--env-file .env` supplies
`LIMEN_SECURITY_KEK` (required) and any other `LIMEN_*` overrides — the
image has no defaults for those, so a bare `docker run` will fail on
startup. The app listens on `http://localhost:8090`.

> Keep `LIMEN_SECURITY_KEK` stable across runs — rotating it leaves
> existing tenant signing keys in the DB un-decryptable. Wipe the
> `auth_postgres_data` volume before changing keys.

## Documentation

| Path | What's there |
|---|---|
| [`docs/reference/architecture.md`](docs/reference/architecture.md) | How the system is built — modules, persistence, security model |
| [`docs/reference/ubiquitous-language.md`](docs/reference/ubiquitous-language.md) | Domain glossary; what we call things and why |
| [`docs/process/`](docs/process/) | How-to guides: local setup, observability, container image, releases |
| [`docs/reports/`](docs/reports/) | Generated reports (test coverage and history) |
| [`docs/research/`](docs/research/) | External writeups that informed design decisions |

## License

[MIT](LICENSE).
