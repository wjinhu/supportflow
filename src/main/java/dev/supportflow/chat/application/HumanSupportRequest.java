package dev.supportflow.chat.application;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Recognizes a request to show the handoff action; it never opens a support session. */
public final class HumanSupportRequest {
    private static final String HUMAN = "(?:人工(?!智能|费|成本|制作|制造|合成)(?:客服|服务)?|真人(?!秀|照片|图片)(?:客服|服务)?|客服人员)";
    private static final Pattern REQUEST = Pattern.compile(
            "(?:转(?:接)?|接通|找|联系|呼叫|叫|要|想|请|安排|让|有没有).{0,12}" + HUMAN
                    + "|^" + HUMAN + "(?:在吗|来一下|接一下)?[呀啊吧呢\\s？?！!。]*$");
    private static final Pattern DECLINE = Pattern.compile(
            "(?:不用|不必|不想|不要|不需要|无需|别|暂不|先不|不转|取消|停止|退出|结束)"
                    + "(?:再|继续|现在|暂时|先|帮我|给我|为我|麻烦|联系|转接|转|找|呼叫|叫|安排|接通|跟|和|与|\\s){0,5}" + HUMAN);

    private HumanSupportRequest() { }

    public static boolean matches(String input) {
        if (input == null || input.isBlank()) return false;
        String normalized = Normalizer.normalize(input.strip(), Normalizer.Form.NFKC);
        for (String clause : normalized.split("[，,。！!？?；;\\n]")) {
            if (REQUEST.matcher(clause.strip()).find() && !DECLINE.matcher(clause).find()) return true;
        }
        return false;
    }
}
