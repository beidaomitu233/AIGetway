package com.lightai.admin.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BE-036 导出安全测试：UTF-8 BOM、RFC 4180 转义、公式字符防护。
 */
class CsvStreamWriterTest {

    @Test
    void writesBomAndCrLfRows() throws IOException {
        StringWriter out = new StringWriter();
        CsvStreamWriter.writeBom(out);
        CsvStreamWriter.writeRow(out, List.of("a", "b"));
        CsvStreamWriter.writeRow(out, List.of("1", "2"));
        String csv = out.toString();
        assertThat(csv.charAt(0)).isEqualTo((char) 0xFEFF);
        String withoutBom = csv.substring(1);
        assertThat(withoutBom).isEqualTo("a,b\r\n1,2\r\n");
    }

    @Test
    void quotesFieldsWithCommasQuotesAndNewlines() throws IOException {
        StringWriter out = new StringWriter();
        CsvStreamWriter.writeRow(out, List.of("plain", "has,comma", "has\"quote", "line1\nline2"));
        assertThat(out.toString()).isEqualTo(
                "plain,\"has,comma\",\"has\"\"quote\",\"line1\nline2\"\r\n");
    }

    @Test
    void prefixesFormulaCharactersForTextCellsOnly() {
        assertThat(CsvStreamWriter.text("=cmd")).isEqualTo("'=cmd");
        assertThat(CsvStreamWriter.text("+sum")).isEqualTo("'+sum");
        assertThat(CsvStreamWriter.text("-1")).isEqualTo("'-1");
        assertThat(CsvStreamWriter.text("@x")).isEqualTo("'@x");
        assertThat(CsvStreamWriter.text("normal")).isEqualTo("normal");
        assertThat(CsvStreamWriter.text(null)).isEmpty();
        // 负数金额走原始值列，不加前缀
        assertThat(CsvStreamWriter.escape("-0.5")).isEqualTo("-0.5");
    }

    @Test
    void exportLimitBoundaryIsExclusiveAbove100000() {
        assertThat(TraceExportBoundary.exceedsLimit(99999)).isFalse();
        assertThat(TraceExportBoundary.exceedsLimit(100000)).isFalse();
        assertThat(TraceExportBoundary.exceedsLimit(100001)).isTrue();
    }

    /** 边界规则从服务中提炼为静态判断，便于验证 100000/100001 契约。 */
    static final class TraceExportBoundary {
        private TraceExportBoundary() {
        }

        static boolean exceedsLimit(long total) {
            return total > com.lightai.admin.trace.TraceExportService.MAX_ROWS;
        }
    }
}
