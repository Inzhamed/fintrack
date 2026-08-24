# ---- Build stage -------------------------------------------------------------------
# Dependencies are resolved in their own layer, keyed on pom.xml alone. Editing source
# then rebuilds in seconds instead of re-downloading the world on every change.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
# Tests run in CI against a real Postgres via Testcontainers; running them here would
# require a Docker daemon inside the build, so the image build only compiles.
RUN mvn -B clean package -DskipTests

# ---- Runtime stage -----------------------------------------------------------------
# JRE only: no compiler, no Maven, no build cache. Smaller image, smaller attack surface.
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# wget backs the container healthcheck in compose.yaml.
RUN apk add --no-cache wget

# An unprivileged user, so a compromise inside the container is not already root.
RUN addgroup -S fintrack && adduser -S fintrack -G fintrack
USER fintrack

COPY --from=build --chown=fintrack:fintrack /build/target/*.jar app.jar

EXPOSE 8080

# Container memory, not the host's, decides the heap. Without this the JVM can size its
# heap from the host and get OOM-killed by the container limit.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
