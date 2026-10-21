package com.ticketbooking.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real infrastructure for integration tests, started once per JVM.
 *
 * <ul>
 *   <li><b>PostgreSQL & Redis</b> - Testcontainers when a Docker daemon is available
 *       (the normal case on a developer machine / CI). Otherwise ("external" mode) the
 *       tests connect to already-running services configured via TEST_DB_URL,
 *       TEST_DB_USERNAME, TEST_DB_PASSWORD, TEST_REDIS_HOST, TEST_REDIS_PORT.
 *       Force a mode with -Dtest.infra=docker|external.</li>
 *   <li><b>Kafka</b> - a real in-process KRaft broker from spring-kafka-test. It speaks
 *       the real Kafka protocol, so producer acks, consumer offsets, retries and DLT
 *       routing are exercised for real.</li>
 * </ul>
 * Mock-only tests cannot prove concurrency correctness, so nothing here is mocked.
 */
public final class TestInfrastructure {

    private static final Logger log = LoggerFactory.getLogger(TestInfrastructure.class);
    private static TestInfrastructure instance;

    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;
    private final String redisHost;
    private final int redisPort;
    private final EmbeddedKafkaKraftBroker kafka;
    private final String mode;

    private TestInfrastructure() {
        String requested = System.getProperty("test.infra", env("TEST_INFRA", "auto"));
        boolean useDocker = switch (requested) {
            case "docker" -> true;
            case "external" -> false;
            default -> DockerClientFactory.instance().isDockerAvailable();
        };
        if (useDocker) {
            PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("ticketing_test").withUsername("ticketing").withPassword("ticketing");
            GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
            postgres.start();
            redis.start();
            jdbcUrl = postgres.getJdbcUrl();
            dbUser = postgres.getUsername();
            dbPassword = postgres.getPassword();
            redisHost = redis.getHost();
            redisPort = redis.getMappedPort(6379);
            mode = "testcontainers";
        } else {
            jdbcUrl = env("TEST_DB_URL", "jdbc:postgresql://localhost:5432/ticketing_test");
            dbUser = env("TEST_DB_USERNAME", "ticketing");
            dbPassword = env("TEST_DB_PASSWORD", "ticketing");
            redisHost = env("TEST_REDIS_HOST", "localhost");
            redisPort = Integer.parseInt(env("TEST_REDIS_PORT", "6379"));
            mode = "external";
        }
        kafka = new EmbeddedKafkaKraftBroker(1, 3);
        kafka.afterPropertiesSet();
        log.info("Integration-test infrastructure: mode={} postgres={} redis={}:{} kafka={}",
                mode, jdbcUrl, redisHost, redisPort, kafka.getBrokersAsString());
    }

    public static synchronized TestInfrastructure get() {
        if (instance == null) {
            instance = new TestInfrastructure();
        }
        return instance;
    }

    public void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", () -> dbUser);
        registry.add("spring.datasource.password", () -> dbPassword);
        // Registered under test.* names and mapped in application-test.yml, so a test class can
        // still override spring.data.redis.* with its own @DynamicPropertySource (see RedisUnavailableIT).
        registry.add("test.redis.host", () -> redisHost);
        registry.add("test.redis.port", () -> redisPort);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBrokersAsString);
    }

    public String kafkaBootstrapServers() {
        return kafka.getBrokersAsString();
    }

    public String mode() {
        return mode;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
