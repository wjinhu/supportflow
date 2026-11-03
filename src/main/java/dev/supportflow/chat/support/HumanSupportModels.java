package dev.supportflow.chat.support;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import tools.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.supportflow.chat.conversation.ConversationIds;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public views deliberately omit internal identities, AI prompts and business operation controls. */
public final class HumanSupportModels {
    private HumanSupportModels() { }
    public enum Status { WAITING, ACTIVE, CLOSED }
    public enum Role { BUYER, STAFF }
    public enum CloseReason { MANUAL, BUYER, STAFF, INACTIVITY }
    public record FeedbackView(int rating, String comment, Instant createdAt) { }
    public record FeedbackRequest(@NotNull @Min(1) @Max(5) Integer rating, @Size(max = 500) String comment) {
        @JsonCreator
        public static FeedbackRequest fromJson(@JsonProperty("rating") JsonNode rating, @JsonProperty("comment") String comment) {
            if (rating == null || !rating.isIntegralNumber() || !rating.canConvertToInt()) throw HumanSupportException.invalidFeedback();
            return new FeedbackRequest(rating.intValue(), comment);
        }
    }
    public record StartRequest(@NotNull UUID requestId) {
        @JsonCreator
        public static StartRequest fromJson(@JsonProperty("requestId") String id) {
            return new StartRequest(ConversationIds.parse(id));
        }
    }
    public record MessageRequest(@NotNull UUID clientMessageId,
                                 @NotBlank @Size(max = 2000) String text) {
        @JsonCreator
        public static MessageRequest fromJson(@JsonProperty("clientMessageId") String id, @JsonProperty("text") String text) {
            return new MessageRequest(ConversationIds.parse(id), text);
        }
    }
    public record SessionView(UUID id, Status status, boolean assigned, boolean assignedToMe,
                              Instant createdAt, Instant updatedAt, Instant closedAt,
                              Instant lastMessageAt, CloseReason closeReason, FeedbackView feedback) { }
    public record MessageView(UUID id, UUID clientMessageId, Role role, String text, Instant createdAt) { }
    public record Snapshot(SessionView session, List<MessageView> messages, UUID nextBeforeId) { }
    public record MessagePage(List<MessageView> messages, UUID nextBeforeId) { }
    public record SessionPage(List<SessionView> items, UUID nextBeforeId) { }
}
