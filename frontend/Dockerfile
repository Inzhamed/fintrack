# ---- Build stage -------------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /build

# Dependencies resolve in their own layer, keyed on the lockfile, so editing source does
# not re-install the tree on every build.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---- Runtime stage -----------------------------------------------------------------
# nginx serves the static build. No Node in the final image: nothing here executes
# JavaScript on the server, so shipping a runtime would be pure attack surface.
FROM nginx:1.27-alpine AS runtime

# A template rather than a finished config: the entrypoint renders it with envsubst at
# start-up, so the same image works under compose, where the API answers to "api", and
# under Kubernetes, where the Service name carries the Helm release prefix. The default
# below is the compose name, so `docker compose up` needs no extra environment.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
ENV API_UPSTREAM=api:8080

COPY --from=build /build/dist /usr/share/nginx/html

# The chart runs this as a non-root uid whose primary group is 0. At start-up nginx writes
# its pid, writes temp files under /var/cache/nginx, and the entrypoint renders the template
# into conf.d - all root-owned by default, so an unprivileged uid cannot start. Granting the
# root *group* rather than a fixed uid keeps this working whatever uid the cluster assigns.
RUN sed -i 's|/run/nginx.pid|/tmp/nginx.pid|' /etc/nginx/nginx.conf \
 && chgrp -R 0 /etc/nginx/conf.d /var/cache/nginx \
 && chmod -R g+w /etc/nginx/conf.d /var/cache/nginx

# Port 8080 rather than 80, so the container needs no privileged port and can run as a
# non-root user under Kubernetes in Phase 4.
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
    CMD wget --spider -q http://localhost:8080/ || exit 1
