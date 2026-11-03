package dev.supportflow.chat.application;

import dev.supportflow.assistant.planning.ExplicitOrderNumbers;

import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Pattern;

/** A bounded account read; scope and identity never come from model-produced arguments. */
public final class SpendingQuestion {
    private static final Pattern SPENDING = Pattern.compile("消费|花费|花了|支出|开销");
    private static final Pattern PAYMENT = Pattern.compile("消费|花费|花了|支出|开销|付款|支付|实付|订单");
    private static final Pattern TOTAL = Pattern.compile("总金额|总额|合计|一共|总共|汇总|累计|统计");
    private static final Pattern AMOUNT = Pattern.compile("多少|金额|钱|总|合计|汇总|统计");
    private static final Pattern SINGLE_ORDER = Pattern.compile("这笔|那笔|这单|那单|该订单|此订单|这个订单|那个订单");
    private static final Pattern WRITE_OR_REFUND = Pattern.compile(
            "退款|退货|售后|申请|提交|取消|修改|删除|审批|忽略|绕过|system\\s*:|ignore|refund|approve|delete|update",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OTHER_OWNER = Pattern.compile("别人|他人|其他用户|其他买家|用户\\s*\\d|买家\\s*\\d|buyerId|customerId", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHOP_SCOPE = Pattern.compile("本店|这家店|当前店铺|当前商铺|这个店|店铺\\s*\\d|商铺\\s*\\d");
    private static final Pattern UNSUPPORTED_TIME = Pattern.compile("本月|上个月|上一个月|上月|这个月|今年|去年|本周|上周|昨天|今天|本日|全部历史|有史以来|\\d{4}[-年/]");
    private static final Pattern RANGE = Pattern.compile("(?:\\d+|[一二三四五六七八九十两百]+)(?:个)?(?:天|个月|月|年|周|星期)");
    private static final Pattern MULTIPLE_ORDERS = Pattern.compile("所有|全部|最近|近期|近\\d|过去|累计|消费|花费|支出");

    private SpendingQuestion() { }

    public static boolean matches(String input) {
        String text = Normalizer.normalize(input.strip(), Normalizer.Form.NFKC);
        return !WRITE_OR_REFUND.matcher(text).find() && !SINGLE_ORDER.matcher(text).find()
                && ExplicitOrderNumbers.extract(text).isEmpty()
                && (SPENDING.matcher(text).find() && AMOUNT.matcher(text).find()
                    || PAYMENT.matcher(text).find() && TOTAL.matcher(text).find() && MULTIPLE_ORDERS.matcher(text).find());
    }

    public static Optional<String> clarification(String input) {
        String text = Normalizer.normalize(input.strip(), Normalizer.Form.NFKC);
        if (OTHER_OWNER.matcher(text).find()) return Optional.of("我只能查询你本人在全部店铺的消费记录，不能查询其他用户。");
        if (SHOP_SCOPE.matcher(text).find()) return Optional.of("目前支持你本人在全部店铺近30天的已付款订单金额汇总，暂不按单个店铺统计。");
        var range = RANGE.matcher(text);
        boolean unsupportedRange = false;
        while (range.find()) unsupportedRange |= !java.util.Set.of("30天", "三十天", "一个月", "1个月").contains(range.group());
        if (unsupportedRange || UNSUPPORTED_TIME.matcher(text).find()) {
            return Optional.of("目前可以统计近30天下单的已付款订单金额；其他时间范围暂不支持。你可以直接问“最近消费了多少钱”。");
        }
        return Optional.empty();
    }
}
