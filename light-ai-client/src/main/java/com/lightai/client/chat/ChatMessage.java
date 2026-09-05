package com.lightai.client.chat;

/** 统一文本 Chat 消息：仅允许 system/user/assistant，content 非空字符串。 */
public record ChatMessage(String role, String content) {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
}
