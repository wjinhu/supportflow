package dev.supportflow.assistant.knowledge;

import dev.supportflow.assistant.planning.ExplicitOrderNumbers;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Bounded explanatory retrieval, never authorization, eligibility or permission to execute a refund. */
public final class AfterSaleKnowledgePolicy {
    private static final Pattern SUBJECT = Pattern.compile("售后|退款|退货");
    private static final Pattern GUIDE = Pattern.compile(
            "(?:售后|退款|退货).{0,20}(?:说明|介绍|指南|流程|步骤|原因.{0,8}(?:选|填|写)|进度.{0,8}(?:哪里|在哪|哪儿).{0,4}看)"
                    + "|(?:如何|怎么|怎样).{0,12}(?:申请|填写|选择).{0,12}(?:退款|退货|售后)"
                    + "|(?:哪里|在哪|哪儿|如何|怎么).{0,12}(?:查看|查询|看).{0,12}(?:退款|售后).{0,6}进度");
    private static final Pattern FACT_OR_ACTION = Pattern.compile(
            "期限|政策|规则|几天|多少天|多久|哪天|能否|能不能|是否|还能|可不可以|可以退款|到账|订单状态|"
                    + "金额|总价|实付|应付|归属|买家.?ID|客户.?ID|直接|立即|自动|无需|不用|跳过|绕过|批准|拒绝|修改|删除|执行|授权|密钥|密码",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CREDENTIAL_OR_AUTHORITY = Pattern.compile(
            "api.?key|系统提示|system.?prompt|system\\s*:|assistant\\s*:|password\\s*[:=]|密码\\s*[:：=是为]|"
                    + "密码\\s*[A-Za-z0-9]|(?:授予|提升|扩大|取消|绕过).{0,8}权限|(?:全局|管理员|超级用户)权限",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE_CLAIM = Pattern.compile(
            "(?:无需|不用|不需|不必|免|跳过|绕过).{0,8}(?:审核|审批|授权)"
                    + "|(?:直接|自动|立即|马上)(?:全额)?(?:退款|批准|执行|到账)"
                    + "|(?:保证|承诺).{0,8}(?:退款成功|到账)|无条件.{0,6}(?:退款|通过)"
                    + "|退款已(?:执行|成功|到账)|(?:这单|这笔订单|该订单|本单|你的订单|你的退款申请).{0,6}(?:已经|已)(?:批准|退款成功|到账|通过审核)"
                    + "|(?:退款|退款申请|退款资金|这笔退款|该笔退款|你的退款)(?:已经|已)(?:执行|成功|到账|批准|通过)"
                    + "|(?:skip|bypass).{0,12}(?:approval|review)|(?:automatic|instant).{0,8}refund",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION = Pattern.compile(
            "([0-9一二三四五六七八九十百两零〇]{1,7})\\s{0,8}(?:个)?\\s{0,8}(工作日|自然日|小时|分钟|天|日|个月|月|星期|周|年|days?|hours?)",
            Pattern.CASE_INSENSITIVE);

    private AfterSaleKnowledgePolicy() { }

    public static boolean isGuideQuestion(String message) {
        if (message == null || message.isBlank()) return false;
        String text = normalize(message);
        return !text.startsWith("申请售后 ") && !text.equals("申请售后")
                && ExplicitOrderNumbers.extract(text).isEmpty()
                && SUBJECT.matcher(text).find() && GUIDE.matcher(text).find()
                && !FACT_OR_ACTION.matcher(text).find();
    }

    static boolean allowsSource(String text, int windowDays) {
        text = normalize(text);
        if (!SUBJECT.matcher(text).find() || CREDENTIAL_OR_AUTHORITY.matcher(text).find()
                || UNSAFE_CLAIM.matcher(text).find()) return false;
        var durations = DURATION.matcher(text);
        while (durations.find()) {
            String unit = durations.group(2).toLowerCase(java.util.Locale.ROOT);
            if (!java.util.Set.of("天", "自然日", "day", "days").contains(unit)
                    || number(durations.group(1)) != windowDays) return false;
        }
        return true;
    }

    /** Select whole, explicitly headed sections, never cut a condition in the middle of a sentence. */
    static String explanatoryText(String question, String content) {
        String section = question.contains("原因") ? "原因"
                : question.contains("进度") ? "进度"
                : question.contains("申请") || question.contains("提交") ? "提交" : null;
        if (section == null) return content;
        var blocks = content.split("(?m)^##\\s+");
        var selected = new java.util.ArrayList<String>();
        for (int i = 1; i < blocks.length; i++) {
            int newline = blocks[i].indexOf('\n');
            if (newline > 0 && blocks[i].substring(0, newline).contains(section)) {
                selected.add(blocks[i].substring(newline + 1).strip());
            }
        }
        if (selected.isEmpty()) return content;
        String text = String.join("\n\n", selected);
        if (section.equals("进度") && !text.contains("我的售后")) {
            var location = Pattern.compile("[^。！？\\n]*我的售后[^。！？\\n]*[。！？]?").matcher(content);
            if (location.find()) text = location.group().strip() + "\n\n" + text;
        }
        return text;
    }

    private static int number(String input) {
        if (input.matches("[0-9]{1,3}")) return Integer.parseInt(input);
        if (input.length() > 7) return -1;
        int total = 0, digit = 0;
        for (char value : input.toCharArray()) {
            if (value == '十' || value == '百') {
                total += (digit == 0 ? 1 : digit) * (value == '十' ? 10 : 100);
                digit = 0;
            } else {
                digit = "零一二三四五六七八九".indexOf(value);
                if (value == '两') digit = 2;
                if (value == '〇') digit = 0;
                if (digit < 0) return -1;
            }
        }
        return total + digit;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text.strip(), Normalizer.Form.NFKC);
    }
}
