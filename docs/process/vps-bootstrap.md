# VPS bootstrap (SSH + Ansible on Ubuntu)

From a freshly provisioned Ubuntu VPS to a hardened, key-only, firewall-on box
— with everything after the first login done by Ansible, so the server can be
rebuilt from scratch in one command.

Written August 2026. Version-sensitive claims (Ubuntu release line, Ansible
install method, SSH hardening defaults) were checked against current sources
at that date.

## Phase 1 — Local prep: SSH key and Ansible

### Generate a dedicated ed25519 key

Ed25519 is the current default choice — smaller, faster, and at least as
strong as RSA-4096. Use a passphrase; macOS Keychain will remember it.

```sh
ssh-keygen -t ed25519 -a 100 -C "stu@vps" -f ~/.ssh/id_ed25519_vps
ssh-add --apple-use-keychain ~/.ssh/id_ed25519_vps
```

Scoping one keypair to the VPS means it can be revoked later without touching
GitHub or other keys, and the Ansible files can reference it by name.

### Install Ansible via pipx

pipx is the install method the Ansible docs recommend — it keeps Ansible in
its own isolated Python environment. Install the full `ansible` package (not
just `ansible-core`): it bundles the community collections the playbook below
uses (`ansible.posix`, `community.general`).

```sh
brew install pipx
pipx ensurepath        # then restart the shell once
pipx install --include-deps ansible
ansible --version
```

## Phase 2 — Provision the VPS

- **Image:** Ubuntu 24.04 LTS or 26.04 LTS. 26.04 (Resolute Raccoon) shipped
  April 2026 and the 26.04.1 point release landed in August 2026, so it's safe
  to start on. If the provider doesn't offer it yet, 24.04 is supported to
  2029.
- **SSH key:** paste the contents of `~/.ssh/id_ed25519_vps.pub` (the _.pub_
  file) into the provider's SSH-key field at creation time. This matters:
  when a key is supplied, cloud-init provisions the box with password
  authentication already disabled, so there is never a root password to leak
  or brute-force.
- **Note the IP.** Optionally point a DNS A record at it now so it has
  propagated by the time TLS is needed.

## Phase 3 — First contact

Log in once as root (some providers use `ubuntu` instead) purely to confirm
key auth works. Everything else happens through Ansible.

```sh
ssh -i ~/.ssh/id_ed25519_vps root@203.0.113.10
```

On first connect SSH shows the server's host-key fingerprint. If the
provider's console displays fingerprints, compare before typing `yes`;
otherwise accepting on first use from a trusted network is the normal
trust-on-first-use tradeoff.

Then add a host alias locally so every later command is short:

```text
# ~/.ssh/config
Host vps
  HostName 203.0.113.10
  User deploy
  IdentityFile ~/.ssh/id_ed25519_vps
  IdentitiesOnly yes
```

`deploy` doesn't exist yet — the playbook creates it next.

## Phase 4 — The Ansible project

Three files, kept in the repo (e.g. `infra/ansible/`). The playbook is
idempotent — run it as often as you like; a clean run reports `changed=0`.

```ini
# ansible.cfg
[defaults]
inventory = inventory.yml

[ssh_connection]
pipelining = True
```

```yaml
# inventory.yml
vps:
  hosts:
    myserver:
      ansible_host: 203.0.113.10
      ansible_user: deploy
```

```yaml
# bootstrap.yml
---
- name: Bootstrap VPS
  hosts: vps
  become: true
  vars:
    deploy_user: deploy
    ssh_pubkey: "{{ lookup('file', lookup('env', 'HOME') + '/.ssh/id_ed25519_vps.pub') }}"

  tasks:
    - name: Upgrade packages
      ansible.builtin.apt:
        update_cache: true
        cache_valid_time: 3600
        upgrade: safe

    - name: Install base packages
      ansible.builtin.apt:
        name: [ufw, fail2ban, unattended-upgrades]
        state: present

    - name: Create deploy user
      ansible.builtin.user:
        name: "{{ deploy_user }}"
        groups: sudo
        append: true
        shell: /bin/bash

    - name: Authorize SSH key for deploy user
      ansible.posix.authorized_key:
        user: "{{ deploy_user }}"
        key: "{{ ssh_pubkey }}"

    - name: Allow passwordless sudo for deploy user
      ansible.builtin.copy:
        dest: /etc/sudoers.d/90-deploy
        content: "{{ deploy_user }} ALL=(ALL) NOPASSWD:ALL\n"
        mode: "0440"
        validate: /usr/sbin/visudo -cf %s

    - name: Harden sshd
      ansible.builtin.copy:
        dest: /etc/ssh/sshd_config.d/00-hardening.conf
        content: |
          PermitRootLogin no
          PasswordAuthentication no
          KbdInteractiveAuthentication no
          MaxAuthTries 3
          X11Forwarding no
        mode: "0644"
        validate: /usr/sbin/sshd -t -f %s
      notify: Restart ssh

    - name: Configure fail2ban for sshd
      ansible.builtin.copy:
        dest: /etc/fail2ban/jail.local
        content: |
          [sshd]
          enabled = true
          backend = systemd
        mode: "0644"
      notify: Restart fail2ban

    - name: Allow SSH through firewall
      community.general.ufw:
        rule: allow
        name: OpenSSH

    - name: Allow web traffic
      community.general.ufw:
        rule: allow
        port: "{{ item }}"
        proto: tcp
      loop: ["80", "443"]

    - name: Enable firewall, default deny incoming
      community.general.ufw:
        state: enabled
        policy: deny
        direction: incoming

  handlers:
    - name: Restart ssh
      ansible.builtin.service:
        name: ssh
        state: restarted

    - name: Restart fail2ban
      ansible.builtin.service:
        name: fail2ban
        state: restarted
```

**Why the file is named `00-hardening.conf`.** Ubuntu's cloud images drop
their own fragments into `/etc/ssh/sshd_config.d/` (e.g.
`50-cloud-init.conf`), and sshd honours the _first_ value it reads for each
option. The `00-` prefix sorts this fragment ahead of cloud-init's, so these
settings win regardless of what the image shipped with.

**Why NOPASSWD sudo?** The deploy user has no password at all — the only way
in is the SSH key. A sudo password would add nothing against an attacker who
already holds the key, and it would force `--ask-become-pass` on every
Ansible run. The `visudo -cf` validation guards against a sudoers typo
locking out sudo entirely.

**fail2ban is optional here.** With password auth off, brute force can't
succeed — fail2ban mostly keeps the log noise down and slows scanners. Keep
it or drop the two tasks; either is defensible.

## Phase 5 — Run the bootstrap

The first run must connect as root, because `deploy` doesn't exist yet.
Override with `-e`, not `-u` — a CLI `-u` is outranked by the `ansible_user`
set in the inventory, but an extra-var beats everything:

```sh
ansible-playbook bootstrap.yml -e ansible_user=root
```

Then run it again the normal way. This proves two things at once: the
`deploy` path works end-to-end, and the playbook is idempotent:

```sh
ansible-playbook bootstrap.yml     # expect changed=0
```

## Phase 6 — Verify

- [ ] `ssh vps` logs in as `deploy` without a password prompt.
- [ ] `ssh root@203.0.113.10` is refused (_Permission denied_).
- [ ] `ssh -o PubkeyAuthentication=no vps` fails immediately — no password
      prompt appears, confirming password auth is off.
- [ ] `ssh vps sudo ufw status verbose` shows _Status: active_, deny
      incoming, with OpenSSH / 80 / 443 allowed.
- [ ] `ssh vps sudo fail2ban-client status sshd` shows the jail running (if
      kept).
- [ ] A repeat `ansible-playbook bootstrap.yml` reports `changed=0`.

**Keep the current session open** in a second terminal while verifying — if
a hardening step went wrong, an already-authenticated session is the way back
in without a provider rescue console.

## Phase 7 — Where to go next

The box is now a clean, rebuildable target. App deployment layers on top of
the same playbook (or a second one):

- **Docker** — for deploying the container image
  ([`docs/process/container.md`](container.md) covers pulling it from GHCR),
  add the well-maintained `geerlingguy.docker` role
  (`ansible-galaxy role install geerlingguy.docker`) and list it under
  `roles:`, or write the four apt-repo tasks by hand.
- **Reverse proxy + TLS** — Caddy (automatic certificates, minimal config) or
  nginx + certbot in front of the app on 80/443.
- **Secrets** — keep server secrets out of the repo; `ansible-vault` or
  sops + age both work with Ansible.
- **Deploy playbook** — a `deploy.yml` that logs in as `deploy`, pulls the
  image tag, and restarts the compose stack gives one-command releases from
  the workstation.
