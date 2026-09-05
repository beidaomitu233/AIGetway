package com.lightai.client.chat;

/** stream_options：仅 include_usage；非流式请求提供该对象时拒绝。 */
public record StreamOptions(boolean includeUsage) {
}
