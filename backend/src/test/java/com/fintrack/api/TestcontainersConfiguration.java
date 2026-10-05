package com.fintrack.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres and real Redis for integration tests.
 * <p>
 * Both images are pinned to the versions production runs. Testing against
 * {@code :latest} would mean the dependency silently changes under the suite whenever a new
 * release lands, and testing against H2 or an embedded fake would accept behaviour the real
 * server rejects - which is exactly the class of bug these tests exist to catch.
 * <p>
 * {@code @ServiceConnection} wires each container's connection details into the context, so
 * no test has to know which port Docker happened to assign.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
    }

    /**
     * A plain GenericContainer rather than a dedicated module: Testcontainers has no Redis
     * module in Spring Boot's managed BOM, and naming the connection is all
     * {@code @ServiceConnection} needs to configure Lettuce against it.
     */
    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                // Waiting on the log line rather than just the port means the first test does
                // not race a server that is listening but not yet ready to answer.
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));
    }

    /**
     * RustFS, so receipt handling is exercised against a real S3 API rather than a mock.
     * <p>
     * This was MinIO until its images were withdrawn from Docker Hub and quay.io: CI could no
     * longer pull them, while machines holding a cached copy kept passing. The tag is pinned
     * for the same reason - a moving tag makes a green build depend on what a registry happened
     * to serve that day.
     * <p>
     * Not a {@code @ServiceConnection}: Spring Boot has no connection-details contract for a
     * generic S3 endpoint, so the properties are registered explicitly once the container has
     * a mapped port.
     */
    @Bean
    GenericContainer<?> storageContainer() {
        return new GenericContainer<>(DockerImageName.parse("rustfs/rustfs:1.0.1"))
                .withExposedPorts(9000)
                .withEnv("RUSTFS_ACCESS_KEY", "testaccess")
                .withEnv("RUSTFS_SECRET_KEY", "testsecret")
                // Ready, not merely listening: the port opens before requests are served.
                .waitingFor(Wait.forHttp("/health/ready").forPort(9000).forStatusCode(200));
    }

    @Bean
    DynamicPropertyRegistrar storageProperties(GenericContainer<?> storageContainer) {
        return registry -> {
            String endpoint = "http://%s:%d".formatted(
                    storageContainer.getHost(), storageContainer.getMappedPort(9000));
            registry.add("fintrack.storage.endpoint", () -> endpoint);
            // Same host in tests: there is no container network to bridge, so the signing
            // endpoint and the reachable endpoint are one and the same.
            registry.add("fintrack.storage.public-endpoint", () -> endpoint);
            registry.add("fintrack.storage.access-key", () -> "testaccess");
            registry.add("fintrack.storage.secret-key", () -> "testsecret");
            registry.add("fintrack.storage.bucket", () -> "test-receipts");
        };
    }
}
