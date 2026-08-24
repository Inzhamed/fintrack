package com.fintrack.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real Postgres for integration tests.
 * <p>
 * The image is pinned to the same version production runs. Testing against
 * {@code postgres:latest} would mean the database silently changes under the suite whenever a
 * new release lands, and testing against H2 would accept SQL that Postgres rejects - which is
 * exactly the class of bug these tests exist to catch.
 * <p>
 * {@code @ServiceConnection} wires the container's JDBC url, username and password into the
 * context, so no test has to know the port Docker happened to assign.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
    }
}
