package dev.supportflow.ai.springai;

import dev.supportflow.chat.application.ModelUnavailableException;
import dev.supportflow.chat.infrastructure.deepseek.DeepSeekCallExecutor;
import dev.supportflow.chat.infrastructure.deepseek.DeepSeekCallOperation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.AbstractBufferingClientHttpRequest;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Spring AI owns the protocol. The existing executor remains the sole network/admission boundary. */
final class SpringAiTransport implements ClientHttpRequestFactory {
    private static final URI ENDPOINT = URI.create("https://api.deepseek.com/chat/completions");
    private final DeepSeekCallExecutor executor;
    private final ThreadLocal<Call> current = new ThreadLocal<>();

    SpringAiTransport(DeepSeekCallExecutor executor) {
        this.executor = executor;
    }

    <T> T call(DeepSeekCallOperation operation, Duration timeout, int inputAllowance,
               Consumer<byte[]> validateResponse, Supplier<T> request) {
        if (current.get() != null) throw new ModelUnavailableException();
        current.set(new Call(operation, timeout, inputAllowance, validateResponse));
        try {
            return request.get();
        } catch (dev.supportflow.common.traffic.TrafficLimitExceededException | ModelUnavailableException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // RetryTemplate wraps even a zero-retry failure. Preserve only our already-sanitized errors.
            Throwable cause = failure;
            for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
                if (cause instanceof ModelUnavailableException safe) throw safe;
                if (cause instanceof dev.supportflow.common.traffic.TrafficLimitExceededException safe) throw safe;
            }
            // Framework exceptions may contain credentials, prompts or provider bodies. Do not attach them.
            throw new ModelUnavailableException();
        } finally {
            current.remove();
        }
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
        if (!ENDPOINT.equals(uri) || method != HttpMethod.POST) throw new ModelUnavailableException();
        return new AbstractBufferingClientHttpRequest() {
            @Override public HttpMethod getMethod() { return method; }
            @Override public URI getURI() { return uri; }

            @Override
            protected ClientHttpResponse executeInternal(HttpHeaders headers, byte[] body) {
                Call call = current.get();
                // Also prevents accidental SDK retry/advisor loops from making a second provider request.
                if (call == null || call.requested || (long) body.length + 2048 > call.inputAllowance) {
                    throw new ModelUnavailableException();
                }
                call.requested = true;
                byte[] response = executor.execute(call.operation, new String(body, StandardCharsets.UTF_8), bytes -> {
                    // Strict wire validation happens inside the existing metrics/circuit/parser boundary,
                    // before Spring AI can log an invalid response together with a prompt.
                    call.validateResponse.accept(bytes);
                    return bytes;
                }, call.timeout);
                return new ClientHttpResponse() {
                    @Override public HttpStatusCode getStatusCode() { return HttpStatus.OK; }
                    @Override public String getStatusText() { return "OK"; }
                    @Override public HttpHeaders getHeaders() {
                        var result = new HttpHeaders();
                        result.setContentType(MediaType.APPLICATION_JSON);
                        return result;
                    }
                    @Override public InputStream getBody() { return new ByteArrayInputStream(response); }
                    @Override public void close() { }
                };
            }
        };
    }

    private static final class Call {
        private final DeepSeekCallOperation operation;
        private final Duration timeout;
        private final int inputAllowance;
        private final Consumer<byte[]> validateResponse;
        private boolean requested;

        private Call(DeepSeekCallOperation operation, Duration timeout, int inputAllowance, Consumer<byte[]> validateResponse) {
            this.operation = operation;
            this.timeout = timeout;
            this.inputAllowance = inputAllowance;
            this.validateResponse = validateResponse;
        }
    }
}
