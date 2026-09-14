package com.lightai.admin.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.lightai.admin.web.RequestContext;
import com.lightai.client.channel.ChannelModelCatalogItem;
import com.lightai.client.protocol.Roles;
import com.lightai.runtime.ports.CredentialSecretPort;
import com.lightai.spi.auth.AuthContext;
import com.lightai.spi.provider.AdapterCapabilities;
import com.lightai.spi.provider.ProviderAdapter;
import com.lightai.spi.provider.ProviderChatResponse;
import com.lightai.spi.provider.ProviderErrorClassification;
import com.lightai.spi.provider.ProviderFailure;
import com.lightai.spi.provider.ProviderModelDescriptor;
import com.lightai.spi.provider.ProviderCallContext;
import com.lightai.spi.provider.ProviderChatRequest;
import com.lightai.spi.provider.ProviderStreamChunk;
import com.lightai.spi.provider.ProviderConfigView;
import com.lightai.storage.application.JdbcApplicationModelMappingRepository;
import com.lightai.storage.schema.DefaultSchemaMigrator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Flow;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ChannelModelCatalogServiceTest {
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private UUID channelId;
    private ChannelModelCatalogService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:catalog_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUser("sa");
        new DefaultSchemaMigrator(ds).migrate();
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
        channelId = UUID.randomUUID();
        jdbc.update("INSERT INTO channel (id, created_at, updated_at, provider_id, name, base_url) VALUES (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, ?)",
                channelId, UUID.fromString("11111111-1111-4111-8111-111111110001"), "remote-catalog", "https://catalog.example.test");
        service = new ChannelModelCatalogService(dataSource, new JdbcApplicationModelMappingRepository(),
                new com.lightai.storage.channel.JdbcChannelRepository(),
                List.of(new StubAdapter()), CredentialSecretPort.inMemory(Map.of(channelId.toString(), "test-secret")));
    }

    @Test
    void queriesProviderAdapterWithoutPersistingModels() {
        var view = service.list(admin(), channelId, "qwen", "");
        assertThat(view.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isNull();
            assertThat(item.modelName()).isEqualTo("qwen-max");
            assertThat(item.displayName()).isEqualTo("Qwen Max");
        });
        assertThat(view.manualInputAllowed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upstream_model WHERE channel_id = ?", Integer.class, channelId)).isZero();
    }

    @Test
    void applicationBulkDirectoryKeepsTransientModelIdNull() {
        List<ChannelModelCatalogItem> items = service.listForApplication(admin(), List.of(channelId), null, 10);
        assertThat(items).extracting(ChannelModelCatalogItem::modelName).containsExactly("gpt-4o", "qwen-max");
        assertThat(items).allSatisfy(item -> assertThat(item.id()).isNull());
    }

    private static RequestContext admin() {
        return new RequestContext(AuthContext.authenticated("admin", "管理员",
                Set.of(Roles.SYSTEM_ADMIN), List.of(), List.of()), UUID.randomUUID().toString(), "127.0.0.1");
    }

    private static final class StubAdapter implements ProviderAdapter {
        private static final AdapterCapabilities CAPABILITIES = new AdapterCapabilities(
                true, true, true, true, List.of("cl100k_base"), 4, Set.of("stop"), List.of());

        @Override public String providerType() { return "OPENAI"; }
        @Override public AdapterCapabilities capabilities() { return CAPABILITIES; }
        @Override public long estimateTokens(ProviderChatRequest request) { return 1; }
        @Override public ProviderChatResponse chat(ProviderCallContext context) { throw new UnsupportedOperationException(); }
        @Override public Flow.Publisher<ProviderStreamChunk> streamChat(ProviderCallContext context) { throw new UnsupportedOperationException(); }
        @Override public ProviderErrorClassification classifyError(ProviderFailure failure) { throw new UnsupportedOperationException(); }
        @Override public List<ProviderModelDescriptor> listModels(ProviderCallContext context) {
            assertThat(context.config()).isEqualTo(new ProviderConfigView("OPENAI", "https://catalog.example.test", null, 3000, 120000, Map.of()));
            return List.of(new ProviderModelDescriptor("qwen-max", "Qwen Max", null, null, null, true),
                    ProviderModelDescriptor.minimal("gpt-4o"));
        }
    }
}
