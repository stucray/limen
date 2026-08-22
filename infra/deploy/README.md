# Production deploy stack

Box-side deploy layer for the Limen VPS (PRD #339): `compose.prod.yaml` +
`Caddyfile`, consumed by `deploy.sh` (slice #342). `example.env` documents the
environment contract; real values are sops-encrypted in the private
`limen-secrets` repo. Provisioning lives in [`../ansible/`](../ansible/); the
end-to-end walk-through is
[`docs/process/vps-bootstrap.md`](../../docs/process/vps-bootstrap.md).

Validate locally without secrets:

```sh
docker compose -f compose.prod.yaml --env-file example.env config -q
```

(CI runs the same check with dummy values for the blank-in-example variables.)
