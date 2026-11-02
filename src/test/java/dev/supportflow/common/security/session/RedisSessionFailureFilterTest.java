package dev.supportflow.common.security.session;

import dev.supportflow.common.security.api.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisSessionFailureFilterTest {
    private final RedisSessionFailureFilter filter = new RedisSessionFailureFilter(
            new SecurityErrorResponseWriter(JsonMapper.builder().build()));

    @ParameterizedTest
    @CsvSource({"GET,/api/v1/security/csrf", "POST,/api/v1/security/login", "POST,/api/v1/security/logout"})
    void aFailedSharedSessionCommitDiscardsSuccessfulExchangeBytesAndCookies(String method, String path) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        var response = new MockHttpServletResponse();
        var calls = new AtomicInteger();
        FilterChain exchangeThenFailedCommit = (ignored, output) -> {
            calls.incrementAndGet();
            var exchange = (HttpServletResponse) output;
            exchange.setStatus(204);
            exchange.setContentType("application/json");
            exchange.addCookie(new Cookie("JSESSIONID", "uncommitted-session-id"));
            exchange.getWriter().write("{\"token\":\"uncommitted-csrf-token\",\"password\":\"private-fixture-password\"}");
            exchange.flushBuffer();
            throw new RedisConnectionFailureException("private-redis-connection-detail");
        };

        filter.doFilter(request, response, exchangeThenFailedCommit);

        assertThat(calls).hasValue(1);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        assertThat(response.getCookie("JSESSIONID")).isNull();
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":\"SESSION_UNAVAILABLE\"")
                .doesNotContain("uncommitted-session-id", "uncommitted-csrf-token", "private-fixture-password",
                        "private-redis-connection-detail");
    }

    @Test
    void anAlreadyCommittedStreamGetsNoAppendedJsonAndIsNeverReplayed() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/shops/100/chat/conversations/example/messages");
        var response = new MockHttpServletResponse();
        var calls = new AtomicInteger();
        String partial = "event: delta\ndata: {\"text\":\"partial\"}\n\n";

        filter.doFilter(request, response, (ignored, output) -> {
            calls.incrementAndGet();
            var stream = (HttpServletResponse) output;
            stream.setContentType("text/event-stream");
            stream.getOutputStream().write(partial.getBytes(StandardCharsets.UTF_8));
            stream.flushBuffer();
            throw new RedisConnectionFailureException("private-redis-connection-detail");
        });

        assertThat(calls).hasValue(1);
        assertThat(response.isCommitted()).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("text/event-stream");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(partial);
    }

    @Test
    void aWrappedRedisStorageFailureStillProducesOnlyTheSafeUnavailableResponse() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/security/me");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignored, output) -> {
            throw new ServletException("wrapper", new RedisConnectionFailureException("private-connection-detail"));
        });

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("SESSION_UNAVAILABLE").doesNotContain("wrapper", "private-connection-detail");
    }

    @Test
    void anUnrelatedApplicationFailurePropagatesWithoutBeingRelabelledAsRedis() {
        var request = new MockHttpServletRequest("GET", "/api/v1/orders");
        var response = new MockHttpServletResponse();
        var expected = new IllegalStateException("unrelated application failure");

        assertThatThrownBy(() -> filter.doFilter(request, response, (ignored, output) -> { throw expected; }))
                .isSameAs(expected);
        assertThat(response.getContentAsByteArray()).isEmpty();
    }
}
