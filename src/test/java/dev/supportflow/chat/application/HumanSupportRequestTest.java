package dev.supportflow.chat.application;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class HumanSupportRequestTest {
    @ParameterizedTest
    @ValueSource(strings = {"转人工", "人工", "人工客服", "请转人工客服", "我要找人工", "帮我联系人工客服",
            "有没有人工客服", "帮我转接真人客服", "人工客服在吗", "我不想跟机器人聊了，转人工",
            "转人工处理这笔退款", "这单物流有问题，请找客服人员", "不用查订单了，我要人工", "人工客服来一下",
            "我想和人工聊一下", "不要机器人给我转人工", "取消退款转人工"})
    void recognizesAnExplicitRequest(String message) {
        assertThat(HumanSupportRequest.matches(message)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"不用转人工", "我不需要人工客服", "先别转人工", "取消转人工", "结束人工服务",
            "暂时不想联系人工客服", "不要再转人工", "我想了解人工智能", "我要咨询人工费多少钱", "我想看真人秀",
            "你好", "耳机发货了吗", "申请退款", "", "   "})
    void doesNotOfferHandoffForDeclinedOrUnrelatedRequests(String message) {
        assertThat(HumanSupportRequest.matches(message)).isFalse();
    }
}
