package com.fintrack.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
}
