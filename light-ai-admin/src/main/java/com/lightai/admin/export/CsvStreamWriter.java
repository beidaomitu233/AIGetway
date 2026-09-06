package com.lightai.admin.export;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * 安全 CSV 流式写出（BE-036；4.4.5 导出规则）。
 * UTF-8 BOM + RFC 4180 转义；文本字段以 =、+、- 或 @ 开头时在值前增加单引号，
 * 防止电子表格公式执行。逐行写出，不缓存完整文件。
 */
public final class CsvStreamWriter {

    private static final char BOM = (char) 0xFEFF;
    private static final String CRLF = "\r\n";

    private CsvStreamWriter() {
    }

    public static void writeBom(Writer writer) throws IOException {
        writer.write(BOM);
    }

    /** 表头/数据行统一转义；数字与日期列传原始值，文本列先经 text() 防公式。 */
    public static void writeRow(Writer writer, List<String> cells) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(escape(cells.get(i)));
        }
        line.append(CRLF);
        writer.write(line.toString());
    }

    /** 文本单元格：先加公式防护前缀，再走统一转义。 */
    public static String text(Object value) {
        if (value == null) {
            return "";
        }
        String raw = String.valueOf(value);
        if (!raw.isEmpty() && (raw.charAt(0) == '=' || raw.charAt(0) == '+'
                || raw.charAt(0) == '-' || raw.charAt(0) == '@')) {
            return "'" + raw;
        }
        return raw;
    }

    static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean needsQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
