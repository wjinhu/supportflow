package dev.supportflow.chat.application.model;

import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.SupportOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatReplyTest {
    private final SupportOrder order = new SupportOrder(1, 100, 200, 200, "SF10001", OrderStatus.SHIPPED,
            new BigDecimal("100.00"), "CNY", Instant.EPOCH, Instant.EPOCH);

    @Test
    void humanSupportPromptCarriesOnlyTheExplicitActionAndNoBusinessData() {
        ChatReply prompt = ChatReply.humanSupport();
        assertThat(prompt.type()).isEqualTo(ChatReplyType.HUMAN_SUPPORT);
        assertThat(prompt.orders()).isEmpty();
        assertThat(prompt.knowledge()).isNull();
        assertThatThrownBy(() -> new ChatReply("contact", ChatReplyType.HUMAN_SUPPORT, List.of(order), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatReply("contact", ChatReplyType.HUMAN_SUPPORT, List.of(), 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRepresentAnUnselectedOrSingleOrderRefundForm() {
        assertThat(ChatReply.refundForm("选择订单", null).orders()).isEmpty();
        ChatReply selected = ChatReply.refundForm("填写退款原因", order);
        assertThat(selected.type()).isEqualTo(ChatReplyType.AFTER_SALE_FORM);
        assertThat(selected.orders()).containsExactly(order);
        assertThat(selected.nextAfterId()).isNull();
    }

    @Test
    void shouldRejectAmbiguousOrPaginatedRefundForms() {
        assertThatThrownBy(() -> new ChatReply("reason", ChatReplyType.AFTER_SALE_FORM,
                List.of(order, order), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatReply("reason", ChatReplyType.AFTER_SALE_FORM,
                List.of(order), 1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldKeepRefundOrderSelectionImmutableAfterCreation() {
        List<SupportOrder> source = new ArrayList<>(List.of(order));
        ChatReply reply = new ChatReply("reason", ChatReplyType.AFTER_SALE_FORM, source, null);
        source.clear();
        assertThat(reply.orders()).containsExactly(order);
        assertThatThrownBy(() -> reply.orders().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
