package dev.supportflow.ai.springai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Selects an adapter, never an authorization or a different business policy. */
@ConfigurationProperties(prefix = "supportflow.ai")
public record SpringAiProperties(@DefaultValue("custom") String planner) {
    public SpringAiProperties {
        if (!"custom".equals(planner) && !"spring-ai".equals(planner)) {
            throw new IllegalArgumentException("AI planner must be custom or spring-ai");
        }
    }
}
