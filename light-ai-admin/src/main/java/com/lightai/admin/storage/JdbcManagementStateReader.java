package com.lightai.admin.storage;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.storage.draft.DraftStateRepository;
import com.lightai.storage.runtimeconfig.RuntimeConfigRepository;
import java.sql.Connection;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * JDBC 状态读取：单次自动提交连接内完成只读查询；
 * 存储不可用映射为 CONFIG_DATA_UNAVAILABLE（503），不静默返回假数据。
 */
public final class JdbcManagementStateReader implements ManagementStateReader {

    private final DataSource dataSource;
    private final RuntimeConfigRepository runtimeConfigRepository;
    private final DraftStateRepository draftStateRepository;
    private final String fallbackTimezone;

    public JdbcManagementStateReader(DataSource dataSource, RuntimeConfigRepository runtimeConfigRepository,
                                     DraftStateRepository draftStateRepository, String fallbackTimezone) {
        this.dataSource = dataSource;
        this.runtimeConfigRepository = runtimeConfigRepository;
        this.draftStateRepository = draftStateRepository;
        this.fallbackTimezone = fallbackTimezone;
    }

    @Override
    public ManagementState read() {
        try (Connection connection = dataSource.getConnection()) {
            Optional<com.lightai.storage.runtimeconfig.RuntimeConfigState> runtime =
                    runtimeConfigRepository.findRuntimeState(connection);
            Optional<com.lightai.storage.draft.DraftStateSnapshot> draft =
                    draftStateRepository.find(connection);
            return ManagementStateReader.of(runtime, draft, fallbackTimezone);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "配置状态当前无法读取");
        }
    }
}
