package com.lightai.client;

/**
 * 流式事件类型（BE-049/052，2.6.10）。
 */
public enum StreamEventType {
    START,
    DELTA,
    USAGE,
    DONE
}