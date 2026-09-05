package com.lightai.client.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

    @Test
    void errorCodesAreUnique() {
        Set<String> names = new HashSet<>();
        Arrays.stream(ErrorCode.values()).forEach(code -> assertThat(names.add(code.name()))
                .as("duplicate error code %s", code).isTrue());
    }

    @Test
    void keyContractRowsMatchPlanTable() {
        assertThat(ErrorCode.FIELD_VALIDATION_FAILED.httpStatus()).isEqualTo(400);
        assertThat(ErrorCode.FIELD_VALIDATION_FAILED.retryable()).isFalse();

        assertThat(ErrorCode.ACCESS_TOKEN_INVALID.httpStatus()).isEqualTo(401);
        assertThat(ErrorCode.ACCESS_DENIED.httpStatus()).isEqualTo(403);
        assertThat(ErrorCode.OBJECT_NOT_FOUND.httpStatus()).isEqualTo(404);

        assertThat(ErrorCode.CONFIG_VERSION_CONFLICT.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.CONFIG_VERSION_CONFLICT.retryable()).isFalse();
        assertThat(ErrorCode.CONFIG_PUBLISH_IN_PROGRESS.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.CONFIG_PUBLISH_IN_PROGRESS.retryable()).isTrue();

        assertThat(ErrorCode.CAPACITY_LIMITED.httpStatus()).isEqualTo(429);
        assertThat(ErrorCode.CAPACITY_LIMITED.retryable()).isTrue();

        assertThat(ErrorCode.CLIENT_CANCELLED.httpStatus()).isEqualTo(499);
        assertThat(ErrorCode.CLIENT_CANCELLED.retryable()).isFalse();

        assertThat(ErrorCode.INTERNAL_ERROR.httpStatus()).isEqualTo(500);
        assertThat(ErrorCode.INTERNAL_ERROR.retryable()).isFalse();
    }

    @Test
    void sdkLocalErrorsDoNotFabricateHttpStatus() {
        assertThat(ErrorCode.SERVER_PROTOCOL_ERROR.httpStatus()).isEqualTo(-1);
        assertThat(ErrorCode.CLIENT_CLOSED.httpStatus()).isEqualTo(-1);
    }

    @Test
    void wireTypeFollowsStableEnum() {
        assertThat(ErrorCode.FIELD_VALIDATION_FAILED.type().wireValue()).isEqualTo("invalid_request_error");
        assertThat(ErrorCode.ACCESS_DENIED.type().wireValue()).isEqualTo("permission_error");
        assertThat(ErrorCode.CONFIG_VERSION_CONFLICT.type().wireValue()).isEqualTo("conflict_error");
        assertThat(ErrorCode.CAPACITY_LIMITED.type().wireValue()).isEqualTo("rate_limit_error");
        assertThat(ErrorCode.INTERNAL_ERROR.type().wireValue()).isEqualTo("api_error");
    }
}
