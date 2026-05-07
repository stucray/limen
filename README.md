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
