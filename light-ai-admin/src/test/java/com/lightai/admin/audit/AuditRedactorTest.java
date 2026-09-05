package com.lightai.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.lightai.client.changes.FieldChange;
import java.util.List;
import org.junit.jupiter.api.Test;

/** BE-005 审计脱敏：敏感字段只保留 field_path 与 changed，值与引用不落审计。 */
class AuditRedactorTest {

    @Test
    void sensitivePathsLoseValuesButKeepChangedFlag() {
        List<FieldChange> redacted = AuditRedactor.redact(List.of(
                FieldChange.changed("secret_ref", "vault://old", "vault://new"),
                FieldChange.changed("headers.authorization", "Bearer x", "Bearer y"),
                FieldChange.changed("display_name", "旧", "新")));

        assertThat(redacted).satisfiesExactly(
                change -> {
                    assertThat(change.fieldPath()).isEqualTo("secret_ref");
                    assertThat(change.before()).isNull();
                    assertThat(change.after()).isNull();
                    assertThat(change.changed()).isTrue();
                },
                change -> {
                    assertThat(change.fieldPath()).isEqualTo("headers.authorization");
                    assertThat(change.before()).isNull();
                    assertThat(change.after()).isNull();
                },
                change -> {
                    assertThat(change.fieldPath()).isEqualTo("display_name");
                    assertThat(change.before()).isEqualTo("旧");
                    assertThat(change.after()).isEqualTo("新");
                });
    }

    @Test
    void sensitiveDetectionIsCaseInsensitiveAndCoversSubPaths() {
        assertThat(AuditRedactor.isSensitive("SECRET_REF")).isTrue();
        assertThat(AuditRedactor.isSensitive("connection.api_key")).isTrue();
        assertThat(AuditRedactor.isSensitive("rotated_token_at")).isTrue();
        assertThat(AuditRedactor.isSensitive("base_url")).isFalse();
        assertThat(AuditRedactor.isSensitive(null)).isTrue();
    }

    @Test
    void nullAndEmptyInputsProduceEmptyList() {
        assertThat(AuditRedactor.redact(null)).isEmpty();
        assertThat(AuditRedactor.redact(List.of())).isEmpty();
    }
}
