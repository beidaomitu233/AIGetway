package com.lightai.admin.channel;

import com.lightai.client.channel.ChannelModelCatalogItem;
import com.lightai.client.channel.ChannelModelCatalogView;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.protocol.Permissions;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.runtime.ports.CredentialSecretPort;
import com.lightai.spi.provider.ProviderAdapter;
import com.lightai.spi.provider.ProviderCallContext;
import com.lightai.spi.provider.ProviderChatRequest;
import com.lightai.spi.provider.ProviderConfigView;
import com.lightai.spi.provider.ProviderModelDescriptor;
import com.lightai.storage.application.JdbcApplicationModelMappingRepository;
import com.lightai.storage.channel.ChannelRecord;
import com.lightai.storage.channel.JdbcChannelRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/** 渠道模型目录：优先调用已声明能力的 ProviderAdapter，失败时不伪造成功；不支持实时目录的渠道允许手工输入。 */
public final class ChannelModelCatalogService {
    private final DataSource dataSource;
    private final JdbcApplicationModelMappingRepository mappingRepository;
    private final JdbcChannelRepository channelRepository;
    private final Map<String, ProviderAdapter> adapters;
    private final CredentialSecretPort credentialPort;

    /** 兼容无运行时 Adapter 的嵌入式管理端：只读取已落库模型。 */
    public ChannelModelCatalogService(DataSource dataSource,
                                      JdbcApplicationModelMappingRepository repository) {
        this(dataSource, repository, new JdbcChannelRepository(), List.of(), null);
    }

    public ChannelModelCatalogService(DataSource dataSource,
                                      JdbcApplicationModelMappingRepository repository,
                                      JdbcChannelRepository channelRepository,
                                      List<ProviderAdapter> adapters,
                                      CredentialSecretPort credentialPort) {
        this.dataSource = dataSource;
        this.mappingRepository = repository;
        this.channelRepository = channelRepository == null ? new JdbcChannelRepository() : channelRepository;
        Map<String, ProviderAdapter> byType = new LinkedHashMap<>();
        if (adapters != null) {
            for (ProviderAdapter adapter : adapters) {
                if (adapter != null && adapter.providerType() != null && !adapter.providerType().isBlank()) {
                    byType.putIfAbsent(adapter.providerType().toUpperCase(Locale.ROOT), adapter);
                }
            }
        }
        this.adapters = Map.copyOf(byType);
        this.credentialPort = credentialPort;
    }

    public ChannelModelCatalogView list(RequestContext context, UUID channelId, String query, String cursor) {
        RequestPermissions.require(context, Permissions.PROVIDER_VIEW);
        int offset = parseCursor(cursor);
        try (var connection = dataSource.getConnection()) {
            CatalogPage page = page(connection, List.of(channelId), query, offset, 100);
            return new ChannelModelCatalogView(page.items(), page.nextCursor(), page.manualInputAllowed());
        } catch (LightAiException e) { throw e; }
        catch (Exception e) { throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "渠道模型目录当前无法读取"); }
    }

    /** 应用映射批量草案使用的内部目录读取，不额外要求 provider.view。 */
    public List<ChannelModelCatalogItem> listForApplication(RequestContext context, List<UUID> channelIds,
                                                              String query, int limit) {
        RequestPermissions.require(context, Permissions.APPLICATION_MODEL_MANAGE);
        try (var connection = dataSource.getConnection()) {
            return page(connection, channelIds == null ? List.of() : channelIds, query, 0, limit).items();
        } catch (LightAiException e) { throw e; }
        catch (Exception e) { throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "渠道模型目录当前无法读取"); }
    }

    private CatalogPage page(java.sql.Connection connection, List<UUID> channelIds, String query,
                             int offset, int pageSize) {
        int limit = Math.max(1, Math.min(pageSize, 500));
        List<ChannelModelCatalogItem> items = new ArrayList<>();
        boolean manualInputAllowed = false;
        for (UUID channelId : channelIds) {
            if (channelId == null) continue;
            ChannelRecord channel = channelRepository.findLiveById(connection, channelId).orElse(null);
            if (channel == null || !channel.enabled()) continue;
            ProviderAdapter adapter = adapters.get(channel.providerType() == null
                    ? "" : channel.providerType().toUpperCase(Locale.ROOT));
            boolean supportsRemote = adapter != null && adapter.capabilities().supportsModelList()
                    && credentialPort != null;
            if (supportsRemote) {
                items.addAll(remoteItems(channel, adapter, query));
            } else {
                manualInputAllowed = true;
                items.addAll(mappingRepository.catalog(connection, List.of(channelId), query, 500).stream()
                        .map(row -> new ChannelModelCatalogItem(row.id() == null ? null : row.id().toString(),
                                row.channelId().toString(), row.modelName(), row.displayName(), row.active()))
                        .toList());
            }
        }
        Map<String, ChannelModelCatalogItem> unique = new LinkedHashMap<>();
        for (ChannelModelCatalogItem item : items) {
            String key = item.channelId() + "|" + item.modelName().toLowerCase(Locale.ROOT);
            unique.putIfAbsent(key, item);
        }
        List<ChannelModelCatalogItem> ordered = unique.values().stream()
                .sorted(Comparator.comparing(ChannelModelCatalogItem::modelName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(item -> item.id() == null ? "" : item.id()))
                .toList();
        int from = Math.min(offset, ordered.size());
        int to = Math.min(ordered.size(), from + limit + 1);
        List<ChannelModelCatalogItem> result = ordered.subList(from, Math.min(to, from + limit));
        boolean hasMore = to > from + limit;
        return new CatalogPage(List.copyOf(result), hasMore ? String.valueOf(from + limit) : null,
                manualInputAllowed);
    }

    private List<ChannelModelCatalogItem> remoteItems(ChannelRecord channel, ProviderAdapter adapter, String query) {
        CredentialSecretPort.ResolvedCredential credential = credentialPort.resolve(channel.id().toString(), 0);
        ProviderConfigView config = new ProviderConfigView(channel.providerType(), channel.baseUrl(), channel.proxyUrl(),
                channel.connectTimeoutMs(), channel.readTimeoutMs(), channel.defaultHeaders());
        ProviderChatRequest request = new ProviderChatRequest("", null, List.of(), 1, null, null, List.of(), Map.of());
        Instant deadline = Instant.now().plusMillis(Math.max(1_000, Math.min(60_000, channel.readTimeoutMs())));
        List<ProviderModelDescriptor> descriptors;
        try {
            descriptors = adapter.listModels(new ProviderCallContext(config, request,
                    credential == null ? null : credential.secretHandle(), deadline));
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "实时模型目录当前无法读取");
        }
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        Map<String, ChannelModelCatalogItem> unique = new LinkedHashMap<>();
        if (descriptors != null) {
            for (ProviderModelDescriptor descriptor : descriptors) {
                if (descriptor == null || descriptor.modelId() == null || descriptor.modelId().isBlank()) continue;
                String display = descriptor.displayName() == null || descriptor.displayName().isBlank()
                        ? descriptor.modelId() : descriptor.displayName();
                if (!needle.isEmpty() && !descriptor.modelId().toLowerCase(Locale.ROOT).contains(needle)
                        && !display.toLowerCase(Locale.ROOT).contains(needle)) continue;
                String key = descriptor.modelId().toLowerCase(Locale.ROOT);
                unique.putIfAbsent(key, new ChannelModelCatalogItem(null, channel.id().toString(),
                        descriptor.modelId(), display, true));
            }
        }
        return unique.values().stream().toList();
    }

    private static int parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try { int value = Integer.parseInt(cursor); if (value < 0 || value > 100000) throw new NumberFormatException(); return value; }
        catch (NumberFormatException e) { throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "cursor 不合法", "cursor"); }
    }

    private record CatalogPage(List<ChannelModelCatalogItem> items, String nextCursor, boolean manualInputAllowed) { }
}
