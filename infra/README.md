# Infrastructure

```
helm/fintrack/          the chart: API, web, Postgres, Redis, MinIO, ingress
helm/environments/      per-environment overlays (local, production)
terraform/              the server the cluster runs on
```

## Running it on a local cluster

```bash
k3d cluster create fintrack --port "8081:80@loadbalancer"
```

Build the images and side-load them, since a local cluster has no registry to pull from:

```bash
docker build -t fintrack-api:local ./backend && docker build -t fintrack-web:local ./frontend
```

```bash
k3d image import fintrack-api:local fintrack-web:local -c fintrack
```

```bash
helm install fintrack infra/helm/fintrack -f infra/helm/environments/local.yaml --set secrets.dbPassword=localdev --set secrets.jwtSecret="$(openssl rand -base64 48)" --set secrets.storageSecretKey=localdev-storage
```

The app is then on http://fintrack.localhost:8081.

## Checking the chart without a cluster

Both run offline and are what CI uses:

```bash
helm lint infra/helm/fintrack --set secrets.dbPassword=x --set secrets.jwtSecret=y --set secrets.storageSecretKey=z
```

```bash
helm template fintrack infra/helm/fintrack -f infra/helm/environments/production.yaml --set image.tag=abc123 | kubeconform -strict -kubernetes-version 1.31.0
```

## Design notes

**A chart, not raw manifests.** Two environments already differ in replica counts, resource
requests, TLS, image source and pull policy. Kept as plain YAML that is either duplicated in
full or patched with overlays that drift; templating keeps one description of the system with
the differences stated explicitly in one file each.

**Config and secrets are hashed into the pod annotations.** Kubernetes does not restart a
Deployment when a referenced ConfigMap changes, so without the checksum a config update
appears to succeed while every pod carries on with the old values.

**The chart refuses to install without secrets.** No default password, not even a placeholder
one: a chart that installs with `password` as the database credential is worse than one that
fails, because the failure is loud and the weak default is silent. Production goes further and
references a Secret created out of band, so credentials never reach the repository or the
release history Helm stores in the cluster.

**Three probes on the API, one on the web.** They answer different questions: startup ("has it
finished booting?" — a JVM running Flyway can take 40 seconds, and without it liveness kills
the pod mid-migration and loops), readiness ("should it get traffic?" — removes the pod from
the Service without restarting it), liveness ("is it wedged?" — the only one that restarts, so
the most conservative). nginx serving static files needs none of that nuance.

**Memory is limited, CPU is not.** CFS throttling on a JVM causes latency spikes worse than
the noisy-neighbour problem a CPU limit solves. A memory leak with no ceiling takes the node
down rather than one pod, so that ceiling stays.

**Postgres is a StatefulSet, Redis is a Deployment.** Postgres needs a stable identity and its
own volume; a Deployment's pods would share one PVC and corrupt the data directory the moment
two existed during a rolling update. Redis holds only caches and rate-limit counters, all safe
to lose, so persisting it would add a stateful component to operate for no benefit.

**Oracle Always Free rather than AWS.** EKS bills $0.10/hour for the control plane with no
free tier ever, and a NAT gateway is $32/month before any traffic. Oracle gives 4 ARM cores
and 24 GB indefinitely. The Kubernetes is conformant either way, so nothing above the node
changes. The Terraform variables validate against the free-tier ceilings, so a typo cannot
quietly start billing.
