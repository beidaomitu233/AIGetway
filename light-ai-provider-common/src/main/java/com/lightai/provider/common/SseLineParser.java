package com.lightai.provider.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * SSE 行解析（4.7.2.4）：注释、心跳、空行与协议控制帧不产生事件；
 * data: 行按事件聚合输出；UTF-8 多字节序列由底层 Reader 缓冲等待完整字符。
 */
public final class SseLineParser {

    private String pendingData;

    /** 逐行喂入；返回 Some(data) 表示一个完整 data 事件负载。 */
    public Optional<String> feed(String line) {
        if (line == null) {
            return Optional.empty();
        }
        if (line.isEmpty()) {
            // 事件分隔：聚合中的 data 形成一次事件
            if (pendingData != null) {
                String data = pendingData;
                pendingData = null;
                return Optional.of(data);
            }
            return Optional.empty();
        }
        if (line.startsWith(":")) {
            return Optional.empty();
        }
        if (line.startsWith("data:")) {
            String payload = line.substring(5);
            if (payload.startsWith(" ")) {
                payload = payload.substring(1);
            }
            if (pendingData == null) {
                pendingData = payload;
            } else {
                pendingData = pendingData + "\n" + payload;
            }
            return Optional.empty();
        }
        // event:/id:/retry: 及其他字段不参与内容转换
        return Optional.empty();
    }

    /** 流正常关闭时冲出未以空行结尾的最后一个事件。 */
    public Optional<String> flush() {
        if (pendingData != null) {
            String data = pendingData;
            pendingData = null;
            return Optional.of(data);
        }
        return Optional.empty();
    }

    /** 从流读取全部 data 事件负载直至流关闭（测试与缓冲消费用）。 */
    public static java.util.List<String> readAllEvents(InputStream stream) throws IOException {
        java.util.List<String> events = new java.util.ArrayList<>();
        SseLineParser parser = new SseLineParser();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                parser.feed(line).ifPresent(events::add);
            }
        }
        parser.flush().ifPresent(events::add);
        return events;
    }
}
