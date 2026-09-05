package com.lightai.storage.schema;

import java.util.List;

/**
 * 结构未就绪：缺表、错误版本或迁移缺失时抛出，
 * 由装配层阻止应用就绪（readiness DOWN），不得静默降级为假就绪。
 */
public class SchemaNotReadyException extends RuntimeException {

    private final List<String> missingTables;

    public SchemaNotReadyException(String message) {
        this(message, List.of());
    }

    public SchemaNotReadyException(String message, List<String> missingTables) {
        super(message);
        this.missingTables = List.copyOf(missingTables);
    }

    public List<String> missingTables() {
        return missingTables;
    }
}
