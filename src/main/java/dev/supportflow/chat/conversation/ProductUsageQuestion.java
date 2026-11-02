package dev.supportflow.chat.conversation;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Short product follow-ups only; this neither selects a product nor authorizes an operation. */
final class ProductUsageQuestion {
    private static final String PREFIX = "(?:(?:请问|请|想问|我想问|我想知道|具体|那|那么|它|这个|那个|这款|那款|这件|那件|这个商品|这款商品|刚才那个)[，,\\s]*)*";
    private static final String END = "[吗呢啊呀？?！!。\\s]*$";
    private static final Pattern USAGE = Pattern.compile("^" + PREFIX
            + "(?:(?:如何|怎么|怎样|该怎么|要怎么)?(?:使用|用|连接|配对|充电|开机|关机|清洁|清洗|保养|安装|设置)"
            + "(?:手机|电脑|蓝牙|设备|方法|步骤|教程|说明|一下)?"
            + "|(?:能不能|能否|可以|可不可以)(?:机洗|水洗|手洗)|使用方法|使用说明|操作步骤)" + END);
    private static final Pattern DEVICE = Pattern.compile("^" + PREFIX
            + "(?:(?:我是|我用的|我用的是|用的是|使用的是)?(?:安卓(?:手机)?|Android(?:手机)?|苹果手机|iPhone|Windows(?:电脑)?|电脑)"
            + "|(?:还是)?(?:连不上|连接不上|配对失败|搜不到|找不到设备))" + END,
            Pattern.CASE_INSENSITIVE);

    private ProductUsageQuestion() { }

    static boolean matches(String message) {
        if (message == null || message.length() > 160) return false;
        String text = Normalizer.normalize(message.strip(), Normalizer.Form.NFKC);
        return USAGE.matcher(text).matches() || DEVICE.matcher(text).matches();
    }
}
