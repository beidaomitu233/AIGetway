package com.lightai.admin.governance;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BE-AUDIT-0913-002：命令引用 ID（scope_id/alias_id）缺失或非法必须 400，不允许 500。 */
class GovernanceAdminServiceUuidValidationTest {

    @Test
    void acceptsValidUuid() {
        UUID id = UUID.randomUUID();
        assertThat(GovernanceAdminService.requireUuid(id.toString(), "scope_id")).isEqualTo(id);
        assertThat(GovernanceAdminService.requireUuid("  " + id + " ", "alias_id")).isEqualTo(id);
    }

    @Test
    void blankIdIsRejectedAsFieldError() {
        assertThatThrownBy(() -> GovernanceAdminService.requireUuid(null, "scope_id"))
                .isInstanceOfSatisfying(LightAiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED);
                    assertThat(e.issues().get(0).field()).isEqualTo("scope_id");
                    assertThat(e.issues().get(0).code()).isEqualTo("REQUIRED");
                });
    }

    @Test
    void nonUuidIsRejectedAsFieldError() {
        assertThatThrownBy(() -> GovernanceAdminService.requireUuid("not-a-uuid", "alias_id"))
                .isInstanceOfSatisfying(LightAiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED);
                    assertThat(e.issues().get(0).field()).isEqualTo("alias_id");
                    assertThat(e.issues().get(0).code()).isEqualTo("INVALID");
                });
    }
}
