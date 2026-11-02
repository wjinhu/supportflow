package dev.supportflow.chat.conversation;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Streams only the already validated and persisted customer reply, never a model's intermediate output. */
final class ConversationStream {
    private static final int CHUNK_CODE_POINTS = 16;

    private ConversationStream() { }

    static void write(ConversationTurnResponse turn, ObjectMapper json, HttpServletResponse response) throws IOException {
        ConversationMessage assistant = turn.messages().stream()
                .filter(message -> message.role() == ConversationRole.ASSISTANT).findFirst().orElseThrow();
        // Serialize the canonical completion before starting: a serialization failure must not leave a partial turn.
        byte[] completion = frame("completed", turn, json);
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        String text = assistant.text();
        int remaining = text.codePointCount(0, text.length());
        for (int offset = 0; remaining > 0;) {
            int count = Math.min(CHUNK_CODE_POINTS, remaining);
            int end = text.offsetByCodePoints(offset, count);
            response.getOutputStream().write(frame("delta", new Delta(text.substring(offset, end)), json));
            response.flushBuffer();
            offset = end;
            remaining -= count;
        }
        response.getOutputStream().write(completion);
        response.flushBuffer();
        // The servlet owns its output stream. A disconnect propagates without retrying the persisted operation.
    }

    private static byte[] frame(String event, Object value, ObjectMapper json) {
        return ("event: " + event + "\ndata: " + json.writeValueAsString(value) + "\n\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private record Delta(String text) { }
}
