package com.innbucks.loyaltyservice.testsupport;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests that need a real PostgreSQL — used for
 * scenarios H2 can't fake faithfully: partial unique indexes, row locks under
 * concurrent load, native enum columns.
 *
 * <p>One container is shared across every test class that extends this (the
 * static field means it's started once per JVM, not once per class). Spring
 * test context caching means the Spring context is also reused — so the cost
 * is paid once per build.
 *
 * <p>Requires Docker on the host. If Docker isn't available these tests fail
 * to start, but the H2-based tests under {@link ControllerSecurityTestBase}
 * and {@code LoyaltyServiceIntegrationTest} keep running.
 *
 * <p>Subclasses must add {@code @ActiveProfiles("it")} (or rely on this base's
 * annotation) so {@code application-it.yaml} kicks in: Flyway enabled, ddl-auto
 * validate, Postgres dialect — i.e. the production schema flow.
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.innbucks.loyaltyservice.testsupport.PostgresIntegrationTestBase#isDockerAvailable")
public abstract class PostgresIntegrationTestBase {

    // Static + reused across test classes. Testcontainers tears it down on JVM
    // exit. Using a lightweight Postgres image to keep startup under 10s.
    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("loyalty_it")
            .withUsername("loyalty")
            .withPassword("loyalty")
            .withReuse(true);

    @BeforeAll
    static void startContainer() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
    }

    /**
     * Junit's @EnabledIf entrypoint — short-circuits the whole IT class when
     * Docker isn't reachable so devs without Docker (or constrained CI runners)
     * don't see red builds. Returns true only when Testcontainers can actually
     * talk to a Docker daemon.
     */
    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
}
