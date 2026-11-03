package dev.supportflow.chat.application.model;

/** 前端根据类型显示文字、订单、售后表单或显式人工客服入口。 */
public enum ChatReplyType {
    TEXT,
    ORDER_DETAILS,
    ORDER_SELECTION,
    AFTER_SALE_FORM,
    HUMAN_SUPPORT
}
