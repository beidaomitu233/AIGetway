package com.lightai.storage.runtimeconfig;

import java.sql.Connection;
import java.util.Optional;

/** runtime_config 单例只读端口：Bootstrap 等只读场景使用。 */
public interface RuntimeConfigRepository {

    Optional<RuntimeConfigState> findRuntimeState(Connection connection);
}
