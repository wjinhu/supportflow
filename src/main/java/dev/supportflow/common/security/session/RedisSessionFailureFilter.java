package dev.supportflow.common.security.session;

import dev.supportflow.common.api.error.ErrorCode;
import dev.supportflow.common.security.api.SecurityErrorResponseWriter;
import io.lettuce.core.RedisException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

/** A shared-session outage is unavailable, never an anonymous or local-session fallback. */
public final class RedisSessionFailureFilter extends OncePerRequestFilter {
    private final SecurityErrorResponseWriter errors;

    public RedisSessionFailureFilter(SecurityErrorResponseWriter errors) { this.errors = errors; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !path(request).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Session creation can fail on the final Redis commit. Do not leak a CSRF token or a successful
        // login/logout body before that commit is known; ordinary replies and SSE remain unbuffered.
        String path = path(request);
        boolean exchange = path.equals("/api/v1/security/csrf") || path.equals("/api/v1/security/login")
                || path.equals("/api/v1/security/logout");
        var cached = exchange ? new ContentCachingResponseWrapper(response) : null;
        try {
            chain.doFilter(request, cached == null ? response : cached);
            if (cached != null) cached.copyBodyToResponse();
        } catch (RuntimeException | ServletException failure) {
            if (!storageFailure(failure)) throw failure;
            if (!response.isCommitted()) {
                response.reset();
                response.setHeader("Cache-Control", "no-store");
                response.setHeader("X-Content-Type-Options", "nosniff");
                errors.write(response, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SESSION_UNAVAILABLE);
            }
            // A committed stream cannot change its HTTP status. Closing it leaves the client with an
            // incomplete frame, which the client rejects without replaying the already processed turn.
        }
    }

    private static boolean storageFailure(Throwable failure) {
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure instanceof RedisConnectionFailureException || failure instanceof RedisSystemException
                    || failure instanceof SerializationException || failure instanceof RedisException) return true;
        }
        return false;
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
