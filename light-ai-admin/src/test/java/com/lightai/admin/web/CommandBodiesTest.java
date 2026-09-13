package com.lightai.admin.web;

import com.lightai.client.application.ApplicationModelsUpdateCommand;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BE-AUDIT-0913-003：命令解析 400 携带字段级明细，且不回传内部异常细节。 */
class CommandBodiesTest {

    @Test
    void parsesStrictSnakeCaseCommand() {
        ApplicationModelsUpdateCommand command = CommandBodies.parse(
                "{\"virtual_model_ids\":[\"b5d77ce1-f1e3-49dc-b43a-becdf838c952\"],"
                        + "\"constraints\":[],\"reason\":\"probe\",\"application_version\":1}",
                ApplicationModelsUpdateCommand.class);

        assertThat(command.virtualModelIds()).containsExactly("b5d77ce1-f1e3-49dc-b43a-becdf838c952");
        assertThat(command.applicationVersion()).isEqualTo(1L);
    }

    @Test
    void missingPrimitiveReportsFieldNameInsteadOfBodyInvalid() {
        // application_version 为 primitive long，缺失时旧实现只报 body INVALID
        assertThatThrownBy(() -> CommandBodies.parse(
                "{\"virtual_model_ids\":[\"b5d77ce1-f1e3-49dc-b43a-becdf838c952\"]}",
                ApplicationModelsUpdateCommand.class))
                .isInstanceOfSatisfying(LightAiException.class, e -> {
                    assertThat(e.code().name()).isEqualTo("FIELD_VALIDATION_FAILED");
                    assertThat(e.issues()).hasSize(1);
                    FieldIssue issue = e.issues().get(0);
                    assertThat(issue.field()).isEqualTo("application_version");
                    assertThat(issue.message()).doesNotContain("Exception").doesNotContain("jackson");
                });
    }

    @Test
    void unknownFieldReportsFieldName() {
        assertThatThrownBy(() -> CommandBodies.parse(
                "{\"virtual_model_ids\":[],\"application_version\":1,\"no_such_field\":1}",
                ApplicationModelsUpdateCommand.class))
                .isInstanceOfSatisfying(LightAiException.class, e -> {
                    assertThat(e.issues().get(0).field()).isEqualTo("no_such_field");
                    assertThat(e.issues().get(0).code()).isEqualTo("UNKNOWN");
                });
    }

    @Test
    void malformedJsonFallsBackToBodyLevelIssue() {
        assertThatThrownBy(() -> CommandBodies.parse("{not-json", ApplicationModelsUpdateCommand.class))
                .isInstanceOfSatisfying(LightAiException.class, e -> {
                    assertThat(e.issues()).hasSize(1);
                    assertThat(e.issues().get(0).field()).isEqualTo("body");
                });
    }
}
