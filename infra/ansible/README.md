# Ansible

Server bootstrap for the Limen VPS. The full walk-through (key generation,
provisioning, first contact, verification) lives in
[`docs/process/vps-bootstrap.md`](../../docs/process/vps-bootstrap.md).

```sh
# first run (deploy user doesn't exist yet)
ansible-playbook bootstrap.yml -e ansible_user=root

# every run after — expect changed=0 on a clean box
ansible-playbook bootstrap.yml
```

Run from this directory; `ansible.cfg` points at `inventory.yml`.
