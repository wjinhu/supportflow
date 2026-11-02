package dev.supportflow.ai.springai;

import dev.supportflow.assistant.runtime.planning.AgentDecisionPlanner;
import dev.supportflow.assistant.runtime.planning.RuleBasedAgentDecisionPlanner;
import dev.supportflow.chat.infrastructure.deepseek.DeepSeekAgentDecisionPlanner;
import dev.supportflow.chat.infrastructure.deepseek.DeepSeekConfiguration;
import dev.supportflow.common.traffic.TrafficConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiConfigurationTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(SpringAiConfiguration.class, DeepSeekConfiguration.class, TrafficConfiguration.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new).withBean(ObjectMapper.class, ObjectMapper::new);

    @Test void defaultsToExistingOfflinePlannerWithoutApiKey() {
        contexts.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AgentDecisionPlanner.class).doesNotHaveBean(ChatClient.class);
            assertThat(context.getBean(AgentDecisionPlanner.class)).isInstanceOf(RuleBasedAgentDecisionPlanner.class);
        });
    }

    @Test void existingPaidAdapterRemainsSelectableWithoutCreatingASpringAiClient() {
        contexts.withPropertyValues("supportflow.ai.deepseek.enabled=true", "supportflow.ai.deepseek.api-key=fake-key")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AgentDecisionPlanner.class).doesNotHaveBean(ChatClient.class);
                    assertThat(context.getBean(AgentDecisionPlanner.class)).isInstanceOf(DeepSeekAgentDecisionPlanner.class);
                });
    }

    @Test void springAiModeHasOnePlannerClientAndZeroProviderRetries() {
        contexts.withPropertyValues("supportflow.ai.planner=spring-ai", "supportflow.ai.deepseek.enabled=true",
                        "supportflow.ai.deepseek.api-key=fake-key").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AgentDecisionPlanner.class).hasSingleBean(ChatClient.class);
            assertThat(context.getBean(AgentDecisionPlanner.class)).isInstanceOf(SpringAiAgentDecisionPlanner.class);
            var model = (org.springframework.ai.deepseek.DeepSeekChatModel) context.getBean(org.springframework.ai.chat.model.ChatModel.class);
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> model.retryTemplate.execute(() -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("test failure");
            })).isInstanceOf(org.springframework.core.retry.RetryException.class);
            assertThat(attempts.get()).isEqualTo(1);
            assertThat(model.getOptions().getToolCallbacks()).isNullOrEmpty();
        });
    }

    @Test void invalidOrDisabledSpringAiConfigurationFailsWithoutNetworkCalls() {
        contexts.withPropertyValues("supportflow.ai.planner=typo").run(context -> assertThat(context).hasFailed());
        contexts.withPropertyValues("supportflow.ai.planner=spring-ai").run(context -> assertThat(context).hasFailed());
    }
}
