# Infrastructure as code

Provisions the server FinTrack runs on: an Oracle Cloud **Always Free** ARM instance, its
network, and the firewall rules in front of it.

Oracle rather than AWS for one reason — cost. AWS EKS bills $0.10/hour for the control plane
with no free tier, ever, and a NAT gateway is another $32/month before a single byte moves.
Oracle's Always Free tier gives 4 ARM cores and 24 GB of RAM indefinitely, which is more than
enough for k3s and this workload. The Kubernetes underneath is conformant either way, so the
manifests and the Helm chart are unchanged.

## Layout

```
modules/oci-k3s-node/     the reusable piece: network, firewall, instance, k3s bootstrap
environments/production/  one instance of it, with its own state
```

## Using it

```bash
cd environments/production
terraform init
terraform plan     # requires OCI credentials
terraform apply
```

## State

State is kept in OCI Object Storage with locking, not on a laptop. Local state means the
person who ran `apply` last is the only one who can safely run it again, and a lost laptop
takes the record of what exists with it. The bucket is created by hand once, before the first
`init` — bootstrapping it from the same configuration it stores would be circular.

## What is verified and what is not

`fmt`, `validate` and the provider schema check all pass. **No `plan` or `apply` has ever
run**: that needs real Oracle Cloud credentials, and the account does not exist yet. Treat
this as unproven until a first apply succeeds.
