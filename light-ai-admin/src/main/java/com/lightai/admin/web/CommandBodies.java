package com.lightai.admin.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.json.ProtocolJson;
import java.util.List;

/**
 * 管理命令体解析：严格模式（未知字段拒绝、显式 null 拒绝覆盖原始类型），
 * 解析失败统一 FIELD_VALIDATION_FAILED；不支持压缩请求体由容器层拒绝。
 */
public final class CommandBodies {

    private CommandBodies() {
    }

    public static <T> T parse(String body, Class<T> type) {
        if (body == null || body.isBlank()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "请求体不能为空",
                    List.of(new FieldIssue("body", "REQUIRED", "请求体不能为空")));
        }
        try {
            ObjectMapper mapper = ProtocolJson.strictCommands();
            T command = mapper.readValue(body, type);
            if (command == null) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "请求体不能为空",
                        List.of(new FieldIssue("body", "REQUIRED", "请求体不能为空")));
            }
            return command;
        } catch (JsonProcessingException e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "请求体不合法",
                    List.of(new FieldIssue("body", "INVALID", "JSON 格式或字段类型不合法")));
        }
    }

}
