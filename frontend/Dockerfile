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

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

# Port 8080 rather than 80, so the container needs no privileged port and can run as a
# non-root user under Kubernetes in Phase 4.
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
    CMD wget --spider -q http://localhost:8080/ || exit 1
