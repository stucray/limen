#!/usr/bin/env bash
# Operator-gated, box-side deploy for Limen (PRD #339, slice #342).
#
#   deploy.sh [--dry-run] <release-tag>     e.g. deploy.sh v0.4.0
#
# Checks out the release tag in this clone (pinning config, migrations, and
# image version together), fast-forwards the secrets clone, decrypts the
# production env via sops, pulls the pinned image, brings the stack up, and
# waits on health. Rollback is the same command with the previous tag — but
# mind Flyway's forward-only schema: rolling code back past a destructive
# migration is not safe (see docs/process/deploy.md).
#
# Expects on the box: the limen clone (this file), the limen-secrets clone
# (~/limen-secrets or $LIMEN_SECRETS_DIR), sops + the box age key at sops'
# default age location, docker with the compose plugin, GHCR login.
set -euo pipefail

DRY_RUN=0

usage() {
  echo "usage: deploy.sh [--dry-run] <release-tag>   (tag form: vX.Y.Z)" >&2
  exit 64
}

run() {
  if ((DRY_RUN)); then
    echo "[dry-run] $*"
  else
    "$@"
  fi
}

main() {
  local tag=""
  while (($#)); do
    case "$1" in
      --dry-run) DRY_RUN=1 ;;
      -*) usage ;;
      *)
        [[ -n "$tag" ]] && usage
        tag="$1"
        ;;
    esac
    shift
  done
  [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || usage
  local version="${tag#v}"

  local script_dir repo_dir
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  repo_dir="$(git -C "$script_dir" rev-parse --show-toplevel)"
  local secrets_dir="${LIMEN_SECRETS_DIR:-$HOME/limen-secrets}"

  echo "==> Pinning release $tag"
  run git -C "$repo_dir" fetch --tags origin
  if ((!DRY_RUN)); then
    git -C "$repo_dir" rev-parse -q --verify "refs/tags/$tag" >/dev/null \
      || { echo "error: tag $tag not found after fetch" >&2; exit 1; }
  fi
  run git -C "$repo_dir" checkout -q --detach "refs/tags/$tag"

  echo "==> Refreshing secrets clone"
  run git -C "$secrets_dir" pull --ff-only

  echo "==> Decrypting production env"
  local env_file="(decrypted-env)" domain="<LIMEN_DOMAIN>"
  if ((!DRY_RUN)); then
    umask 077
    env_file="$(mktemp)"
    # shellcheck disable=SC2064 # expand env_file now, not at exit
    trap "rm -f '$env_file'" EXIT
    sops decrypt "$secrets_dir/prod.env" > "$env_file"
    printf 'LIMEN_VERSION=%s\n' "$version" >> "$env_file"
    domain="$(grep -E '^LIMEN_DOMAIN=' "$env_file" | cut -d= -f2-)"
    [[ -n "$domain" ]] || { echo "error: LIMEN_DOMAIN missing from prod.env" >&2; exit 1; }
  else
    echo "[dry-run] sops decrypt $secrets_dir/prod.env; append LIMEN_VERSION=$version"
  fi

  local compose=(docker compose -f "$script_dir/compose.prod.yaml" --env-file "$env_file")

  echo "==> Pulling image ghcr.io/stucray/limen:$version"
  run "${compose[@]}" pull

  echo "==> Starting stack"
  run "${compose[@]}" up -d --remove-orphans

  if ((DRY_RUN)); then
    echo "[dry-run] wait for limen container health, then check https://$domain/actuator/health"
    echo "[dry-run] done — no changes made"
    return 0
  fi

  echo "==> Waiting for limen container health"
  local cid state deadline=$((SECONDS + 180))
  cid="$("${compose[@]}" ps -q limen)"
  while :; do
    state="$(docker inspect -f '{{.State.Health.Status}}' "$cid")"
    [[ "$state" == "healthy" ]] && break
    if ((SECONDS >= deadline)) || [[ "$state" == "unhealthy" ]]; then
      echo "error: limen container is $state — recent logs:" >&2
      "${compose[@]}" logs --tail=80 limen >&2
      exit 1
    fi
    sleep 5
  done

  echo "==> Checking end-to-end health via the edge"
  if ! curl -fsS --max-time 10 "https://$domain/actuator/health" | grep -q '"UP"'; then
    echo "error: https://$domain/actuator/health not UP — recent logs:" >&2
    "${compose[@]}" logs --tail=80 limen >&2
    exit 1
  fi

  echo "==> Deployed $tag"
  "${compose[@]}" ps
}

main "$@"
# Explicit exit so a mid-run `git checkout` of this file can never affect the
# already-running invocation past this point.
exit
