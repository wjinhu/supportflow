package dev.supportflow.common.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Shared session storage is opt-in; namespaces isolate local, LAB and test identities. */
@ConfigurationProperties(prefix = "supportflow.security.session.redis")
public record RedisSessionProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("supportflow:local:session") String namespace
) {
    public RedisSessionProperties {
        if (namespace == null || !namespace.matches("[A-Za-z0-9][A-Za-z0-9:_-]{2,127}")) {
            throw new IllegalArgumentException("Redis session namespace must be a bounded explicit environment namespace");
        }
    }
}
