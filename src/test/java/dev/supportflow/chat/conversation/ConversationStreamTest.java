package dev.supportflow.chat.conversation;

import dev.supportflow.chat.api.response.ChatResponse;
import dev.supportflow.chat.application.model.ChatReplyType;
import dev.supportflow.chat.application.model.KnowledgeEvidence;
import dev.supportflow.chat.application.model.KnowledgeSource;
import dev.supportflow.chat.application.model.KnowledgeStatus;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ConversationStreamTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CustomerPrincipal buyer = new CustomerPrincipal("buyer", "unused", new CustomerIdentity(200), List.of());
    private final UUID id = UUID.randomUUID();
    private final ConversationMessageRequest request = new ConversationMessageRequest(UUID.randomUUID(), "hello", null);

    @Test
    void flushesOrderedUnicodeSafeReplyDeltasAndPreservesCanonicalAuditOnlyInCompletion() throws Exception {
        String reply = "订单状态正常，" + "商品📦".repeat(20) + "\n包含\"引号\"和\\反斜线。\nevent: forged\ndata: forged";
        ConversationTurnResponse turn = turn(reply);
        var response = new RecordingResponse();

        new ConversationController(service(turn), json).stream(buyer, 100, id.toString(), request, response);

        assertThat(response.getContentType()).startsWith("text/event-stream");
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
        String[] frames = response.getContentAsString(StandardCharsets.UTF_8).split("\n\n");
        StringBuilder assembled = new StringBuilder();
        for (int index = 0; index < frames.length - 1; index++) {
            String frame = frames[index];
            assertThat(frame).startsWith("event: delta\ndata: ");
            assertThat(frame).doesNotContain("AUDIT-ONLY-SOURCE", "knowledge", "event: completed\n");
            String delta = json.readTree(frame.substring("event: delta\ndata: ".length())).path("text").asString();
            assertThat(delta.codePointCount(0, delta.length())).isBetween(1, 16);
            assertThat(delta.charAt(0)).matches(character -> !Character.isLowSurrogate(character));
            assertThat(delta.charAt(delta.length() - 1)).matches(character -> !Character.isHighSurrogate(character));
            assembled.append(delta);
            assertThat(response.flushed.get(index)).endsWith(frame + "\n\n");
        }
        assertThat(assembled.toString()).isEqualTo(reply);
        String completed = frames[frames.length - 1];
        assertThat(completed).startsWith("event: completed\ndata: ");
        assertThat(json.readTree(completed.substring("event: completed\ndata: ".length())))
                .isEqualTo(json.readTree(json.writeValueAsString(turn)));
        assertThat(response.flushed).hasSize(frames.length).hasSizeGreaterThan(2);
    }

    @Test
    void serviceFailureDoesNotCommitStreamHeadersOrAnyReplyBytes() {
        var service = mock(ConversationService.class);
        when(service.send(buyer, 100, id, request)).thenThrow(ConversationException.notFound());
        var response = new MockHttpServletResponse();

        assertThatThrownBy(() -> new ConversationController(service, json)
                .stream(buyer, 100, id.toString(), request, response)).isInstanceOf(ConversationException.class);

        assertThat(response.isCommitted()).isFalse();
        assertThat(response.getContentType()).isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
        verify(service).send(buyer, 100, id, request);
        verifyNoMoreInteractions(service);
    }

    @Test
    void disconnectedReaderNeverReexecutesOrRevertsTheAlreadyPersistedTurn() {
        var service = service(turn("已保存的订单回复"));
        var response = new MockHttpServletResponse() {
            @Override public ServletOutputStream getOutputStream() {
                return new ServletOutputStream() {
                    @Override public boolean isReady() { return true; }
                    @Override public void setWriteListener(WriteListener listener) { }
                    @Override public void write(int value) throws IOException { throw new IOException("isolated disconnect"); }
                };
            }
        };

        assertThatThrownBy(() -> new ConversationController(service, json)
                .stream(buyer, 100, id.toString(), request, response)).isInstanceOf(IOException.class);

        verify(service).send(buyer, 100, id, request);
        verifyNoMoreInteractions(service);
    }

    private ConversationService service(ConversationTurnResponse turn) {
        var service = mock(ConversationService.class);
        when(service.send(buyer, 100, id, request)).thenReturn(turn);
        return service;
    }

    private ConversationTurnResponse turn(String reply) {
        Instant at = Instant.parse("2026-11-01T00:00:00Z");
        var evidence = new KnowledgeEvidence(KnowledgeStatus.ANSWERED,
                List.of(new KnowledgeSource("kb-source", "v1", "AUDIT-ONLY-SOURCE", "AUDIT-ONLY-SOURCE-CONTENT")));
        var response = new ChatResponse(reply, ChatReplyType.TEXT, List.of(), null, evidence);
        return new ConversationTurnResponse(new Conversation(id, 100, at, at), List.of(
                new ConversationMessage(UUID.randomUUID(), ConversationRole.USER, request.message(), at, null),
                new ConversationMessage(UUID.randomUUID(), ConversationRole.ASSISTANT, reply, at, response)));
    }

    private static final class RecordingResponse extends MockHttpServletResponse {
        final List<String> flushed = new ArrayList<>();
        @Override public void flushBuffer() {
            flushed.add(new String(getContentAsByteArray(), StandardCharsets.UTF_8));
            super.flushBuffer();
        }
    }
}
