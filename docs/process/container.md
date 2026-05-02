# Pulling the Limen container image

## Where the image lives

The Limen container image is published to **GitHub Container Registry**:

```
ghcr.io/stucray/limen
```

`.github/workflows/publish-image.yml` builds and pushes on every commit to
`main`, every `v*.*.*` tag, and on manual `workflow_dispatch`. Tags emitted:

| Tag                  | When                              |
|----------------------|-----------------------------------|
| `main`               | every push to `main` (moving)     |
| `sha-<short>`        | every push to `main` (immutable)  |
| `<x.y.z>`, `<x.y>`   | semver tag push (`v1.2.3`)        |
| `latest`             | semver tag push only              |

**The package is private.** Pulls require authentication.

## Creating a pull token

Use a **classic personal access token** with the `read:packages` scope.
Fine-grained PATs do not currently grant access to user-namespaced GHCR
packages, so the classic form is required for `ghcr.io/stucray/...`.

1. Go to https://github.com/settings/tokens (Tokens — classic) → *Generate new token (classic)*
2. Scope: tick **`read:packages`** only
3. Set an expiration (90 days is a reasonable default)
4. Save the token in your password manager / secret store

In examples below, the token is referred to as `$GHCR_PAT`.

## Pulling from a local machine

```sh
echo "$GHCR_PAT" | docker login ghcr.io -u stucray --password-stdin
docker pull ghcr.io/stucray/limen:main
```

`docker login` writes the credential to `~/.docker/config.json` so
subsequent pulls don't need to re-authenticate.

## Pulling from another GitHub Actions workflow

Two steps — one in the package settings, one in the consuming workflow.

**1. Grant the consuming repo Read access**

Visit
https://github.com/users/stucray/packages/container/limen/settings →
**Manage Actions access** → *Add Repository* → pick the consuming repo
and assign the **Read** role.

**2. Use the built-in `GITHUB_TOKEN` in that repo's workflow**

```yaml
permissions:
  packages: read

steps:
  - uses: docker/login-action@v3
    with:
      registry: ghcr.io
      username: ${{ github.actor }}
      password: ${{ secrets.GITHUB_TOKEN }}
  - run: docker pull ghcr.io/stucray/limen:main
```

No PAT needed — `GITHUB_TOKEN` is sufficient once the package settings
list the repo with Read access.

## Pulling on a Docker host (VM, dev box, server)

Same as the local-machine flow:

```sh
echo "$GHCR_PAT" | docker login ghcr.io -u stucray --password-stdin
docker pull ghcr.io/stucray/limen:main
```

`docker login` stores the credential as base64 in
`~/.docker/config.json` — fine for personal hosts, not great for shared
ones. Configure a credential helper if the host is shared:

- macOS: `docker-credential-osxkeychain` (default in Docker Desktop)
- Linux: `docker-credential-secretservice` (libsecret/GNOME Keyring) or
  `docker-credential-pass` (pass + GPG)

## Pulling on Kubernetes

Create an `imagePullSecret` from the PAT:

```sh
kubectl create secret docker-registry ghcr-limen \
  --docker-server=ghcr.io \
  --docker-username=stucray \
  --docker-password="$GHCR_PAT"
```

Reference it from the pod spec:

```yaml
spec:
  imagePullSecrets:
    - name: ghcr-limen
  containers:
    - name: limen
      image: ghcr.io/stucray/limen:main
```

When the PAT expires, recreate the secret with the new value
(`kubectl delete secret ghcr-limen` first, or use `kubectl create
secret ... --dry-run=client -o yaml | kubectl apply -f -`).

## Verifying after the first workflow run

Walk this checklist once, after `publish-image.yml` has run on `main`
for the first time:

- [ ] Package appears at https://github.com/stucray?tab=packages and is
      marked **Private**
- [ ] Package settings show **Repository: `stucray/limen`** linked
      (auto-set by Paketo's OCI image labels). If the link is empty,
      add the following under `spring-boot-maven-plugin` in `pom.xml`
      and re-run the workflow:
      ```xml
      <configuration>
        <image>
          <env>
            <BP_OCI_SOURCE>https://github.com/stucray/limen</BP_OCI_SOURCE>
          </env>
        </image>
      </configuration>
      ```
- [ ] **Manage Actions access** lists `stucray/limen` with the **Write**
      role (lets future workflow runs overwrite tags)
- [ ] `docker pull ghcr.io/stucray/limen:main` from a clean machine
      returns 401 without auth (confirms private), and succeeds after
      `docker login` with a PAT
