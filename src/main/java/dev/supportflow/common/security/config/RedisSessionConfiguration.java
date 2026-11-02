package dev.supportflow.common.security.config;

import dev.supportflow.common.security.api.SecurityErrorResponseWriter;
import dev.supportflow.common.security.session.RedisSessionFailureFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.health.DataRedisHealthIndicator;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;

/** Wrap the existing HttpSession contract only when shared login sessions are explicitly enabled. */
@Configuration(proxyBeanMethods = false)
@Profile({"local", "lab"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "supportflow.security.session.redis", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({RedisSessionProperties.class, BrowserAuthenticationProperties.class, ServerProperties.class})
@EnableRedisHttpSession
public class RedisSessionConfiguration {

    @Bean
    SessionRepositoryCustomizer<RedisSessionRepository> sharedSessionRepository(
            RedisSessionProperties redis, BrowserAuthenticationProperties authentication) {
        return repository -> {
            repository.setRedisKeyNamespace(redis.namespace());
            repository.setDefaultMaxInactiveInterval(authentication.sessionTimeout());
        };
    }

    @Bean
    CookieSerializer cookieSerializer(ServerProperties server) {
        var cookie = server.getServlet().getSession().getCookie();
        var serializer = new DefaultCookieSerializer();
        serializer.setCookieName(cookie.getName() == null ? "JSESSIONID" : cookie.getName());
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setUseBase64Encoding(false);
        if (cookie.getPath() != null) serializer.setCookiePath(cookie.getPath());
        if (cookie.getDomain() != null) serializer.setDomainName(cookie.getDomain());
        // With no explicit override the serializer follows request.isSecure(), including trusted forwarded HTTPS.
        if (cookie.getSecure() != null) serializer.setUseSecureCookie(cookie.getSecure());
        return serializer;
    }

    @Bean
    FilterRegistrationBean<RedisSessionFailureFilter> sharedSessionFailureRegistration(SecurityErrorResponseWriter errors) {
        var registration = new FilterRegistrationBean<>(new RedisSessionFailureFilter(errors));
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER - 1);
        registration.setName("sharedSessionFailureFilter");
        return registration;
    }

    // Redis is absent from health entirely in the unchanged, single-process session mode.
    @Bean
    DataRedisHealthIndicator redisHealthIndicator(RedisConnectionFactory connections) {
        return new DataRedisHealthIndicator(connections);
    }
}
