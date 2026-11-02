package dev.supportflow.ai.springai;

import dev.supportflow.assistant.runtime.model.AgentAction;
import dev.supportflow.assistant.runtime.model.AgentLoopState;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.stream.Stream;

/** Schema-only callbacks have no reference to business services and cannot execute a tool. */
final class SpringAiToolDefinitions {
    static final BeanOutputConverter<OrderArgument> ARGUMENTS = new BeanOutputConverter<>(OrderArgument.class);

    record OrderArgument(String orderNo) { }

    private SpringAiToolDefinitions() { }

    static List<ToolCallback> forState(AgentLoopState state) {
        return Stream.of(AgentAction.values()).filter(action -> switch (action) {
            case ORDER_LOOKUP -> state.order() == null;
            case LOGISTICS_LOOKUP -> state.order() != null && state.logistics() == null
                    && (state.order().orderStatus() == dev.supportflow.order.domain.model.OrderStatus.SHIPPED
                    || state.order().orderStatus() == dev.supportflow.order.domain.model.OrderStatus.COMPLETED);
            case ANSWER, CLARIFY -> true;
        }).map(SpringAiToolDefinitions::schema).toList();
    }

    private static ToolCallback schema(AgentAction action) {
        ToolDefinition definition = ToolDefinition.builder().name(action.code())
                .description(switch (action) {
                    case ORDER_LOOKUP -> "Read the authenticated buyer's exact order in the server-selected shop. Read only.";
                    case LOGISTICS_LOOKUP -> "Read logistics only after current ownership and shipped/completed status checks.";
                    case ANSWER -> "Finish using only facts from completed read-only observations.";
                    case CLARIFY -> "Request an explicit order selection when absent or ambiguous; orderNo must be null.";
                } + " Never modify orders, refund, approve, or change buyer, shop or permissions.")
                .inputSchema("""
                        {"type":"object","additionalProperties":false,
                         "properties":{"orderNo":{"type":["string","null"],"maxLength":64}},
                         "required":["orderNo"]}
                        """).build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String input) {
                throw new AccessDeniedException("Only AgentLoopExecutor may execute business tools");
            }
        };
    }
}
