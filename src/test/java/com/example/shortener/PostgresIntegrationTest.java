package com.example.shortener;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests: a real PostgreSQL (same major version as docker-compose) with the
 * real Flyway migrations applied. No H2, because the guarantees under test (ON CONFLICT, partial indexes,
 * bytea, SKIP LOCKED) are PostgreSQL behaviour.
 *
 * <p>Singleton container: started once per JVM and shared by every test class (Ryuk removes it at the end).
 * With {@code @Container} each class would restart it on a new port while Spring's cached context still pointed
 * at the old one.
 *
 * <p>Test defaults: analytics in {@code sync} mode (deterministic stats), generous rate limits (tests create many
 * links with one key), and test API keys (full, read-only, expired, create-only) from {@link TestProps}.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "shortener.api-keys[0].name=tests",
            "shortener.api-keys[0].sha256=" + TestProps.API_KEY_SHA256,
            "shortener.api-keys[0].scopes[0]=links:create",
            "shortener.api-keys[0].scopes[1]=links:delete",
            "shortener.api-keys[0].scopes[2]=stats:read",
            "shortener.api-keys[1].name=readonly",
            "shortener.api-keys[1].sha256=" + TestProps.READONLY_KEY_SHA256,
            "shortener.api-keys[1].scopes[0]=stats:read",
            "shortener.api-keys[2].name=expired",
            "shortener.api-keys[2].sha256=" + TestProps.EXPIRED_KEY_SHA256,
            "shortener.api-keys[2].scopes[0]=links:delete",
            "shortener.api-keys[2].expires-at=2020-01-01T00:00:00Z",
            "shortener.api-keys[3].name=creator",
            "shortener.api-keys[3].sha256=" + TestProps.CREATOR_KEY_SHA256,
            "shortener.api-keys[3].scopes[0]=links:create",
            "shortener.analytics.mode=sync",
            "shortener.rate-limits.create.capacity=10000",
            "shortener.rate-limits.redirect.capacity=10000",
            "shortener.rate-limits.auth-failure.capacity=10000"
        })
public abstract class PostgresIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }
}
