package dev.supportflow.chat.application;

import dev.supportflow.chat.application.model.OrderIntent;
import dev.supportflow.chat.application.model.OrderIntentType;
import dev.supportflow.chat.application.model.ChatReply;
import dev.supportflow.chat.application.port.OrderIntentRecognizer;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.order.application.OrderQueryService;
import dev.supportflow.order.application.model.OrderPage;
import dev.supportflow.order.domain.OrderQueryLimits;
import dev.supportflow.order.domain.model.SupportOrder;
import dev.supportflow.assistant.planning.ExplicitOrderNumbers;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 店铺由HTTP路径提供，身份由认证提供；模型只识别订单号。
 * 缺订单号时返回当前店铺本人订单供前端选择，不要求用户输入商铺ID。
 */
@Service
public class ChatService {

    private static final Pattern GREETING = Pattern.compile(
            "(?:你好|您好|嗨|哈喽|hello|hi|在吗|谢谢|多谢|再见)[！!？?。,.，\\s]*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDER_MENU = Pattern.compile("^(?:(?:请|麻烦)?帮我)?(?:查询|查看|看看|查一下|看下)(?:我的)?订单[？?！!。\\s]*$");
    private static final DateTimeFormatter SUMMARY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final OrderQueryService orderQueryService;
    private final OrderIntentRecognizer orderIntentRecognizer;

    public ChatService(OrderQueryService orderQueryService, OrderIntentRecognizer orderIntentRecognizer) {
        this.orderQueryService = Objects.requireNonNull(orderQueryService, "orderQueryService must not be null");
        this.orderIntentRecognizer = Objects.requireNonNull(
                orderIntentRecognizer, "orderIntentRecognizer must not be null");
    }

    public ChatReply reply(CustomerIdentity identity, long shopId, String message, String selectedOrderNo) {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(message, "message must not be null");
        if (shopId <= 0) {
            throw new IllegalArgumentException("shopId must be positive");
        }
        validateSelectedOrderNo(selectedOrderNo);
        String normalized = message.strip();
        if (SpendingQuestion.matches(normalized)) {
            var clarification = SpendingQuestion.clarification(normalized);
            if (clarification.isPresent()) return ChatReply.text(clarification.get());
            var summary = orderQueryService.summarizeOwnedPaidOrdersLast30Days(identity);
            return ChatReply.text("截至 " + SUMMARY_TIME.format(summary.until()) + "（北京时间），近30天下单的已付款订单，你在全部店铺共有 " + summary.orderCount()
                    + " 笔，订单金额合计 ¥" + summary.totalAmount().toPlainString()
                    + "。\n这是订单金额汇总，包含已隐藏订单，未扣除退款。");
        }
        if (selectedOrderNo != null
                && (normalized.equals("咨询此订单") || normalized.equals("查询此订单"))) {
            return replyWithOrder(identity, shopId, selectedOrderNo);
        }
        if (normalized.equals("查订单") || normalized.equals("订单") || ORDER_MENU.matcher(normalized).matches()) {
            return replyWithOrderList(identity, shopId, 0L, true);
        }
        Optional<OrderListCommand> listCommand = OrderListCommand.parse(message);
        if (listCommand.isPresent()) {
            return replyWithOrderList(identity, shopId, listCommand.get().afterId(), false);
        }

        Optional<OrderLookupCommand> command = OrderLookupCommand.parse(message);
        if (command.isEmpty()) {
            return replyToNaturalLanguage(identity, shopId, message, selectedOrderNo);
        }

        OrderLookupCommand lookup = command.get();
        return replyWithOrder(identity, shopId, lookup.orderNo());
    }

    private ChatReply replyToNaturalLanguage(
            CustomerIdentity identity, long shopId, String message, String selectedOrderNo) {
        var explicit = ExplicitOrderNumbers.extract(message);
        if (OrderReadQuestion.asksShippingReason(message)) {
            if (explicit.size() > 1) return ChatReply.text("你提到了多笔订单，请先选择要咨询的那一笔。");
            String number = explicit.size() == 1 ? explicit.iterator().next() : selectedOrderNo;
            if (number != null) return replyWithShippingStatus(identity, shopId, number);
            if (OrderReadQuestion.isContextualRead(message)) return replyWithOrderList(identity, shopId, 0L, true);
        }
        if (selectedOrderNo != null && explicit.isEmpty() && OrderReadQuestion.isContextualRead(message)) {
            return replyWithOrder(identity, shopId, selectedOrderNo);
        }
        // 识别器只收到消息，不接收身份、权限或任何数据库中的订单详情。
        OrderIntent intent = orderIntentRecognizer.recognize(message);
        if (intent.type() == OrderIntentType.OTHER) {
            // OTHER is also returned when the optional recognizer is disabled; it is not proof of scope.
            if (GREETING.matcher(message.strip()).matches()) return ChatReply.text(
                    message.contains("谢") ? "不客气，有订单或商品方面的问题可以继续问我。"
                            : message.contains("再见") ? "再见，需要帮助时随时来找我。"
                            : "你好，我可以帮你查询订单、物流、售后，也可以解答商品使用问题。");
            if (OrderReadQuestion.isContextualRead(message)) return replyWithOrderList(identity, shopId, 0L, true);
            if (CustomerQuestionScope.hasBusinessSubject(message)) return ChatReply.text(
                    "我暂时没有查到这项信息。你可以补充商品名称或选择要咨询的订单，也可以转人工客服进一步核实。");
            return ChatReply.outOfScope();
        }
        String orderNo = intent.orderNo() != null ? intent.orderNo() : selectedOrderNo;
        if (orderNo == null) {
            return replyWithOrderList(identity, shopId, 0L, true);
        }
        return replyWithOrder(identity, shopId, orderNo);
    }

    private ChatReply replyWithOrder(CustomerIdentity identity, long shopId, String orderNo) {
        SupportOrder order = orderQueryService.getOwnedOrder(identity, shopId, orderNo);
        return ChatReply.details(describeOrder(order), order);
    }

    private ChatReply replyWithShippingStatus(CustomerIdentity identity, long shopId, String orderNo) {
        SupportOrder order = orderQueryService.getOwnedOrder(identity, shopId, orderNo);
        String status = switch (order.status()) {
            case PAID -> "这笔订单目前仍显示待发货。当前订单信息没有提供未发货的具体原因，可以转人工客服帮你核实发货进度。";
            case CREATED -> "这笔订单目前待支付，尚未进入发货阶段。";
            case CANCELLED -> "这笔订单已经取消，不会继续发货。";
            case SHIPPED -> "这笔订单目前已经发货。你可以继续问我物流进度。";
            case COMPLETED -> "这笔订单已经完成收货。若商品有问题，可以继续咨询售后。";
        };
        return ChatReply.details(status, order);
    }

    private ChatReply replyWithOrderList(CustomerIdentity identity, long shopId, long afterId, boolean prompt) {
        // 每次查询和翻页均在SQL层同时限制当前店铺及认证买家。
        OrderPage page = orderQueryService.listOwnedOrdersInShop(
                identity, shopId, afterId, OrderQueryLimits.DEFAULT_PAGE_SIZE);
        if (page.items().isEmpty()) {
            return ChatReply.selection(
                    afterId == 0L ? "你在当前店铺暂无订单。" : "当前店铺没有更多订单。",
                    page.items(), null);
        }

        StringBuilder reply = new StringBuilder();
        if (prompt) {
            reply.append("请选择当前店铺的一笔订单，或输入订单号。\n");
        }
        reply.append("当前店铺的订单（本批 ").append(page.items().size()).append(" 笔）：");
        for (int index = 0; index < page.items().size(); index++) {
            reply.append('\n').append(index + 1).append(". ")
                    .append(describeOrder(page.items().get(index)));
        }
        if (page.nextAfterId() != null) {
            // 原样使用服务生成的游标，不把它当页码加一，也不使用预读行。
            reply.append("\n还有更多订单，请发送：我的订单 ").append(page.nextAfterId());
        } else {
            reply.append("\n没有更多订单。");
        }
        return ChatReply.selection(reply.toString(), page.items(), page.nextAfterId());
    }

    private void validateSelectedOrderNo(String orderNo) {
        if (orderNo != null && (orderNo.isBlank() || orderNo.length() > OrderQueryLimits.MAX_ORDER_NO_LENGTH
                || orderNo.codePoints().anyMatch(codePoint ->
                        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                                || Character.isISOControl(codePoint)))) {
            throw new InvalidChatCommandException("订单号不能为空、超过64个字符或包含空白、控制字符");
        }
    }

    private String describeOrder(SupportOrder order) {
        return "商铺 " + order.tenantId() + " 的订单 " + order.orderNo()
                + "：" + order.status().getDescription()
                + "，订单金额 " + order.totalAmount().toPlainString() + " " + order.currency() + "。";
    }
}
