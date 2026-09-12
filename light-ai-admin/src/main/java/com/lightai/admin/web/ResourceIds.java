package com.lightai.admin.web;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import java.util.UUID;

/** Canonical management resource identifiers; malformed input is a 400, never a database lookup. */
public final class ResourceIds {
    private ResourceIds() { }
    public static UUID parse(String raw) {
        try {
            UUID id = UUID.fromString(raw);
            if (!id.toString().equalsIgnoreCase(raw)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "ID 格式不合法", "id");
        }
    }
}
