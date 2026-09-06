package com.lightai.storage.publish;

import java.util.List;

/** JSON 字符串数组的最小解析（jsonb text[] 场景，避免引入额外依赖差异）。 */
final class StringListJson {

    private StringListJson() {
    }

    static List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String body = raw.trim();
        if ("[]".equals(body)) {
            return List.of();
        }
        body = body.substring(1, body.length() - 1);
        if (body.isBlank()) {
            return List.of();
        }
        List<String> values = new java.util.ArrayList<>();
        for (String item : body.split(",")) {
            values.add(item.trim().replaceAll("^\"|\"$", "").replace("\\\"", "\""));
        }
        return List.copyOf(values);
    }

    static String write(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(values.get(i).replace("\"", "\\\"")).append('"');
        }
        return json.append(']').toString();
    }
}
