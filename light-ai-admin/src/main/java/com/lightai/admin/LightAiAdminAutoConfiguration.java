package com.lightai.admin;

import com.lightai.admin.bootstrap.BootstrapController;
import com.lightai.admin.bootstrap.BootstrapService;
import com.lightai.admin.draft.DraftWriteService;
import com.lightai.admin.storage.JdbcManagementStateReader;
import com.lightai.admin.storage.ManagementStateReader;
import com.lightai.admin.web.AdminAuthInterceptor;
import com.lightai.admin.web.CsrfTokenFilter;
import com.lightai.admin.web.CsrfTokenService;
import com.lightai.admin.web.RequestIdFilter;
import com.lightai.spi.adapter.AdapterMetadataSource;
import com.lightai.spi.auth.AuthContextProvider;
import com.lightai.spi.auth.AuthContextProviders;
import com.lightai.storage.audit.AuditRepository;
import com.lightai.storage.audit.JdbcAuditRepository;
import com.lightai.storage.draft.DraftChangeRepository;
import com.lightai.storage.draft.DraftStateRepository;
import com.lightai.storage.draft.JdbcDraftChangeRepository;
import com.lightai.storage.draft.JdbcDraftStateRepository;
import com.lightai.storage.runtimeconfig.JdbcRuntimeConfigRepository;
import com.lightai.storage.runtimeconfig.RuntimeConfigRepository;
import com.lightai.storage.schema.SchemaGuard;
import com.lightai.storage.schema.SchemaMode;
import com.lightai.storage.schema.SchemaMigrator;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 管理端自动装配：身份拦截、bootstrap、审计与草稿写事务按模式条件装配，
 * 允许宿主以同型 Bean 覆盖默认实现（PROJECT_DOCUMENT 第 2 节）。
 * 无 DataSource 时仅提供无存储默认（草稿状态为零值），不假装已连接数据库。
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnProperty(name = "light-ai.admin.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({AdminProperties.class, StorageProperties.class})
public class LightAiAdminAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AuthContextProvider lightAiAuthContextProvider() {
        // Embedded 无宿主认证适配时默认拒绝匿名，不提供默认管理员
        return AuthContextProviders.denyAll();
    }

    @Bean
    @ConditionalOnMissingBean
    public Clock lightAiClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean
    public CsrfTokenService lightAiCsrfTokenService() {
        return new CsrfTokenService();
    }

    @Bean
    public BootstrapService lightAiBootstrapService(AdminProperties properties,
                                                    ObjectProvider<ManagementStateReader> stateReader,
                                                    ObjectProvider<AdapterMetadataSource> adapterMetadataSource) {
        ManagementStateReader reader = stateReader.getIfAvailable(
                () -> () -> ManagementStateReader.ManagementState.defaults(properties.getTimezone()));
        return new BootstrapService(properties, reader, adapterMetadataSource.getIfAvailable());
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public BootstrapController lightAiBootstrapController(BootstrapService bootstrapService,
                                                          CsrfTokenService csrfTokenService,
                                                          AdminProperties properties) {
        return new BootstrapController(bootstrapService, csrfTokenService, properties.isCsrfEnabled());
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public AdminAuthInterceptor lightAiAdminAuthInterceptor(AuthContextProvider authContextProvider) {
        return new AdminAuthInterceptor(authContextProvider);
    }

    /** 管理面统一错误映射：未注册为 Bean 时宿主应用收到的是容器原始错误页。 */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean
    public com.lightai.admin.web.AdminErrorHandler lightAiAdminErrorHandler() {
        return new com.lightai.admin.web.AdminErrorHandler();
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public WebMvcConfigurer lightAiAdminWebMvcConfigurer(AdminAuthInterceptor adminAuthInterceptor) {
        return new AdminWebMvcConfigurer(adminAuthInterceptor);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public FilterRegistrationBean<RequestIdFilter> lightAiRequestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration =
                new FilterRegistrationBean<>(new RequestIdFilter());
        registration.addUrlPatterns("/admin/*", "/v1/*", "/internal/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = "light-ai.admin.csrf-enabled", havingValue = "true")
    public FilterRegistrationBean<CsrfTokenFilter> lightAiCsrfTokenFilter(CsrfTokenService csrfTokenService) {
        FilterRegistrationBean<CsrfTokenFilter> registration =
                new FilterRegistrationBean<>(new CsrfTokenFilter(csrfTokenService));
        registration.addUrlPatterns("/admin/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    public SmartInitializingSingleton lightAiAdminConfigurationValidator(AdminProperties properties) {
        return () -> {
            try {
                com.lightai.client.protocol.RuntimeMode.valueOf(properties.getRuntimeMode());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "light-ai.admin.runtime-mode 非法：" + properties.getRuntimeMode()
                                + "，允许值 LOCAL_RUNTIME/EMBEDDED/STANDALONE_SERVER");
            }
        };
    }

    /** /admin/** 统一鉴权入口注册。 */
    record AdminWebMvcConfigurer(AdminAuthInterceptor adminAuthInterceptor) implements WebMvcConfigurer {

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            // /admin/** 全量入口鉴权；页面在 /ui/**，API 不会被 SPA fallback 吞掉
            registry.addInterceptor(adminAuthInterceptor).addPathPatterns("/admin/**");
        }
    }

    /**
     * 存储装配：仅当宿主提供 DataSource 且未显式关闭时启用。
     * 缺 PlatformTransactionManager 时补 DataSource 事务管理器（宿主可覆盖）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(name = "light-ai.storage.enabled", havingValue = "true", matchIfMissing = true)
    public static class StorageConfiguration {

        @Bean
        @ConditionalOnMissingBean(PlatformTransactionManager.class)
        public PlatformTransactionManager lightAiTransactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        @ConditionalOnMissingBean
        public JdbcDraftStateRepository lightAiDraftStateRepository(StorageProperties properties) {
            return new JdbcDraftStateRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public JdbcDraftChangeRepository lightAiDraftChangeRepository(StorageProperties properties) {
            return new JdbcDraftChangeRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public AuditRepository lightAiAuditRepository(StorageProperties properties) {
            return new JdbcAuditRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public RuntimeConfigRepository lightAiRuntimeConfigRepository(StorageProperties properties) {
            return new JdbcRuntimeConfigRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.security.RuntimeConfigAdminRepository lightAiRuntimeConfigAdminRepository(
                StorageProperties properties) {
            return new com.lightai.storage.security.JdbcRuntimeConfigAdminRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.admin.runtimeconfig.RuntimeConfigAdminService lightAiRuntimeConfigAdminService(
                DataSource dataSource, PlatformTransactionManager transactionManager,
                com.lightai.storage.security.RuntimeConfigAdminRepository repository,
                com.lightai.admin.audit.AuditService auditService,
                DraftStateRepository draftStateRepository,
                Clock clock, AdminProperties properties) {
            java.util.function.Supplier<Long> revision = () -> {
                try (java.sql.Connection connection = dataSource.getConnection()) {
                    return draftStateRepository.find(connection).map(com.lightai.storage.draft.DraftStateSnapshot::draftRevision)
                            .orElse(0L);
                } catch (Exception e) {
                    throw new IllegalStateException("草稿修订读取失败", e);
                }
            };
            return new com.lightai.admin.runtimeconfig.RuntimeConfigAdminService(dataSource, transactionManager,
                    repository, auditService, clock, revision, properties.getRuntimeMode());
        }

        @Bean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        public com.lightai.admin.runtimeconfig.RuntimeConfigController lightAiRuntimeConfigController(
                com.lightai.admin.runtimeconfig.RuntimeConfigAdminService service) {
            return new com.lightai.admin.runtimeconfig.RuntimeConfigController(service);
        }

        @Bean
        public ManagementStateReader lightAiManagementStateReader(
                DataSource dataSource, RuntimeConfigRepository runtimeConfigRepository,
                DraftStateRepository draftStateRepository, AdminProperties properties) {
            return new JdbcManagementStateReader(dataSource, runtimeConfigRepository,
                    draftStateRepository, properties.getTimezone());
        }

        @Bean
        public com.lightai.admin.audit.AuditService lightAiAuditService(
                AuditRepository auditRepository, DataSource dataSource,
                PlatformTransactionManager transactionManager,
                ObjectProvider<com.lightai.admin.audit.AuditFailureListener> failureListener) {
            return new com.lightai.admin.audit.AuditService(auditRepository, dataSource,
                    transactionManager, failureListener.getIfAvailable());
        }

        @Bean
        public com.lightai.admin.draft.DraftWriteService lightAiDraftWriteService(
                DataSource dataSource, PlatformTransactionManager transactionManager,
                DraftStateRepository draftStateRepository, DraftChangeRepository draftChangeRepository,
                com.lightai.admin.audit.AuditService auditService) {
            return new com.lightai.admin.draft.DraftWriteService(dataSource, transactionManager,
                    draftStateRepository, draftChangeRepository, auditService);
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.channel.JdbcChannelRepository lightAiProviderRepository(
                StorageProperties properties) {
            return new com.lightai.storage.channel.JdbcChannelRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.reference.JdbcConfigReferenceRepository lightAiConfigReferenceRepository(
                StorageProperties properties) {
            return new com.lightai.storage.reference.JdbcConfigReferenceRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.runtime.JdbcObjectRuntimeStateRepository lightAiObjectRuntimeStateRepository(
                StorageProperties properties) {
            return new com.lightai.storage.runtime.JdbcObjectRuntimeStateRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.runtime.JdbcRuntimeStateWriter lightAiRuntimeStateWriter(
                StorageProperties properties) {
            return new com.lightai.storage.runtime.JdbcRuntimeStateWriter(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.check.JdbcChannelCheckRecordRepository lightAiChannelCheckRecordRepository(
                StorageProperties properties) {
            return new com.lightai.storage.check.JdbcChannelCheckRecordRepository(properties.getSchemaName());
        }

        @Bean
        public com.lightai.admin.channel.ProviderTypeRegistry lightAiProviderTypeRegistry(
                ObjectProvider<AdapterMetadataSource> adapterMetadataSource) {
            return new com.lightai.admin.channel.ProviderTypeRegistry(adapterMetadataSource.getIfAvailable());
        }

        @Bean
        public com.lightai.admin.channel.TargetUrlPolicy lightAiTargetUrlPolicy(
                AdminProperties properties) {
            return new com.lightai.admin.channel.TargetUrlPolicy(properties.isAllowedProviderInternalNetworks());
        }

        @Bean
        public com.lightai.admin.impact.ImpactService lightAiImpactService(
                com.lightai.storage.reference.JdbcConfigReferenceRepository referenceRepository,
                StorageProperties properties) {
            return new com.lightai.admin.impact.ImpactService(referenceRepository, properties.getSchemaName());
        }

        @Bean
        public com.lightai.admin.channel.ChannelService lightAiChannelService(
                DataSource dataSource,
                com.lightai.storage.channel.JdbcChannelRepository providerRepository,
                com.lightai.storage.reference.JdbcConfigReferenceRepository referenceRepository,
                com.lightai.storage.runtime.JdbcObjectRuntimeStateRepository runtimeStateRepository,
                com.lightai.storage.runtime.JdbcRuntimeStateWriter runtimeStateWriter,
                com.lightai.storage.check.JdbcChannelCheckRecordRepository checkRecordRepository,
                DraftChangeRepository draftChangeRepository,
                com.lightai.admin.draft.DraftWriteService draftWriteService,
                com.lightai.admin.impact.ImpactService impactService,
                com.lightai.admin.channel.ProviderTypeRegistry typeRegistry,
                com.lightai.admin.channel.TargetUrlPolicy targetUrlPolicy,
                Clock clock, AdminProperties properties) {
            return new com.lightai.admin.channel.ChannelService(dataSource, providerRepository,
                    referenceRepository, runtimeStateRepository, runtimeStateWriter,
                    checkRecordRepository, draftChangeRepository,
                    draftWriteService, impactService, typeRegistry, targetUrlPolicy,
                    new com.lightai.admin.query.PageResultFactory(clock), properties.getRuntimeMode());
        }

        @Bean
        public com.lightai.admin.check.ChannelCheckService lightAiChannelCheckService(
                DataSource dataSource,
                com.lightai.storage.channel.JdbcChannelRepository providerRepository,
                com.lightai.storage.reference.JdbcConfigReferenceRepository referenceRepository,
                com.lightai.storage.check.JdbcChannelCheckRecordRepository checkRecordRepository,
                com.lightai.storage.runtime.JdbcRuntimeStateWriter runtimeStateWriter,
                org.springframework.beans.factory.ObjectProvider<com.lightai.spi.check.ProviderCheckExecutor> executors,
                AdminProperties properties) {
            return new com.lightai.admin.check.ChannelCheckService(dataSource, providerRepository,
                    referenceRepository, checkRecordRepository, runtimeStateWriter,
                    executors.orderedStream().toList(), properties.getRuntimeMode());
        }

        @Bean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        public com.lightai.admin.channel.ChannelController lightAiChannelController(
                com.lightai.admin.channel.ChannelService providerService,
                com.lightai.admin.check.ChannelCheckService providerCheckService) {
            return new com.lightai.admin.channel.ChannelController(providerService, providerCheckService);
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.channel.JdbcChannelCredentialRepository lightAiCredentialRepository(
                StorageProperties properties) {
            return new com.lightai.storage.channel.JdbcChannelCredentialRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.upstream.JdbcUpstreamModelRepository lightAiProviderModelRepository(
                StorageProperties properties) {
            return new com.lightai.storage.upstream.JdbcUpstreamModelRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.alias.JdbcAliasRepository lightAiAliasRepository(
                StorageProperties properties) {
            return new com.lightai.storage.alias.JdbcAliasRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.alias.JdbcCandidateRepository lightAiCandidateRepository(
                StorageProperties properties) {
            return new com.lightai.storage.alias.JdbcCandidateRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.batch.JdbcBatchCheckRepository lightAiBatchCheckRepository(
                StorageProperties properties) {
            return new com.lightai.storage.batch.JdbcBatchCheckRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.spi.secret.SecretCipher lightAiSecretCipher(AdminProperties properties)
                throws Exception {
            String base64 = properties.getSecretMasterKeyBase64();
            if (base64 == null || base64.isBlank()) {
                throw new IllegalStateException(
                        "缺少 light-ai.admin.secret-master-key-base64，INLINE 秘密无法安全加密，拒绝启动");
            }
            return new com.lightai.storage.crypto.AesGcmSecretCipher(base64,
                    properties.getSecretMasterKeyId());
        }

        @Bean
        public com.lightai.admin.channel.ChannelCredentialService lightAiChannelCredentialService(
                DataSource dataSource,
                com.lightai.storage.channel.JdbcChannelCredentialRepository credentialRepository,
                com.lightai.storage.channel.JdbcChannelRepository channelRepository,
                com.lightai.storage.runtime.JdbcObjectRuntimeStateRepository runtimeStateRepository,
                DraftChangeRepository draftChangeRepository,
                com.lightai.admin.draft.DraftWriteService draftWriteService,
                com.lightai.spi.secret.SecretCipher secretCipher,
                Clock clock, AdminProperties properties,
                com.lightai.storage.reference.JdbcConfigReferenceRepository referenceRepository,
                com.lightai.storage.check.JdbcChannelCheckRecordRepository checkRecordRepository,
                com.lightai.storage.runtime.JdbcRuntimeStateWriter runtimeStateWriter,
                org.springframework.beans.factory.ObjectProvider<com.lightai.spi.check.ProviderCheckExecutor> executors) {
            return new com.lightai.admin.channel.ChannelCredentialService(dataSource, credentialRepository,
                    channelRepository, runtimeStateRepository,
                    draftChangeRepository, draftWriteService, secretCipher,
                    new com.lightai.admin.query.PageResultFactory(clock), properties.getRuntimeMode(),
                    referenceRepository, checkRecordRepository, runtimeStateWriter,
                    executors.orderedStream().toList());
        }

        @Bean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        public com.lightai.admin.channel.ChannelCredentialController lightAiChannelCredentialController(
                com.lightai.admin.channel.ChannelCredentialService credentialService) {
            return new com.lightai.admin.channel.ChannelCredentialController(credentialService);
        }

        @Bean
        public com.lightai.admin.upstream.UpstreamModelService lightAiUpstreamModelService(
                DataSource dataSource,
                com.lightai.storage.upstream.JdbcUpstreamModelRepository modelRepository,
                com.lightai.storage.channel.JdbcChannelRepository providerRepository,
                com.lightai.storage.alias.JdbcCandidateRepository candidateRepository,
                com.lightai.storage.runtime.JdbcObjectRuntimeStateRepository runtimeStateRepository,
                DraftChangeRepository draftChangeRepository,
                com.lightai.admin.draft.DraftWriteService draftWriteService,
                com.lightai.admin.impact.ImpactService impactService,
                Clock clock, AdminProperties properties,
                com.lightai.storage.check.JdbcChannelCheckRecordRepository checkRecordRepository,
                com.lightai.storage.runtime.JdbcRuntimeStateWriter runtimeStateWriter,
                org.springframework.beans.factory.ObjectProvider<com.lightai.spi.check.ProviderCheckExecutor> executors) {
            return new com.lightai.admin.upstream.UpstreamModelService(dataSource, modelRepository,
                    providerRepository, candidateRepository, runtimeStateRepository,
                    draftChangeRepository, draftWriteService, impactService,
                    new com.lightai.admin.query.PageResultFactory(clock), properties.getRuntimeMode(),
                    checkRecordRepository, runtimeStateWriter, executors.orderedStream().toList());
        }

        @Bean
        public com.lightai.admin.upstream.ModelImportService lightAiModelImportService(
                DataSource dataSource,
                com.lightai.storage.channel.JdbcChannelRepository providerRepository,
                com.lightai.storage.upstream.JdbcUpstreamModelRepository modelRepository,
                com.lightai.admin.draft.DraftWriteService draftWriteService,
                com.lightai.storage.batch.JdbcBatchCheckRepository batchCheckRepository,
                org.springframework.beans.factory.ObjectProvider<com.lightai.spi.check.ProviderCheckExecutor> executors,
                AdminProperties properties) {
            return new com.lightai.admin.upstream.ModelImportService(dataSource, providerRepository,
                    modelRepository, draftWriteService, batchCheckRepository,
                    executors.orderedStream().toList(), properties.getRuntimeMode());
        }

        @Bean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        public com.lightai.admin.upstream.UpstreamModelController lightAiUpstreamModelController(
                com.lightai.admin.upstream.UpstreamModelService modelService,
                com.lightai.admin.upstream.ModelImportService importService) {
            return new com.lightai.admin.upstream.UpstreamModelController(modelService, importService);
        }

        @Bean
        public com.lightai.admin.alias.ModelAliasService lightAiModelAliasService(
                DataSource dataSource,
                com.lightai.storage.alias.JdbcAliasRepository aliasRepository,
                com.lightai.storage.alias.JdbcCandidateRepository candidateRepository,
                DraftChangeRepository draftChangeRepository,
                com.lightai.admin.draft.DraftWriteService draftWriteService,
                com.lightai.admin.impact.ImpactService impactService,
                Clock clock, AdminProperties properties) {
            return new com.lightai.admin.alias.ModelAliasService(dataSource, aliasRepository,
                    candidateRepository, draftChangeRepository, draftWriteService, impactService,
                    new com.lightai.admin.query.PageResultFactory(clock), properties.getRuntimeMode());
        }

        @Bean
        public com.lightai.admin.alias.RouteCandidateService lightAiRouteCandidateService(
                DataSource dataSource,
                com.lightai.storage.alias.JdbcCandidateRepository candidateRepository,
                com.lightai.storage.alias.JdbcAliasRepository aliasRepository,
                com.lightai.storage.upstream.JdbcUpstreamModelRepository modelRepository,
                com.lightai.storage.channel.JdbcChannelRepository channelRepository,
                com.lightai.admin.draft.DraftWriteService draftWriteService,
                com.lightai.admin.check.ChannelCheckService providerCheckService,
                AdminProperties properties) {
            return new com.lightai.admin.alias.RouteCandidateService(dataSource, candidateRepository,
                    aliasRepository, modelRepository, channelRepository,
                    draftWriteService, providerCheckService, properties.getRuntimeMode());
        }

        @Bean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        public com.lightai.admin.alias.ModelAliasController lightAiModelAliasController(
                com.lightai.admin.alias.ModelAliasService aliasService,
                com.lightai.admin.alias.RouteCandidateService candidateService) {
            return new com.lightai.admin.alias.ModelAliasController(aliasService, candidateService);
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.governance.JdbcLimitPolicyRepository lightAiLimitPolicyRepository(
                StorageProperties properties) {
            return new com.lightai.storage.governance.JdbcLimitPolicyRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.governance.JdbcReliabilityPolicyRepository lightAiReliabilityPolicyRepository(
                StorageProperties properties) {
            return new com.lightai.storage.governance.JdbcReliabilityPolicyRepository(
                    properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.governance.JdbcCircuitRepository lightAiCircuitRepository(
                StorageProperties properties) {
            return new com.lightai.storage.governance.JdbcCircuitRepository(properties.getSchemaName());
        }

        /** Embedded 单实例进程内容量存储；Standalone 模式下无共享 Redis 时 fail-closed 拒绝新预占（CR-004）。 */
        @Bean
        @ConditionalOnMissingBean(com.lightai.runtime.capacity.CapacityStore.class)
        public com.lightai.runtime.capacity.CapacityStore lightAiCapacityStore(AdminProperties properties) {
            com.lightai.runtime.capacity.InMemoryCapacityStore store = new com.lightai.runtime.capacity.InMemoryCapacityStore();
            if ("STANDALONE_SERVER".equalsIgnoreCase(properties.getRuntimeMode())) {
                store.setUnavailable("集群模式未装配共享 Redis 容量存储");
            }
            return store;
        }

        @Bean
        @ConditionalOnMissingBean(com.lightai.runtime.circuit.CircuitStateStore.class)
        public com.lightai.runtime.circuit.CircuitStateStore lightAiCircuitStateStore() {
            return new com.lightai.runtime.circuit.InMemoryCircuitStore();
        }

        @Bean
        public com.lightai.admin.governance.GovernanceAdminService lightAiGovernanceAdminService(
                DataSource dataSource,
                com.lightai.storage.governance.JdbcLimitPolicyRepository limitPolicyRepository,
                com.lightai.storage.governance.JdbcReliabilityPolicyRepository reliabilityPolicyRepository,
                com.lightai.storage.governance.JdbcCircuitRepository circuitRepository,
                com.lightai.admin.draft.DraftWriteService draftWriteService,
                Clock clock, com.lightai.runtime.capacity.CapacityStore capacityStore,
                AdminProperties properties) {
            return new com.lightai.admin.governance.GovernanceAdminService(dataSource,
                    limitPolicyRepository, reliabilityPolicyRepository, circuitRepository,
                    draftWriteService, new com.lightai.admin.query.PageResultFactory(clock),
                    capacityStore, properties.getRuntimeMode());
        }

        @Bean
        public com.lightai.admin.governance.CircuitManagementService lightAiCircuitManagementService(
                DataSource dataSource,
                com.lightai.storage.governance.JdbcCircuitRepository circuitRepository,
                com.lightai.admin.draft.DraftWriteService draftWriteService,
                com.lightai.admin.audit.AuditService auditService,
                com.lightai.runtime.circuit.CircuitStateStore circuitStateStore,
                Clock clock, AdminProperties properties) {
            com.lightai.runtime.circuit.CircuitPolicy defaultPolicy = new com.lightai.runtime.circuit.CircuitPolicy(null, 0, 60, 20, 0.5, 30, 3, 2);
            return new com.lightai.admin.governance.CircuitManagementService(dataSource,
                    circuitRepository, draftWriteService, auditService, circuitStateStore,
                    defaultPolicy, new com.lightai.admin.query.PageResultFactory(clock),
                    properties.getRuntimeMode());
        }

        @Bean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        public com.lightai.admin.governance.GovernanceController lightAiGovernanceController(
                com.lightai.admin.governance.GovernanceAdminService governanceService,
                com.lightai.admin.governance.CircuitManagementService circuitService) {
            return new com.lightai.admin.governance.GovernanceController(governanceService,
                    circuitService);
        }

        // ---- 调用观测（BE-P06：BE-031~036）----

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.trace.JdbcTraceRepository lightAiTraceRepository(
                StorageProperties properties) {
            return new com.lightai.storage.trace.JdbcTraceRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.trace.JdbcTraceDetailRepository lightAiTraceDetailRepository(
                StorageProperties properties) {
            return new com.lightai.storage.trace.JdbcTraceDetailRepository(
                    properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.trace.JdbcUsageAggregateRepository lightAiUsageAggregateRepository(
                StorageProperties properties) {
            return new com.lightai.storage.trace.JdbcUsageAggregateRepository(
                    properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.trace.JdbcUsageAggregationEventRepository
                lightAiUsageAggregationEventRepository(StorageProperties properties) {
            return new com.lightai.storage.trace.JdbcUsageAggregationEventRepository(
                    properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.trace.JdbcOverviewStatsRepository lightAiOverviewStatsRepository(
                StorageProperties properties) {
            return new com.lightai.storage.trace.JdbcOverviewStatsRepository(
                    properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.trace.JdbcObservationConfigReader
                lightAiObservationConfigReader(StorageProperties properties) {
            return new com.lightai.storage.trace.JdbcObservationConfigReader(
                    properties.getSchemaName());
        }

        @Bean
        public com.lightai.admin.trace.TraceService lightAiTraceService(DataSource dataSource,
                com.lightai.storage.trace.JdbcTraceRepository traceRepository, Clock clock) {
            return new com.lightai.admin.trace.TraceService(dataSource, traceRepository,
                    new com.lightai.admin.query.PageResultFactory(clock), clock);
        }

        @Bean
        public com.lightai.admin.trace.TraceDetailService lightAiTraceDetailService(
                DataSource dataSource,
                com.lightai.storage.trace.JdbcTraceRepository traceRepository,
                com.lightai.storage.trace.JdbcTraceDetailRepository traceDetailRepository,
                com.lightai.storage.trace.JdbcObservationConfigReader observationConfigReader,
                com.lightai.admin.audit.AuditService auditService, Clock clock) {
            return new com.lightai.admin.trace.TraceDetailService(dataSource, traceRepository,
                    traceDetailRepository, observationConfigReader, auditService, clock);
        }

        @Bean
        public com.lightai.admin.trace.TraceExportService lightAiTraceExportService(
                DataSource dataSource,
                com.lightai.storage.trace.JdbcTraceRepository traceRepository) {
            return new com.lightai.admin.trace.TraceExportService(dataSource, traceRepository);
        }

        @Bean
        public com.lightai.admin.trace.TraceFinalizer lightAiTraceFinalizer(DataSource dataSource,
                com.lightai.storage.trace.JdbcTraceRepository traceRepository,
                com.lightai.storage.trace.JdbcUsageAggregationEventRepository eventRepository,
                PlatformTransactionManager transactionManager) {
            return new com.lightai.admin.trace.TraceFinalizer(dataSource, traceRepository,
                    eventRepository, transactionManager);
        }

        @Bean
        public com.lightai.admin.usage.UsageAggregator lightAiUsageAggregator(
                DataSource dataSource,
                com.lightai.storage.trace.JdbcTraceRepository traceRepository,
                com.lightai.storage.trace.JdbcUsageAggregateRepository aggregateRepository,
                com.lightai.storage.trace.JdbcUsageAggregationEventRepository eventRepository,
                com.lightai.storage.trace.JdbcObservationConfigReader observationConfigReader,
                PlatformTransactionManager transactionManager, Clock clock) {
            return new com.lightai.admin.usage.UsageAggregator(dataSource, traceRepository,
                    aggregateRepository, eventRepository, observationConfigReader,
                    transactionManager, clock);
        }

        @Bean
        public com.lightai.admin.usage.UsageService lightAiUsageService(DataSource dataSource,
                com.lightai.storage.trace.JdbcUsageAggregateRepository aggregateRepository,
                com.lightai.storage.trace.JdbcObservationConfigReader observationConfigReader,
                Clock clock) {
            return new com.lightai.admin.usage.UsageService(dataSource, aggregateRepository,
                    observationConfigReader, clock);
        }

        @Bean
        public com.lightai.admin.usage.UsageExportService lightAiUsageExportService(
                DataSource dataSource,
                com.lightai.storage.trace.JdbcUsageAggregateRepository aggregateRepository,
                com.lightai.admin.usage.UsageService usageService) {
            return new com.lightai.admin.usage.UsageExportService(dataSource, aggregateRepository,
                    usageService);
        }

        @Bean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        public com.lightai.admin.usage.UsageController lightAiUsageController(
                com.lightai.admin.usage.UsageService usageService,
                com.lightai.admin.usage.UsageExportService usageExportService) {
            return new com.lightai.admin.usage.UsageController(usageService, usageExportService);
        }

        @Bean
        public com.lightai.admin.overview.OverviewService lightAiOverviewService(
                DataSource dataSource,
                com.lightai.storage.trace.JdbcOverviewStatsRepository overviewStatsRepository,
                com.lightai.storage.trace.JdbcObservationConfigReader observationConfigReader,
                Clock clock) {
            return new com.lightai.admin.overview.OverviewService(dataSource,
                    overviewStatsRepository, observationConfigReader, clock);
        }

        @Bean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        public com.lightai.admin.overview.OverviewController lightAiOverviewController(
                com.lightai.admin.overview.OverviewService overviewService) {
            return new com.lightai.admin.overview.OverviewController(overviewService);
        }

        @Bean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        public com.lightai.admin.trace.TraceObservationController lightAiTraceObservationController(
                com.lightai.admin.trace.TraceService traceService,
                com.lightai.admin.trace.TraceDetailService traceDetailService,
                com.lightai.admin.trace.TraceExportService traceExportService) {
            return new com.lightai.admin.trace.TraceObservationController(traceService,
                    traceDetailService, traceExportService);
        }

        /**
         * 聚合事件轮询（BE-033）：单线程定时消费 Outbox 事件，
         * 允许两个 dashboard_refresh_seconds 内的聚合延迟；宿主可显式关闭。
         */
        @Bean
        @ConditionalOnProperty(name = "light-ai.admin.usage-aggregation-enabled",
                havingValue = "true", matchIfMissing = true)
        public SmartLifecycle lightAiUsageAggregationPoller(
                com.lightai.admin.usage.UsageAggregator aggregator) {
            return new com.lightai.admin.usage.UsageAggregationPoller(aggregator);
        }

        // ---------- 草稿发布（BE-P07） ----------

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.publish.ConfigValidationRepository lightAiConfigValidationRepository(
                StorageProperties properties) {
            return new com.lightai.storage.publish.JdbcConfigValidationRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.publish.ConfigSnapshotRepository lightAiConfigSnapshotRepository(
                StorageProperties properties) {
            return new com.lightai.storage.publish.JdbcConfigSnapshotRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.publish.PublishRecordRepository lightAiPublishRecordRepository(
                StorageProperties properties) {
            return new com.lightai.storage.publish.JdbcPublishRecordRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.publish.PublishInstanceResultRepository lightAiPublishInstanceResultRepository(
                StorageProperties properties) {
            return new com.lightai.storage.publish.JdbcPublishInstanceResultRepository(
                    properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.publish.RuntimeInstanceRepository lightAiRuntimeInstanceRepository(
                StorageProperties properties) {
            return new com.lightai.storage.publish.JdbcRuntimeInstanceRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.publish.SnapshotContentRepository lightAiSnapshotContentRepository(
                StorageProperties properties) {
            return new com.lightai.storage.publish.JdbcSnapshotContentRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.publish.DraftDependencyRepository lightAiDraftDependencyRepository(
                StorageProperties properties) {
            return new com.lightai.storage.publish.JdbcDraftDependencyRepository(properties.getSchemaName());
        }

        @Bean
        public com.lightai.admin.publish.DraftStateQueryService lightAiDraftStateQueryService(
                DataSource dataSource, DraftStateRepository draftStateRepository,
                com.lightai.storage.draft.JdbcDraftChangeRepository draftChangeRepository,
                com.lightai.storage.publish.DraftDependencyRepository dependencyRepository) {
            return new com.lightai.admin.publish.DraftStateQueryService(dataSource, draftStateRepository,
                    draftChangeRepository, dependencyRepository);
        }

        @Bean
        public com.lightai.admin.publish.DraftRevertService lightAiDraftRevertService(
                DataSource dataSource, PlatformTransactionManager transactionManager,
                com.lightai.storage.draft.JdbcDraftStateRepository draftStateRepository,
                com.lightai.storage.draft.JdbcDraftChangeRepository draftChangeRepository,
                com.lightai.storage.publish.ConfigSnapshotRepository snapshotRepository,
                com.lightai.storage.publish.SnapshotContentRepository snapshotContentRepository,
                com.lightai.storage.publish.DraftDependencyRepository dependencyRepository,
                com.lightai.admin.audit.AuditService auditService,
                AdminProperties properties) {
            return new com.lightai.admin.publish.DraftRevertService(dataSource, transactionManager,
                    draftStateRepository, draftStateRepository,
                    draftChangeRepository,
                    snapshotRepository, snapshotContentRepository, dependencyRepository,
                    auditService, properties.getRuntimeMode());
        }

        @Bean
        public com.lightai.admin.publish.ConfigValidationService lightAiConfigValidationService(
                DataSource dataSource, PlatformTransactionManager transactionManager, Clock clock,
                DraftStateRepository draftStateRepository,
                com.lightai.storage.draft.JdbcDraftChangeRepository draftChangeRepository,
                com.lightai.storage.publish.ConfigSnapshotRepository snapshotRepository,
                com.lightai.storage.publish.SnapshotContentRepository snapshotContentRepository,
                com.lightai.storage.publish.ConfigValidationRepository validationRepository,
                com.lightai.storage.publish.RuntimeInstanceRepository runtimeInstanceRepository,
                com.lightai.admin.channel.ProviderTypeRegistry providerTypeRegistry,
                com.lightai.storage.check.JdbcChannelCheckRecordRepository checkRecordRepository,
                com.lightai.admin.audit.AuditService auditService,
                AdminProperties properties) {
            return new com.lightai.admin.publish.ConfigValidationService(dataSource, transactionManager,
                    clock, draftStateRepository, draftChangeRepository,
                    snapshotRepository, snapshotContentRepository, validationRepository,
                    runtimeInstanceRepository, providerTypeRegistry, checkRecordRepository,
                    auditService, properties.getTimezone(), properties.getRuntimeMode());
        }

        @Bean
        public com.lightai.admin.publish.ConfigPublishService lightAiConfigPublishService(
                DataSource dataSource, PlatformTransactionManager transactionManager, Clock clock,
                com.lightai.storage.draft.JdbcDraftStateRepository draftStateRepository,
                com.lightai.storage.draft.JdbcDraftChangeRepository draftChangeRepository,
                com.lightai.storage.publish.ConfigSnapshotRepository snapshotRepository,
                com.lightai.storage.publish.SnapshotContentRepository snapshotContentRepository,
                com.lightai.storage.publish.ConfigValidationRepository validationRepository,
                com.lightai.storage.publish.PublishRecordRepository publishRecordRepository,
                com.lightai.storage.publish.PublishInstanceResultRepository instanceResultRepository,
                com.lightai.storage.publish.RuntimeInstanceRepository runtimeInstanceRepository,
                com.lightai.admin.audit.AuditService auditService, AdminProperties properties,
                com.lightai.runtime.ports.ConfigSnapshotPort snapshotPort) {
            return new com.lightai.admin.publish.ConfigPublishService(dataSource, transactionManager,
                    clock, draftStateRepository, draftStateRepository,
                    draftChangeRepository,
                    snapshotRepository, snapshotContentRepository, validationRepository,
                    publishRecordRepository, instanceResultRepository, runtimeInstanceRepository,
                    auditService, properties, snapshotPort);
        }

        @Bean
        public com.lightai.admin.publish.JdbcConfigSnapshotPortAdapter lightAiJdbcConfigSnapshotPortAdapter(
                DataSource dataSource) {
            return new com.lightai.admin.publish.JdbcConfigSnapshotPortAdapter(
                    com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME, dataSource);
        }

        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(
                com.lightai.runtime.ports.ConfigSnapshotPort.class)
        public com.lightai.runtime.ports.ConfigSnapshotPort lightAiConfigSnapshotPort(
                com.lightai.admin.publish.JdbcConfigSnapshotPortAdapter adapter) {
            return adapter;
        }

        @Bean
        public com.lightai.admin.publish.InternalInstanceAuth lightAiInternalInstanceAuth(
                AdminProperties properties) {
            return new com.lightai.admin.publish.InternalInstanceAuth(
                    properties.getInternalInstanceCredentials());
        }

        @Bean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        public com.lightai.admin.publish.ConfigDraftController lightAiConfigDraftController(
                com.lightai.admin.publish.DraftStateQueryService queryService,
                com.lightai.admin.publish.DraftRevertService revertService) {
            return new com.lightai.admin.publish.ConfigDraftController(queryService, revertService);
        }

        @Bean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        public com.lightai.admin.publish.ConfigPublishController lightAiConfigPublishController(
                com.lightai.admin.publish.ConfigValidationService validationService,
                com.lightai.admin.publish.ConfigPublishService publishService) {
            return new com.lightai.admin.publish.ConfigPublishController(validationService, publishService);
        }

        @Bean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        public com.lightai.admin.publish.InternalInstanceController lightAiInternalInstanceController(
                com.lightai.admin.publish.ConfigPublishService publishService,
                com.lightai.admin.publish.InternalInstanceAuth instanceAuth) {
            return new com.lightai.admin.publish.InternalInstanceController(publishService, instanceAuth);
        }

        /** /internal/** 实例认证拦截注册（仅存储装配时存在内部接口）。 */
        @Bean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        public WebMvcConfigurer lightAiInternalWebMvcConfigurer(
                com.lightai.admin.publish.InternalInstanceAuth instanceAuth) {
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(
                            new com.lightai.admin.publish.InternalAuthInterceptor(instanceAuth))
                            .addPathPatterns("/internal/**");
                }
            };
        }

        // ---------- 企业应用中心（V2.0） ----------

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.application.JdbcApplicationRepository lightAiApplicationRepository(
                StorageProperties properties) {
            return new com.lightai.storage.application.JdbcApplicationRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.application.JdbcApplicationKeyRepository lightAiApplicationKeyRepository(
                StorageProperties properties) {
            return new com.lightai.storage.application.JdbcApplicationKeyRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean(com.lightai.runtime.ports.ApplicationQuotaPort.class)
        public com.lightai.runtime.ports.ApplicationQuotaPort lightAiApplicationQuotaPort(
                DataSource dataSource, com.lightai.runtime.capacity.CapacityStore capacityStore,
                Clock clock, StorageProperties properties) {
            return new com.lightai.storage.application.JdbcApplicationQuotaPort(
                    dataSource, capacityStore, clock, properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.admin.application.ApplicationService lightAiApplicationService(
                DataSource dataSource,
                com.lightai.storage.application.JdbcApplicationRepository applicationRepository,
                com.lightai.storage.alias.JdbcAliasRepository aliasRepository,
                com.lightai.admin.audit.AuditService auditService,
                PlatformTransactionManager transactionManager,
                Clock clock, AdminProperties properties,
                ObjectProvider<com.lightai.runtime.ports.ConfigSnapshotPort> snapshotPortProvider) {
            return new com.lightai.admin.application.ApplicationService(
                    dataSource, applicationRepository, aliasRepository, auditService,
                    transactionManager, new com.lightai.admin.query.PageResultFactory(clock),
                    clock, properties.getRuntimeMode(), snapshotPortProvider.getIfAvailable());
        }

        @Bean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        public com.lightai.admin.application.ApplicationController lightAiApplicationController(
                com.lightai.admin.application.ApplicationService service) {
            return new com.lightai.admin.application.ApplicationController(service);
        }

        // ---------- 数据迁移执行器（BE-003 / CR-015） ----------

        @Bean
        @ConditionalOnMissingBean(com.lightai.storage.schema.SchemaMigrator.class)
        public com.lightai.storage.schema.SchemaMigrator lightAiSchemaMigrator(DataSource dataSource) {
            return new com.lightai.storage.schema.DefaultSchemaMigrator(dataSource);
        }

        // ---------- 访问凭证管理与鉴权（BE-044 / CR-003, CR-005） ----------

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.access.AccessCredentialRepository lightAiAccessCredentialRepository(
                StorageProperties properties) {
            return new com.lightai.storage.access.JdbcAccessCredentialRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.admin.security.AccessTokenService.PepperProvider lightAiAccessTokenPepperProvider(
                AdminProperties properties) {
            String pepper = properties.getAccessTokenPepper();
            if (pepper == null || pepper.isBlank()) {
                pepper = "light-ai-default-access-token-pepper";
            }
            return com.lightai.admin.security.AccessTokenService.fixedPepper(1, pepper);
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.admin.security.AccessTokenService lightAiAccessTokenService(
                com.lightai.admin.security.AccessTokenService.PepperProvider pepperProvider) {
            return new com.lightai.admin.security.AccessTokenService(pepperProvider);
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.admin.application.ApplicationKeyService lightAiApplicationKeyService(
                DataSource dataSource,
                com.lightai.storage.application.JdbcApplicationRepository applicationRepository,
                com.lightai.storage.application.JdbcApplicationKeyRepository applicationKeyRepository,
                com.lightai.admin.security.AccessTokenService tokenService,
                com.lightai.admin.audit.AuditService auditService,
                PlatformTransactionManager transactionManager,
                Clock clock, AdminProperties properties) {
            return new com.lightai.admin.application.ApplicationKeyService(
                    dataSource, applicationRepository, applicationKeyRepository, tokenService,
                    auditService, transactionManager, clock, properties.getRuntimeMode());
        }

        @Bean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        public com.lightai.admin.application.ApplicationKeyController lightAiApplicationKeyController(
                com.lightai.admin.application.ApplicationKeyService service) {
            return new com.lightai.admin.application.ApplicationKeyController(service);
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.admin.accesscred.AccessCredentialService lightAiAccessCredentialService(
                DataSource dataSource,
                com.lightai.storage.access.AccessCredentialRepository repository,
                com.lightai.admin.security.AccessTokenService tokenService,
                ObjectProvider<com.lightai.admin.audit.AuditService> auditServiceProvider,
                Clock clock, AdminProperties properties) {
            boolean isStandalone = "STANDALONE_SERVER".equalsIgnoreCase(properties.getRuntimeMode());
            return new com.lightai.admin.accesscred.AccessCredentialService(
                    dataSource, repository, tokenService,
                    auditServiceProvider::getIfAvailable,
                    clock, properties.getRuntimeMode(), isStandalone);
        }

        @Bean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        public com.lightai.admin.accesscred.AccessCredentialController lightAiAccessCredentialController(
                com.lightai.admin.accesscred.AccessCredentialService service) {
            return new com.lightai.admin.accesscred.AccessCredentialController(service);
        }

        @Bean
        @ConditionalOnMissingBean(com.lightai.runtime.ports.AccessTokenPort.class)
        public com.lightai.runtime.ports.AccessTokenPort lightAiAccessTokenPort(
                DataSource dataSource,
                com.lightai.storage.access.AccessCredentialRepository repository,
                com.lightai.storage.alias.JdbcAliasRepository aliasRepository,
                com.lightai.storage.application.JdbcApplicationRepository applicationRepository,
                com.lightai.storage.application.JdbcApplicationKeyRepository applicationKeyRepository,
                com.lightai.admin.security.AccessTokenService tokenService,
                Clock clock, AdminProperties properties) {
            return new com.lightai.admin.accesscred.AccessTokenAuthService(
                    dataSource, repository, aliasRepository, tokenService, clock, false,
                    applicationRepository, applicationKeyRepository);
        }

        // ---------- 审计查询与导出（BE-045 / CR-003） ----------

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.audit.AuditQueryRepository lightAiAuditQueryRepository(
                StorageProperties properties) {
            return new com.lightai.storage.audit.JdbcAuditQueryRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.admin.audit.AuditQueryService lightAiAuditQueryService(
                DataSource dataSource, com.lightai.storage.audit.AuditQueryRepository repository) {
            return new com.lightai.admin.audit.AuditQueryService(dataSource, repository);
        }

        @Bean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        public com.lightai.admin.audit.AuditController lightAiAuditController(
                com.lightai.admin.audit.AuditQueryService service) {
            return new com.lightai.admin.audit.AuditController(service);
        }

        // ---------- 开发接入与在线测试（BE-046/047 / CR-003, CR-006） ----------

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.admin.developer.DeveloperAccessService lightAiDeveloperAccessService(
                ObjectProvider<com.lightai.runtime.ports.ConfigSnapshotPort> snapshotPortProvider,
                ObjectProvider<com.lightai.runtime.ports.AccessTokenPort.RuntimeConfigPort> runtimeConfigPortProvider,
                ObjectProvider<com.lightai.runtime.chat.ChatPipeline> chatPipelineProvider,
                AdminProperties properties, Clock clock) {
            com.lightai.runtime.ports.ConfigSnapshotPort snapshotPort =
                    snapshotPortProvider.getIfAvailable(com.lightai.runtime.ports.ConfigSnapshotPort::empty);
            com.lightai.runtime.ports.AccessTokenPort.RuntimeConfigPort runtimeConfigPort =
                    runtimeConfigPortProvider.getIfAvailable(() -> java.util.Optional::empty);
            com.lightai.runtime.chat.ChatPipeline chatPipeline = chatPipelineProvider.getIfAvailable();
            return new com.lightai.admin.developer.DeveloperAccessService(
                    snapshotPort, runtimeConfigPort, chatPipeline, properties.getRuntimeMode(),
                    properties.getAdminApiBasePath(), clock);
        }

        @Bean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        public com.lightai.admin.developer.DeveloperAccessController lightAiDeveloperAccessController(
                com.lightai.admin.developer.DeveloperAccessService service) {
            return new com.lightai.admin.developer.DeveloperAccessController(service);
        }

        // ---------- 留存数据清理（BE-048 / CR-014） ----------

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.cleanup.RetentionDeletionRepository lightAiRetentionDeletionRepository(
                StorageProperties properties) {
            return new com.lightai.storage.cleanup.JdbcRetentionDeletionRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.admin.cleanup.RetentionCleanupService lightAiRetentionCleanupService(
                DataSource dataSource,
                com.lightai.storage.security.RuntimeConfigAdminRepository runtimeConfigRepository,
                com.lightai.storage.cleanup.RetentionDeletionRepository retentionDeletionRepository,
                Clock clock) {
            com.lightai.admin.cleanup.JdbcDeletionPortAdapter port =
                    new com.lightai.admin.cleanup.JdbcDeletionPortAdapter(dataSource, retentionDeletionRepository);
            return new com.lightai.admin.cleanup.RetentionCleanupService(port, clock,
                    () -> {
                        try (java.sql.Connection conn = dataSource.getConnection()) {
                            return runtimeConfigRepository.find(conn).map(c -> java.time.OffsetDateTime.now(clock).minusDays(c.traceRetentionDays())).orElse(java.time.OffsetDateTime.now(clock).minusDays(7));
                        } catch (Exception e) {
                            return java.time.OffsetDateTime.now(clock).minusDays(7);
                        }
                    },
                    () -> {
                        try (java.sql.Connection conn = dataSource.getConnection()) {
                            return runtimeConfigRepository.find(conn).map(c -> java.time.OffsetDateTime.now(clock).minusDays(c.usageRetentionDays())).orElse(java.time.OffsetDateTime.now(clock).minusDays(90));
                        } catch (Exception e) {
                            return java.time.OffsetDateTime.now(clock).minusDays(90);
                        }
                    },
                    () -> {
                        try (java.sql.Connection conn = dataSource.getConnection()) {
                            return runtimeConfigRepository.find(conn).map(c -> java.time.OffsetDateTime.now(clock).minusDays(c.auditRetentionDays())).orElse(java.time.OffsetDateTime.now(clock).minusDays(365));
                        } catch (Exception e) {
                            return java.time.OffsetDateTime.now(clock).minusDays(365);
                        }
                    },
                    () -> {
                        try (java.sql.Connection conn = dataSource.getConnection()) {
                            return runtimeConfigRepository.find(conn).map(c -> java.time.OffsetDateTime.now(clock).minusDays(c.diagnosticSampleRetentionDays())).orElse(java.time.OffsetDateTime.now(clock).minusDays(3));
                        } catch (Exception e) {
                            return java.time.OffsetDateTime.now(clock).minusDays(3);
                        }
                    });
        }

        /** 启动结构检查（BE-003）：VALIDATE 校验、MIGRATE 先迁移后校验；失败阻止就绪。 */
        @Bean
        public SmartInitializingSingleton lightAiSchemaGuardInitializer(
                DataSource dataSource, StorageProperties properties,
                ObjectProvider<SchemaMigrator> schemaMigrator) {
            return () -> {
                SchemaGuard guard = new SchemaGuard(dataSource, properties.getSchemaName());
                if (SchemaMode.MIGRATE.name().equalsIgnoreCase(properties.getSchemaMode())) {
                    guard.migrateAndValidate(schemaMigrator.getIfAvailable());
                } else {
                    guard.validate();
                }
            };
        }
    }
}
