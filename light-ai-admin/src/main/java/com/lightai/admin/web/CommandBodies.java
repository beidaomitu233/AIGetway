package com.lightai.admin.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.json.ProtocolJson;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理命令体解析：严格模式（未知字段拒绝、显式 null 拒绝覆盖原始类型），
 * 解析失败统一 FIELD_VALIDATION_FAILED；不支持压缩请求体由容器层拒绝。
 * 错误明细只携带客户端字段路径与通用原因，不回传内部异常细节。
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
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "请求体不合法", fieldIssues(e));
        }
    }

    /** 从 Jackson 异常提取字段路径与通用原因（BE-AUDIT-0913-003），路径为空时退回 body 级明细。 */
    private static List<FieldIssue> fieldIssues(JsonProcessingException e) {
        String path = fieldPath(e);
        if (e instanceof UnrecognizedPropertyException unknown) {
            String field = path.isEmpty() ? unknown.getPropertyName() : path;
            return List.of(new FieldIssue(field, "UNKNOWN", "未知字段，已按严格模式拒绝"));
        }
        if (!path.isEmpty()) {
            boolean missingPrimitive = e instanceof MismatchedInputException mismatched
                    && mismatched.getTargetType() != null && mismatched.getTargetType().isPrimitive();
            String reason = missingPrimitive || e.getCause() instanceof IllegalArgumentException
                    ? "字段缺失或类型不合法" : "字段类型不合法";
            return List.of(new FieldIssue(path, "INVALID", reason));
        }
        return List.of(new FieldIssue("body", "INVALID", "JSON 格式或字段类型不合法"));
    }

    private static String fieldPath(JsonProcessingException e) {
        if (!(e instanceof JsonMappingException mapping)) {
            return "";
        }
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : mapping.getPath()) {
            if (reference.getFieldName() != null) {
                path.append(path.length() == 0 ? "" : ".").append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                path.append("[").append(reference.getIndex()).append("]");
            }
        }
        return path.toString();
    }
}
