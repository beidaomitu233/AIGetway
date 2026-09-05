package com.lightai.admin.audit;

import com.lightai.client.changes.FieldChange;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 审计与草稿差异脱敏（BE-005）：写入前剥离敏感字段值。
 * 敏感字段只保留 field_path 与 changed=true，不落值、不落引用；
 * 完整 secret_ref、Token、密钥值永不进入审计。
 */
public final class AuditRedactor {

    private static final List<String> SENSITIVE_KEYWORDS = List.of(
            "secret", "token", "password", "authorization", "api_key", "apikey", "credential_key");

    private AuditRedactor() {
    }

    /** 判定字段路径是否敏感（大小写不敏感，含子路径）。 */
    public static boolean isSensitive(String fieldPath) {
        if (fieldPath == null) {
            return true;
        }
        String path = fieldPath.toLowerCase(Locale.ROOT);
        return SENSITIVE_KEYWORDS.stream().anyMatch(path::contains);
    }

    public static List<FieldChange> redact(List<FieldChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return List.of();
        }
        List<FieldChange> redacted = new ArrayList<>(changes.size());
        for (FieldChange change : changes) {
            if (change == null) {
                continue;
            }
            if (isSensitive(change.fieldPath())) {
                redacted.add(FieldChange.sensitiveChanged(change.fieldPath()));
            } else {
                redacted.add(change);
            }
        }
        return List.copyOf(redacted);
    }
}
