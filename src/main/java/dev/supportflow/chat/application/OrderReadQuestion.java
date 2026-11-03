package dev.supportflow.chat.application;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Recognizes short order follow-ups without treating question words as product names. */
public final class OrderReadQuestion {
    private static final String MODIFIERS = "(?:为什么|为啥|怎么|咋|现在|目前|今天|到底|已经|一直|迟迟|到现在|这么久|什么时候|啥时候|何时|多久|有没有|是否|还|仍然|尚|没有|未|没|不|要|能|才|会){0,12}";
    private static final Pattern CONTEXTUAL = Pattern.compile(
            "^(?:请问|请|麻烦|帮我看看|帮我查一下|帮我看看这笔)?"
                    + "(?:我的订单|我的那笔订单|这笔订单|那笔订单|这个订单|那个订单|订单|这单|那单|它|这个|那个)?"
                    + MODIFIERS + "(?:发货|发出|寄出|出库|发没发|发了|发货了没有|发货了没)"
                    + "(?:了没有|了没|了吗|没有|没|吗|呢|呀|啊|了|\\s|[？?！!。，,])*$");
    private static final Pattern SHIPPING_REASON = Pattern.compile(
            "(?:为什么|为啥|怎么|咋|迟迟|一直|还不|还没|没有|不发货|未发货).{0,80}(?:发货|发出|寄出|出库)"
                    + "|(?:发货|发出|寄出|出库).{0,12}(?:原因|为什么|为啥)");

    private OrderReadQuestion() { }

    public static boolean isContextualRead(String input) {
        return CONTEXTUAL.matcher(normalize(input)).matches();
    }

    public static boolean asksShippingReason(String input) {
        return SHIPPING_REASON.matcher(normalize(input)).find();
    }

    private static String normalize(String input) {
        return Normalizer.normalize(input.strip(), Normalizer.Form.NFKC);
    }
}
