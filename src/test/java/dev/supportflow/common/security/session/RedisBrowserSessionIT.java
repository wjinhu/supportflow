package dev.supportflow.common.security.session;

import dev.supportflow.SupportFlowApplication;
import dev.supportflow.testsupport.ApplicationResourceAssertions;
import dev.supportflow.common.security.database.DatabaseAccountAdministration;
import dev.supportflow.common.security.local.LocalCustomerAccount;
import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real HTTP requests cross two application instances and one isolated Redis; no business secrets or model calls. */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class RedisBrowserSessionIT {
    private static final String DATABASE = "redis_browser_session_test";
    private static final String USERNAME = "redis-session-buyer";
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String PASSWORD_HASH = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD);
    private static final String READER_PASSWORD = UUID.randomUUID().toString();
    private static final String REDIS_PASSWORD = UUID.randomUUID().toString();
    private static final String NAMESPACE = "supportflow:test:session:" + UUID.randomUUID();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Container private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.45")
            .withDatabaseName(DATABASE).withUsername("root").withPassword(UUID.randomUUID().toString())
            .withEnv("MYSQL_ROOT_HOST", "%").withUrlParam("allowPublicKeyRetrieval", "true")
            .withUrlParam("sslMode", "DISABLED").withUrlParam("connectionTimeZone", "UTC");
    @Container private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.2.11-alpine"))
            .withExposedPorts(6379)
            .withCopyToContainer(Transferable.of(("bind 0.0.0.0\nprotected-mode yes\nport 6379\n"
                    + "save \"\"\nappendonly no\nrequirepass " + REDIS_PASSWORD + "\n").getBytes(StandardCharsets.UTF_8), 0444),
                    "/etc/redis/supportflow-session-test.conf")
            .withCommand("redis-server", "/etc/redis/supportflow-session-test.conf");

    private static ConfigurableApplicationContext first;
    private static ConfigurableApplicationContext second;
    private static JdbcTemplate operator;
    private static DatabaseAccountAdministration accounts;

    @BeforeAll static void start() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .cleanDisabled(true).load().migrate();
        operator = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        operator.execute("CREATE USER 'redis_session_runtime'@'%' IDENTIFIED BY '" + READER_PASSWORD + "'");
        for (String table : List.of("support_security_account", "support_order", "support_order_buyer_deletion")) {
            operator.execute("GRANT SELECT ON " + DATABASE + "." + table + " TO 'redis_session_runtime'@'%'");
        }
        accounts = new DatabaseAccountAdministration(operator);
        assertThat(accounts.importMissingConfiguredAccounts(List.of(new LocalCustomerAccount(
                USERNAME, PASSWORD_HASH, new CustomerIdentity(200), Set.of(SecurityPermission.ORDER_READ), AccountType.BUYER))))
                .isEqualTo(1);
        operator.update("""
                INSERT INTO support_order (tenant_id,customer_id,buyer_id,order_no,status,total_amount,currency)
                VALUES (700,200,200,'REDIS-SESSION-OWNED','PAID',19.90,'CNY')
                """);
        try {
            first = application("JSESSIONID");
            second = application("JSESSIONID");
        } catch (RuntimeException failure) {
            closeApplications();
            throw failure;
        }
    }

    @AfterAll static void closeContexts() {
        try { closeApplications(); }
        finally {
            HTTP.close();
            assertThat(HTTP.isTerminated()).as("test-owned HTTP client terminated").isTrue();
        }
    }

    private static void closeApplications() {
        try {
            if (second != null) ApplicationResourceAssertions.closeAndVerify(second);
        } finally {
            second = null;
            try {
                if (first != null) ApplicationResourceAssertions.closeAndVerify(first);
            } finally { first = null; }
        }
    }

    @Test @Order(1)
    void sharesAuthenticationCsrfAndLogoutAcrossNodesWithoutPersistingPasswords() throws Exception {
        Csrf anonymous = csrf(first, null);
        var login = request(first, "POST", "/api/v1/security/login", anonymous.cookie(), anonymous,
                JSON.writeValueAsString(Map.of("username", USERNAME, "password", PASSWORD)));
        assertThat(login.statusCode()).isEqualTo(204);
        Cookie authenticated = cookie(login, "JSESSIONID");
        assertThat(authenticated.value()).isNotEqualTo(anonymous.cookie().value());
        assertCookieAttributes(login, "JSESSIONID");

        var me = request(second, "GET", "/api/v1/security/me", authenticated, null, null);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(me.body()).path("username").asString()).isEqualTo(USERNAME);
        assertThat(me.body()).doesNotContain("password", "buyerId", "securityVersion");
        var orders = request(second, "GET", "/api/v1/orders?afterId=0&size=20", authenticated, null, null);
        assertThat(orders.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(orders.body()).path("items").get(0).path("orderNo").asString())
                .isEqualTo("REDIS-SESSION-OWNED");

        assertStoredSession(first, authenticated);
        assertThat(request(second, "POST", "/api/v1/security/logout", authenticated, null, null).statusCode()).isEqualTo(403);
        assertThat(request(second, "POST", "/api/v1/security/logout", authenticated, anonymous, null).statusCode()).isEqualTo(403);
        Csrf refreshed = csrf(second, authenticated);
        assertThat(request(first, "GET", "/api/v1/security/me", authenticated, null, null).statusCode()).isEqualTo(200);
        var logout = request(second, "POST", "/api/v1/security/logout", authenticated, refreshed, null);
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.headers().allValues("set-cookie").stream()
                .anyMatch(value -> value.startsWith("JSESSIONID=") && value.toLowerCase().contains("max-age=0"))).isTrue();
        assertThat(request(first, "GET", "/api/v1/security/me", authenticated, null, null).statusCode()).isEqualTo(401);

        // The real Redis TTL is configured from the same browser-session duration, not an unrelated cache TTL.
        Cookie expiring = login(first, PASSWORD);
        String key = sessionKey(expiring);
        var redis = first.getBean(StringRedisTemplate.class);
        assertThat(redis.getExpire(key)).isBetween(1L, 60L);
        redis.expire(key, Duration.ofMillis(50));
        await().atMost(Duration.ofSeconds(4)).untilAsserted(() ->
                assertThat(request(second, "GET", "/api/v1/security/me", expiring, null, null).statusCode()).isEqualTo(401));
    }

    @Test @Order(2)
    void revokesRedisSessionsOnEitherNodeAfterDatabaseDisableOrPasswordChange() throws Exception {
        Cookie disabled = login(first, PASSWORD);
        assertThat(accounts.disable(USERNAME)).isTrue();
        assertThat(request(second, "GET", "/api/v1/security/me", disabled, null, null).statusCode()).isEqualTo(401);
        assertThat(accounts.enable(USERNAME)).isTrue();
        assertThat(request(first, "GET", "/api/v1/security/me", disabled, null, null).statusCode()).isEqualTo(401);

        Cookie oldPasswordSession = login(second, PASSWORD);
        String replacement = UUID.randomUUID().toString();
        assertThat(accounts.changePassword(USERNAME, replacement, PasswordEncoderFactories.createDelegatingPasswordEncoder())).isTrue();
        assertThat(request(first, "GET", "/api/v1/security/me", oldPasswordSession, null, null).statusCode()).isEqualTo(401);
        Csrf token = csrf(first, null);
        var oldLogin = request(first, "POST", "/api/v1/security/login", token.cookie(), token,
                JSON.writeValueAsString(Map.of("username", USERNAME, "password", PASSWORD)));
        assertThat(oldLogin.statusCode()).isEqualTo(401);
        Cookie current = login(first, replacement);
        assertThat(request(second, "GET", "/api/v1/security/me", current, null, null).statusCode()).isEqualTo(200);
        assertThat(accounts.changePassword(USERNAME, PASSWORD, PasswordEncoderFactories.createDelegatingPasswordEncoder())).isTrue();
        assertThat(request(second, "GET", "/api/v1/security/me", current, null, null).statusCode()).isEqualTo(401);
    }

    @Test @Order(3)
    void preservesTheConfiguredLabCookieNameAcrossRedisReadAndLogout() throws Exception {
        Cookie local = login(first, PASSWORD);
        try (ConfigurableApplicationContext labCookieNode = application("SF_LAB_SESSION")) {
            Cookie lab = new Cookie("SF_LAB_SESSION", local.value());
            assertThat(request(labCookieNode, "GET", "/api/v1/security/me", lab, null, null).statusCode()).isEqualTo(200);
            Csrf token = csrf(labCookieNode, lab);
            var logout = request(labCookieNode, "POST", "/api/v1/security/logout", lab, token, null);
            assertThat(logout.statusCode()).isEqualTo(204);
            assertThat(logout.headers().allValues("set-cookie").stream()
                    .anyMatch(value -> value.startsWith("SF_LAB_SESSION=") && value.toLowerCase().contains("max-age=0"))).isTrue();
            assertThat(request(first, "GET", "/api/v1/security/me", local, null, null).statusCode()).isEqualTo(401);
            Cookie freshLab = login(labCookieNode, PASSWORD, "SF_LAB_SESSION");
            assertStoredSession(labCookieNode, freshLab);
        }
    }

    @Test @Order(4)
    void redisOutageFailsClosedForProtectedReadsAndSessionExchanges() throws Exception {
        Cookie authenticated = login(first, PASSWORD);
        Csrf token = csrf(second, authenticated);
        REDIS.stop();
        for (var exchange : List.of(
                request(second, "GET", "/api/v1/security/me", authenticated, null, null),
                request(first, "GET", "/api/v1/security/csrf", null, null, null),
                request(first, "POST", "/api/v1/security/login", authenticated, token,
                        JSON.writeValueAsString(Map.of("username", USERNAME, "password", PASSWORD))),
                request(second, "POST", "/api/v1/security/logout", authenticated, token, null))) {
            assertThat(exchange.statusCode()).isEqualTo(503);
            assertThat(exchange.headers().firstValue("content-type").orElse("")).contains("application/json");
            assertThat(JSON.readTree(exchange.body()).path("code").asString()).isEqualTo("SESSION_UNAVAILABLE");
            assertThat(exchange.body()).doesNotContain(PASSWORD, PASSWORD_HASH, REDIS_PASSWORD, "redis://", "localhost:");
            assertThat(exchange.headers().allValues("set-cookie").stream()
                    .noneMatch(value -> value.startsWith("JSESSIONID=") && !value.contains("Max-Age=0"))).isTrue();
        }
    }

    private static ConfigurableApplicationContext application(String cookieName) {
        List<String> settings = new ArrayList<>(List.of(
                "--spring.config.location=classpath:/application-day4-test.yml",
                "--spring.config.additional-location=", "--spring.config.import=",
                "--spring.profiles.active=local", "--spring.profiles.include=",
                "--spring.main.banner-mode=off", "--spring.flyway.enabled=false",
                "--spring.sql.init.mode=never", "--server.address=127.0.0.1", "--server.port=0",
                "--server.shutdown=immediate", "--spring.lifecycle.timeout-per-shutdown-phase=3s",
                "--server.servlet.session.cookie.name=" + cookieName,
                "--server.servlet.session.cookie.path=/", "--server.servlet.session.cookie.http-only=true",
                "--server.servlet.session.cookie.same-site=lax",
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                "--spring.datasource.username=redis_session_runtime", "--spring.datasource.password=" + READER_PASSWORD,
                "--spring.datasource.hikari.maximum-pool-size=2", "--spring.datasource.hikari.minimum-idle=0",
                "--supportflow.security.authentication.mode=database",
                "--supportflow.security.authentication.basic-enabled=false",
                "--supportflow.security.authentication.session-timeout=1m",
                "--supportflow.security.session.redis.enabled=true",
                "--supportflow.security.session.redis.namespace=" + NAMESPACE,
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--spring.data.redis.password=" + REDIS_PASSWORD,
                "--spring.data.redis.connect-timeout=300ms", "--spring.data.redis.timeout=300ms",
                "--management.health.redis.enabled=true", "--supportflow.traffic.enabled=false",
                "--supportflow.ai.deepseek.enabled=false", "--supportflow.ai.deepseek.api-key=",
                "--supportflow.rag.enabled=false", "--supportflow.rag.persistent-index-enabled=false",
                "--supportflow.after-sale.execution.enabled=false", "--logging.level.root=WARN"));
        return new SpringApplicationBuilder(SupportFlowApplication.class).run(settings.toArray(String[]::new));
    }

    private static Cookie login(ConfigurableApplicationContext node, String password) throws Exception {
        return login(node, password, "JSESSIONID");
    }

    private static Cookie login(ConfigurableApplicationContext node, String password, String cookieName) throws Exception {
        Csrf csrf = csrf(node, null, cookieName);
        var response = request(node, "POST", "/api/v1/security/login", csrf.cookie(), csrf,
                JSON.writeValueAsString(Map.of("username", USERNAME, "password", password)));
        assertThat(response.statusCode()).isEqualTo(204);
        assertCookieAttributes(response, cookieName);
        return cookie(response, cookieName);
    }

    private static Csrf csrf(ConfigurableApplicationContext node, Cookie cookie) throws Exception {
        return csrf(node, cookie, cookie == null ? "JSESSIONID" : cookie.name());
    }

    private static Csrf csrf(ConfigurableApplicationContext node, Cookie cookie, String cookieName) throws Exception {
        var response = request(node, "GET", "/api/v1/security/csrf", cookie, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        Cookie current = response.headers().allValues("set-cookie").stream().anyMatch(value -> value.startsWith(cookieName + "="))
                ? cookie(response, cookieName) : cookie;
        assertThat(current).isNotNull();
        return new Csrf(body.path("headerName").asString(), body.path("token").asString(), current);
    }

    private static HttpResponse<String> request(ConfigurableApplicationContext node, String method, String path,
                                                 Cookie cookie, Csrf csrf, String body) throws Exception {
        int port = ((WebServerApplicationContext) node).getWebServer().getPort();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(8)).header("Accept", "application/json");
        if (cookie != null) request.header("Cookie", cookie.name() + "=" + cookie.value());
        if (csrf != null) request.header(csrf.header(), csrf.token());
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Cookie cookie(HttpResponse<?> response, String name) {
        String pair = response.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith(name + "=") && !value.substring(name.length() + 1).startsWith(";"))
                .reduce((first, second) -> second).orElseThrow(() -> new AssertionError("Expected the configured session cookie"));
        return new Cookie(name, pair.substring(name.length() + 1).split(";", 2)[0]);
    }

    private static void assertCookieAttributes(HttpResponse<?> response, String name) {
        String value = response.headers().allValues("set-cookie").stream().filter(header -> header.startsWith(name + "="))
                .reduce((first, second) -> second).orElseThrow();
        assertThat(value.toLowerCase()).contains("httponly", "samesite=lax", "path=/");
    }

    @SuppressWarnings("unchecked")
    private static void assertStoredSession(ConfigurableApplicationContext node, Cookie cookie) {
        var repository = (SessionRepository<Session>) node.getBean(SessionRepository.class);
        Session session = repository.findById(sessionId(cookie));
        assertThat(session).isNotNull();
        assertThat(session.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(1));
        SecurityContext context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication().getCredentials()).isNull();
        var principal = (CustomerPrincipal) context.getAuthentication().getPrincipal();
        assertThat(principal.getPassword()).isNull();
        assertThat(principal.getIdentity().buyerId()).isEqualTo(200);
        assertThat(principal.getAuthorities()).contains(SecurityPermission.ORDER_READ);
        var factory = node.getBean(StringRedisTemplate.class).getConnectionFactory();
        assertThat(factory).isNotNull();
        try (var connection = factory.getConnection()) {
            var values = connection.hashCommands().hGetAll(sessionKey(cookie).getBytes(StandardCharsets.UTF_8));
            assertThat(values).isNotEmpty();
            for (byte[] bytes : values.values()) {
                assertThat(new String(bytes, StandardCharsets.ISO_8859_1)).doesNotContain(PASSWORD, PASSWORD_HASH);
            }
        }
    }

    private static String sessionKey(Cookie cookie) { return NAMESPACE + ":sessions:" + sessionId(cookie); }
    private static String sessionId(Cookie cookie) {
        try {
            String decoded = new String(Base64.getDecoder().decode(cookie.value()), StandardCharsets.UTF_8);
            return UUID.fromString(decoded).toString();
        } catch (IllegalArgumentException notBase64Uuid) {
            return UUID.fromString(cookie.value()).toString();
        }
    }
    private record Cookie(String name, String value) { }
    private record Csrf(String header, String token, Cookie cookie) { }
}
