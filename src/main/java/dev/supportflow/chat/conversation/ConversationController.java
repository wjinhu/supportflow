package dev.supportflow.chat.conversation;

import dev.supportflow.common.security.model.CustomerPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;

@RestController
@RequestMapping("/api/v1/shops/{shopId}/chat/conversations")
public class ConversationController {
    private final ConversationService service;
    private final ObjectMapper json;

    public ConversationController(ConversationService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Conversation create(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                               @PathVariable @Positive long shopId) {
        return service.create(principal, shopId);
    }

    @GetMapping
    public ConversationPage list(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                                 @PathVariable @Positive long shopId,
                                 @RequestParam(required = false) String beforeId) {
        return service.list(principal, shopId, ConversationIds.parse(beforeId));
    }

    @GetMapping("/{conversationId}/messages")
    public ConversationMessages messages(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive long shopId, @PathVariable String conversationId,
            @RequestParam(required = false) String beforeId) {
        return service.messages(principal, shopId, ConversationIds.parse(conversationId), ConversationIds.parse(beforeId));
    }

    @PostMapping(value = "/{conversationId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public ConversationTurnResponse send(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive long shopId, @PathVariable String conversationId,
            @Valid @RequestBody ConversationMessageRequest request) {
        return service.send(principal, shopId, ConversationIds.parse(conversationId), request);
    }

    @PostMapping(value = "/{conversationId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void stream(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive long shopId, @PathVariable String conversationId,
            @Valid @RequestBody ConversationMessageRequest request, HttpServletResponse response) throws IOException {
        // A successful, durable turn is the only source of streamed text. Model plans and tool observations
        // stay internal; failures still use the normal HTTP/JSON error contract before any bytes are committed.
        ConversationTurnResponse turn = service.send(principal, shopId, ConversationIds.parse(conversationId), request);
        ConversationStream.write(turn, json, response);
    }
}
