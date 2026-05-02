# Cutting a release

This repo's release process is fully automated by `.github/workflows/release.yml`.
One click in the Actions tab cuts a versioned release: it strips `-SNAPSHOT`,
runs the test suite, tags `vX.Y.Z`, publishes the container image, creates a
GitHub Release, and bumps `main` to the next development snapshot.

## What the workflow does

When you click *Run workflow*, in order:

1. Checks out `main` and reads `<java.version>` from `pom.xml`
2. Computes the release version (current pom version with `-SNAPSHOT` stripped)
   and the next snapshot version (patch+1, with `-SNAPSHOT` re-appended)
3. Refuses to run if any guard fails: current version isn't a snapshot, the
   `vX.Y.Z` tag already exists on origin, or `next_version` doesn't end in
   `-SNAPSHOT`
4. Rewrites `pom.xml` to the release version via `versions:set`
5. Runs `./mvnw verify` — the same pipeline `ci.yml` runs (Error Prone +
   NullAway + tests + JaCoCo + PMD)
6. Commits `Release X.Y.Z` and tags `vX.Y.Z`
7. Rewrites `pom.xml` to the next snapshot version and commits
   `Prepare for next development iteration: X.Y.Z+1-SNAPSHOT`
8. Pushes both commits and the tag to `main` atomically (`git push --atomic`)
9. Creates a GitHub Release with auto-generated notes (PR titles since the
   previous tag)
10. The tag push triggers `publish-image.yml`, which builds and pushes
    `ghcr.io/stucray/limen:X.Y.Z`, `:X.Y`, and `:latest` to GHCR

## One-time setup

Before the first release ever, two manual steps. (Already done? Skip to the
next section.)

### 1. Create a fine-grained PAT

Go to https://github.com/settings/personal-access-tokens → *Generate new token*:

- **Resource owner**: your account
- **Repository access**: *Only select repositories* → `stucray/limen`
- **Repository permissions**:
  - **Contents**: Read and write
- **Expiration**: 90 days (set a calendar reminder for renewal)

Save the token value somewhere safe — you can't see it again after this page.

### 2. Store the PAT as a repo secret

Repo → Settings → Secrets and variables → Actions → *New repository secret*:

- **Name**: `RELEASE_TOKEN` (must be this exact name)
- **Value**: the PAT

That's it. No ruleset changes are needed because the existing `main-protection`
ruleset already lets the Admin role bypass the PR-required rule, and the PAT
carries your admin role.

## Cutting a release

### First, dry-run

Always dry-run the first release of a session, especially for the first-ever
release of the project.

1. Repo → Actions → **Release** → *Run workflow*
2. Branch: `main`
3. Tick **Dry run**
4. Leave **Release version** and **Next development version** blank
5. *Run workflow*

In the run summary you'll see:

```
## Plan
- Current: 0.0.1-SNAPSHOT
- Release: 0.0.1  (tag v0.0.1)
- Next:    0.0.2-SNAPSHOT
- Dry run: true
```

`mvn verify` will run end-to-end. Nothing is pushed; no tag, commit, or Release
appears on origin. The runner is discarded.

### Real release

If the dry-run looked right:

1. Repo → Actions → **Release** → *Run workflow*
2. Branch: `main`
3. Leave **Dry run** unticked
4. Leave both version inputs blank
5. *Run workflow*

In ~6-8 minutes you'll have:

- A new tag `vX.Y.Z` on `main`
- Two new commits: `Release X.Y.Z` and `Prepare for next development iteration: X.Y.Z+1-SNAPSHOT`
- A GitHub Release at `https://github.com/stucray/limen/releases/tag/vX.Y.Z`
- The image published at `ghcr.io/stucray/limen:X.Y.Z` (also `:X.Y` and `:latest`) — a separate `publish-image.yml` run handles this and takes another ~1 min after the tag push

### Overriding the versions

The defaults bump patch (`0.0.1` → `0.0.2-SNAPSHOT`). For a minor or major
bump, fill the inputs:

- **Release version**: `0.1.0`
- **Next development version**: `0.2.0-SNAPSHOT`

Validation: `next_version` must end in `-SNAPSHOT`; the tag must not already
exist; the current pom version must end in `-SNAPSHOT`. If any fail, the run
stops with a clear error before any commits are made.

## After the release

Locally:

```sh
git pull
./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout
# 0.0.2-SNAPSHOT
```

To pull the new image (see also `docs/CONTAINER.md`):

```sh
echo "$GHCR_PAT" | docker login ghcr.io -u stucray --password-stdin
docker pull ghcr.io/stucray/limen:0.0.1
```

## Maintenance

- **PAT renewal**: when the 90-day clock runs out, the workflow fails at
  *Checkout* with a 401. Generate a new PAT (same permissions), update the
  `RELEASE_TOKEN` secret, you're back in business. Set a calendar reminder.
- **`gh release --generate-notes`**: pulls PR titles since the previous tag.
  Write good PR titles and you get good release notes for free.

## Reusing this workflow in another Maven project

The workflow file is **portable** — it never references this repo's name. Drop
`.github/workflows/release.yml` into another single-module or multi-module
Maven project and adjust two things in the YAML:

1. **The `env:` block under the job.** List any secrets your tests need.
   Limen has `LIMEN_SECURITY_KEK`. Most projects have something. Delete the
   block entirely if `mvn verify` runs with no env.

2. **The Java-version lookup.** The workflow reads `<java.version>` from
   `pom.xml` via a `grep` line. If your project pins Java only via
   `maven.compiler.source` or similar, change the property name in the grep.

Per repo, repeat the **One-time setup** section above (create a PAT scoped to
the new repo, store as `RELEASE_TOKEN` secret). The secret name stays the same
across repos.

Multi-module projects are already handled — `versions:set` runs with
`-DprocessAllModules=true`, which is a no-op on single-module poms.

If the new repo doesn't have a tag-driven downstream workflow (image build,
deploy, etc.), the release workflow still works — it just stops at "tag is
pushed and the GitHub Release is created."
