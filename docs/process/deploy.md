# Deploying Limen to the VPS

Operator-gated, box-side deploys (PRD #339). The release pipeline's job ends
at "image tagged and in GHCR" ([`container.md`](container.md)); putting a
release into production is a separate, deliberate act performed on the box.
Provisioning the box itself is covered by
[`vps-bootstrap.md`](vps-bootstrap.md) plus the deploy-toolchain Ansible
tasks (slice #343).

## Prerequisites (once)

- Box bootstrapped and provisioned: Docker, sops + age, the `limen` and
  `limen-secrets` clones under the deploy user, GHCR login, box age key
  registered as a sops recipient (slices #341/#343).
- DNS A record for the public domain pointing at the box; the domain
  verified at Resend.

## Deploy

```sh
ssh vps
~/limen/infra/deploy/deploy.sh v0.4.0        # or --dry-run first
```

The script checks out the tag (pinning config, migrations, and image version
from one checkout), fast-forwards the secrets clone, decrypts `prod.env` via
sops to a mode-600 tempfile (removed on exit), pulls
`ghcr.io/stucray/limen:<version>`, runs `docker compose up -d`, waits for the
container healthcheck, then asserts `https://<domain>/actuator/health` is UP
through the edge. On failure it prints the last 80 app-log lines and exits
non-zero.

**Watch the logs on any deploy that ships a migration** — Flyway runs
in-process on startup, so a failed migration surfaces as a crash-looping
container, and the real error is in the startup log:

```sh
docker compose -f ~/limen/infra/deploy/compose.prod.yaml logs -f limen
```

## Rollback

Symmetric by design:

```sh
~/limen/infra/deploy/deploy.sh v0.3.9
```

**Schema caveat:** Flyway is forward-only. Rolling code back *under* an
additive migration (new table, nullable column) is safe; rolling back past a
destructive one (drop/rename) is not — old code meets a schema it doesn't
understand. Destructive migrations therefore deserve extra release-time care;
prefer expand/contract so the rollback property holds.

## Rebuild from scratch

The box is disposable; the only irreplaceable state is the Postgres volume
(off-box backups are a follow-up PRD — until they ship, treat the box as
precious after first real data).

1. Provision a fresh box: [`vps-bootstrap.md`](vps-bootstrap.md), real IP in
   the Ansible inventory, run the playbook.
2. Ansible generates a new box age key — add its public half as a recipient
   in `limen-secrets` (`sops updatekeys`), commit, push. The old box key is
   dead; nothing to revoke beyond removing the recipient.
3. Register the new box deploy key on the `limen-secrets` repo.
4. `deploy.sh <current-release-tag>`.
5. Restore the database when backups exist; until then, first boot re-seeds
   the bootstrap admin from secrets.
6. Update the DNS A record if the IP changed.

## Verifying a graceful stop

`docker compose stop limen` should exit the container with code **143**
(SIGTERM handled, shutdown hooks ran — check for Tomcat's "Graceful shutdown
complete" in the logs). **137** means the 40s `stop_grace_period` elapsed and
Docker force-killed it. Exit 0 is *not* expected from a JVM container.
