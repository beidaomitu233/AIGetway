package com.lightai.admin.channel;

import com.lightai.client.channel.ChannelModelCatalogItem;
import com.lightai.client.channel.ChannelModelCatalogView;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.protocol.Permissions;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.storage.application.JdbcApplicationModelMappingRepository;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/** 渠道已落库模型的轻量目录；实时供应商适配不可用时明确允许手工输入。 */
public final class ChannelModelCatalogService {
    private final DataSource dataSource;
    private final JdbcApplicationModelMappingRepository repository;

    public ChannelModelCatalogService(DataSource dataSource,
                                      JdbcApplicationModelMappingRepository repository) {
        this.dataSource = dataSource;
        this.repository = repository;
    }

    public ChannelModelCatalogView list(RequestContext context, UUID channelId, String query, String cursor) {
        RequestPermissions.require(context, Permissions.PROVIDER_VIEW);
        int offset = parseCursor(cursor);
        try (Connection connection = dataSource.getConnection()) {
            if (!repository.channelActive(connection, channelId)) {
                throw new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "渠道不存在或未启用");
            }
            List<JdbcApplicationModelMappingRepository.CatalogRow> rows = repository.catalog(
                    connection, List.of(channelId), query, 101, offset);
            boolean hasMore = rows.size() > 100;
            List<ChannelModelCatalogItem> items = rows.stream().limit(100)
                    .map(row -> new ChannelModelCatalogItem(row.id().toString(), row.channelId().toString(),
                            row.modelName(), row.displayName(), row.active())).toList();
            return new ChannelModelCatalogView(items, hasMore ? String.valueOf(offset + 100) : null,
                    items.isEmpty());
        } catch (LightAiException e) { throw e; }
        catch (Exception e) { throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "渠道模型目录当前无法读取"); }
    }

    private static int parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try { int value = Integer.parseInt(cursor); if (value < 0 || value > 100000) throw new NumberFormatException(); return value; }
        catch (NumberFormatException e) { throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "cursor 不合法", "cursor"); }
    }
}
