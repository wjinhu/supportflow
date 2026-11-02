package dev.supportflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(excludeName = {
        // The explicit Spring AI adapter owns transport protection even when a profile replaces application.yml.
        "org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration",
        "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration",
        "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.health.DataRedisHealthContributorAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.health.DataRedisReactiveHealthContributorAutoConfiguration"
})
public class SupportFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupportFlowApplication.class, args);
    }
}
