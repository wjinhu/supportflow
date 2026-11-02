package dev.supportflow.ai.springai;

import dev.supportflow.assistant.knowledge.KnowledgeService;
import dev.supportflow.chat.infrastructure.deepseek.DeepSeekCallExecutor;
import dev.supportflow.chat.infrastructure.deepseek.DeepSeekProperties;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SpringAiProperties.class)
public class SpringAiConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "supportflow.ai", name = "planner", havingValue = "spring-ai")
    SpringAiTransport springAiTransport(DeepSeekCallExecutor executor) {
        return new SpringAiTransport(executor);
    }

    @Bean
    @ConditionalOnProperty(prefix = "supportflow.ai", name = "planner", havingValue = "spring-ai")
    ChatModel springAiChatModel(SpringAiTransport transport, DeepSeekProperties properties,
                               ObjectProvider<ObservationRegistry> observations) {
        return model(transport, properties, observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    static DeepSeekChatModel model(SpringAiTransport transport, DeepSeekProperties properties, ObservationRegistry observations) {
        var api = DeepSeekApi.builder().apiKey(properties.apiKey())
                .restClientBuilder(RestClient.builder().requestFactory(transport))
                // Streaming needs a separate bounded/cancellable transport contract before it can be enabled.
                .webClientBuilder(org.springframework.web.reactive.function.client.WebClient.builder()
                        .exchangeFunction(request -> reactor.core.publisher.Mono.error(
                                new dev.supportflow.chat.application.ModelUnavailableException())))
                .build();
        return DeepSeekChatModel.builder().deepSeekApi(api)
                .options(DeepSeekChatOptions.builder().model(properties.model()).disableThinking().build())
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
                .observationRegistry(observations).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "supportflow.ai", name = "planner", havingValue = "spring-ai")
    ChatClient springAiChatClient(ChatModel model, ObjectProvider<ObservationRegistry> observations) {
        return client(model, observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    static ChatClient client(ChatModel model, ObservationRegistry observations) {
        return ChatClient.builder(model, observations, null, null)
                .defaultAdvisors(spec -> spec.param(ChatClientAttributes.TOOL_CALLING_ADVISOR_AUTO_REGISTER.getKey(), false))
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "supportflow.ai", name = "planner", havingValue = "spring-ai")
    SpringAiAgentDecisionPlanner springAiAgentDecisionPlanner(ChatModel model, SpringAiTransport transport,
                                                            DeepSeekProperties properties, ObjectMapper json) {
        return new SpringAiAgentDecisionPlanner(model, transport, properties, json);
    }

    @Bean
    @ConditionalOnProperty(name = {"supportflow.ai.planner"}, havingValue = "spring-ai")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
            "${supportflow.rag.enabled:false} && ${supportflow.rag.public-evidence-selection-enabled:false}")
    SpringAiKnowledgeAdapter springAiKnowledgeAdapter(ChatClient client, SpringAiTransport transport,
            DeepSeekProperties properties, ObjectMapper json, ObjectProvider<KnowledgeService> knowledge) {
        return new SpringAiKnowledgeAdapter(client, transport, properties, json, knowledge);
    }
}
