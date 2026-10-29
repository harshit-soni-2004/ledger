package dev.ihars.ledger.support;

import com.mysql.cj.jdbc.Driver;
import dev.ihars.ledger.audit.Actor;
import dev.ihars.ledger.security.ApiClientService;
import dev.ihars.ledger.security.ApiRole;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Base class for integration tests against a real MySQL 8.4 server.
 *
 * <p>One container is started per test JVM and shared by all test classes. It is initialized
 * with the same {@code docker/mysql/init/01-users.sh} as Docker Compose, so tests exercise the
 * real privilege model: Flyway migrates as the migration user and the application runs as the
 * least-privilege app user. Tests that need to bypass those privileges (inspecting grants,
 * probing constraints) use {@link #privilegedJdbc()}, which connects as root.
 *
 * <p>Isolation: test classes must not depend on each other's data. Ledger tables are
 * append-only and are never cleaned up, so tests create their own accounts and assert only on
 * those (see {@link LedgerTestData}); writes that must not persist run in a rolled-back
 * transaction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    protected static final String DATABASE = "ledger";
    protected static final String MIGRATION_USER = "ledger_migrator";
    protected static final String APP_USER = "ledger_app";
    private static final String MIGRATION_PASSWORD = "test-migrator-password";
    private static final String APP_PASSWORD = "test-app-password";
    /** Registered as the bootstrap ADMIN key in every test context. Test-only value. */
    protected static final String ADMIN_KEY = "lk_TestOnlyBootstrapAdminKey000000000000000000";
    private static final Map<ApiRole, String> SHARED_KEYS = new ConcurrentHashMap<>();

    private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName(DATABASE)
            // Root only: otherwise Testcontainers adds a "test" user with ALL on the schema,
            // which Docker Compose does not have.
            .withUsername("root")
            .withCommand("--log-bin-trust-function-creators=1")
            .withStartupTimeout(Duration.ofMinutes(5))
            // The JDBC readiness wait has its own timeout (default 120 s), separate from the above.
            .withConnectTimeoutSeconds(300)
            .withEnv("LEDGER_DB_MIGRATION_USER", MIGRATION_USER)
            .withEnv("LEDGER_DB_MIGRATION_PASSWORD", MIGRATION_PASSWORD)
            .withEnv("LEDGER_DB_APP_USER", APP_USER)
            .withEnv("LEDGER_DB_APP_PASSWORD", APP_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("docker/mysql/init/01-users.sh"), 0755),
                    "/docker-entrypoint-initdb.d/01-users.sh");

    static {
        MYSQL.start();
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ApiClientService apiClients;

    /** A key of the given role, created once per test JVM and shared by all tests. */
    protected String keyFor(ApiRole role) {
        if (role == ApiRole.ADMIN) {
            return ADMIN_KEY;
        }
        return SHARED_KEYS.computeIfAbsent(role, r -> newClient(r).token());
    }

    /** A fresh client with its own key, for tests about ownership or revocation. */
    protected TestClient newClient(ApiRole role) {
        ApiClientService.CreatedClient created = apiClients.createClient("Test " + role, role,
                Actor.system("integration-test"), null);
        return new TestClient(created.client().publicId(), created.key().key().publicId(), created.key().token());
    }

    public record TestClient(String id, String keyId, String token) {}

    /** Any method, optional bearer token and JSON body; extra headers as name/value pairs. */
    protected HttpResponse<String> call(String method, String path, String token, String json, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (headers.length > 0) {
            request.headers(headers);
        }
        return send(request.build());
    }

    protected HttpResponse<String> getAs(String token, String path, String... headers) {
        return call("GET", path, token, null, headers);
    }

    protected HttpResponse<String> postAs(String token, String path, String json, String... headers) {
        return call("POST", path, token, json, headers);
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("LEDGER_DB_URL", IntegrationTest::jdbcUrl);
        registry.add("LEDGER_DB_APP_USER", () -> APP_USER);
        registry.add("LEDGER_DB_APP_PASSWORD", () -> APP_PASSWORD);
        registry.add("LEDGER_DB_MIGRATION_USER", () -> MIGRATION_USER);
        registry.add("LEDGER_DB_MIGRATION_PASSWORD", () -> MIGRATION_PASSWORD);
        registry.add("LEDGER_BOOTSTRAP_ADMIN_KEY", () -> ADMIN_KEY);
        registry.add("LEDGER_INTEGRITY_SCHEDULE_ENABLED", () -> "false");
    }

    private static String jdbcUrl() {
        return "jdbc:mysql://%s:%d/%s?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
                .formatted(MYSQL.getHost(), MYSQL.getMappedPort(3306), DATABASE);
    }

    /** Root data source for assertions that must bypass the application's privileges. */
    protected static DataSource privilegedDataSource() {
        try {
            return new SimpleDriverDataSource(new Driver(), jdbcUrl(), "root", MYSQL.getPassword());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static JdbcClient privilegedJdbc() {
        return JdbcClient.create(privilegedDataSource());
    }

    protected HttpResponse<String> get(String path, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (headers.length > 0) {
            request.headers(headers);
        }
        return send(request.build());
    }

    protected HttpResponse<String> postJson(String path, String json) {
        return post(path, "application/json", json);
    }

    protected HttpResponse<String> post(String path, String contentType, String body) {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** MySQL vendor error code of the root-cause SQLException, if any. */
    protected static Optional<Integer> mysqlErrorCode(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return Optional.of(sql.getErrorCode());
            }
        }
        return Optional.empty();
    }
}
