package dev.supportflow.ai.springai;

import dev.supportflow.chat.infrastructure.deepseek.DeepSeekCallExecutor;
import dev.supportflow.chat.infrastructure.deepseek.DeepSeekProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Runs the actual SDK/model/RestClient stack; only the final HTTP transport is synthetic. */
final class SpringAiTestProvider {
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = mock(HttpClient.class);
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final DeepSeekProperties properties = new DeepSeekProperties(true, "fake-spring-ai-test-key", "deepseek-flash",
            Duration.ofSeconds(1), Duration.ofSeconds(1), 2);
    final SpringAiTransport transport = new SpringAiTransport(new DeepSeekCallExecutor(http, properties, meters));
    final ChatModel model = SpringAiConfiguration.model(transport, properties, ObservationRegistry.NOOP);
    final ChatClient client = SpringAiConfiguration.client(model, ObservationRegistry.NOOP);
    final SpringAiAgentDecisionPlanner planner = new SpringAiAgentDecisionPlanner(model, transport, properties, json);
    final List<String> requests = new ArrayList<>();
    final ArrayDeque<Reply> replies = new ArrayDeque<>();

    @SuppressWarnings("unchecked")
    SpringAiTestProvider() {
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            requests.add(body(request));
            Reply reply = replies.removeFirst();
            if (reply.pending) return new CompletableFuture<HttpResponse<byte[]>>();
            HttpResponse<byte[]> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(reply.status);
            when(response.body()).thenReturn(reply.body.getBytes(StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(response);
        });
    }

    void decision(String action, String arguments, String id) {
        reply(200, json.writeValueAsString(Map.of("id", "fake-completion", "model", "deepseek-flash", "created", 1,
                "choices", List.of(Map.of("index", 0, "finish_reason", "tool_calls", "message", Map.of("role", "assistant",
                        "tool_calls", List.of(Map.of("id", id, "type", "function", "function", Map.of("name", action, "arguments", arguments)))))),
                "usage", Map.of("prompt_tokens", 300, "completion_tokens", 25, "total_tokens", 325))));
    }

    void selection(String content) {
        reply(200, json.writeValueAsString(Map.of("id", "fake-selection", "model", "deepseek-flash", "created", 1,
                "choices", List.of(Map.of("index", 0, "finish_reason", "stop", "message", Map.of("role", "assistant", "content", content))),
                "usage", Map.of("prompt_tokens", 300, "completion_tokens", 25, "total_tokens", 325))));
    }

    void reply(int status, String body) { replies.addLast(new Reply(status, body, false)); }
    void stall() { replies.addLast(new Reply(200, "", true)); }

    private static String body(HttpRequest request) throws Exception {
        var result = new CompletableFuture<String>();
        var bytes = new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
            @Override public void onError(Throwable throwable) { result.completeExceptionally(throwable); }
            @Override public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return result.get(1, java.util.concurrent.TimeUnit.SECONDS);
    }

    record Reply(int status, String body, boolean pending) { }
}
