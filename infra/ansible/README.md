# Ansible

Server bootstrap for the Limen VPS. The full walk-through (key generation,
provisioning, first contact, verification) lives in
[`docs/process/vps-bootstrap.md`](../../docs/process/vps-bootstrap.md).

```sh
# once, to fetch external roles (Docker install)
ansible-galaxy install -r requirements.yml

# first run (deploy user doesn't exist yet)
ansible-playbook bootstrap.yml -e ansible_user=root

# every run after — expect changed=0 on a clean box
ansible-playbook bootstrap.yml
```

Run from this directory; `ansible.cfg` points at `inventory.yml`.

## Deploy-toolchain provisioning (third play)

The `Provision deploy toolchain` play installs age + a pinned, checksummed
sops binary, generates the box's age key (on-box only, never copied off, not
part of any backup) and a read-only SSH deploy key for the private
`limen-secrets` repo, clones `limen` + `limen-secrets` under the deploy user,
and logs docker into GHCR with the pull-scoped token from the decrypted
secrets.

The first run ends with an **Operator follow-ups** message listing two manual
steps, then needs one re-run:

1. Register the printed SSH public key as a **read-only deploy key** on
   `stucray/limen-secrets` (Settings → Deploy keys → Add; leave "Allow write
   access" unchecked).
2. Add the printed box age public key as a recipient in the secrets repo's
   `.sops.yaml`, run `sops updatekeys prod.env`, commit, push.

Re-run the playbook: the secrets clone and GHCR login complete, and a further
run reports no changes.
