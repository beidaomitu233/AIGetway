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
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
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
    public BootstrapController lightAiBootstrapController(BootstrapService bootstrapService,
                                                          CsrfTokenService csrfTokenService,
                                                          AdminProperties properties) {
        return new BootstrapController(bootstrapService, csrfTokenService, properties.isCsrfEnabled());
    }

    @Bean
    public AdminAuthInterceptor lightAiAdminAuthInterceptor(AuthContextProvider authContextProvider) {
        return new AdminAuthInterceptor(authContextProvider);
    }

    @Bean
    public WebMvcConfigurer lightAiAdminWebMvcConfigurer(AdminAuthInterceptor adminAuthInterceptor) {
        return new AdminWebMvcConfigurer(adminAuthInterceptor);
    }

    @Bean
    public FilterRegistrationBean<RequestIdFilter> lightAiRequestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration =
                new FilterRegistrationBean<>(new RequestIdFilter());
        registration.addUrlPatterns("/admin/*", "/v1/*", "/internal/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
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
        public DraftStateRepository lightAiDraftStateRepository(StorageProperties properties) {
            return new JdbcDraftStateRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public DraftChangeRepository lightAiDraftChangeRepository(StorageProperties properties) {
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
        public com.lightai.storage.provider.JdbcProviderRepository lightAiProviderRepository(
                StorageProperties properties) {
            return new com.lightai.storage.provider.JdbcProviderRepository(properties.getSchemaName());
        }

        @Bean
        @ConditionalOnMissingBean
        public com.lightai.storage.pool.JdbcPoolRepository lightAiPoolRepository(
                StorageProperties properties) {
            return new com.lightai.storage.pool.JdbcPoolRepository(properties.getSchemaName());
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
        public com.lightai.storage.check.JdbcProviderCheckRecordRepository lightAiProviderCheckRecordRepository(
                StorageProperties properties) {
            return new com.lightai.storage.check.JdbcProviderCheckRecordRepository(properties.getSchemaName());
        }

        @Bean
        public com.lightai.admin.provider.ProviderTypeRegistry lightAiProviderTypeRegistry(
                ObjectProvider<AdapterMetadataSource> adapterMetadataSource) {
            return new com.lightai.admin.provider.ProviderTypeRegistry(adapterMetadataSource.getIfAvailable());
        }

        @Bean
        public com.lightai.admin.provider.TargetUrlPolicy lightAiTargetUrlPolicy(
                AdminProperties properties) {
            return new com.lightai.admin.provider.TargetUrlPolicy(properties.isAllowedProviderInternalNetworks());
        }

        @Bean
        public com.lightai.admin.impact.ImpactService lightAiImpactService(
                com.lightai.storage.reference.JdbcConfigReferenceRepository referenceRepository,
                StorageProperties properties) {
            return new com.lightai.admin.impact.ImpactService(referenceRepository, properties.getSchemaName());
        }

        @Bean
        public com.lightai.admin.provider.ProviderService lightAiProviderService(
                DataSource dataSource,
                com.lightai.storage.provider.JdbcProviderRepository providerRepository,
                com.lightai.storage.reference.JdbcConfigReferenceRepository referenceRepository,
                com.lightai.storage.runtime.JdbcObjectRuntimeStateRepository runtimeStateRepository,
                com.lightai.storage.runtime.JdbcRuntimeStateWriter runtimeStateWriter,
                com.lightai.storage.check.JdbcProviderCheckRecordRepository checkRecordRepository,
                DraftChangeRepository draftChangeRepository,
                com.lightai.admin.draft.DraftWriteService draftWriteService,
                com.lightai.admin.impact.ImpactService impactService,
                com.lightai.admin.provider.ProviderTypeRegistry typeRegistry,
                com.lightai.admin.provider.TargetUrlPolicy targetUrlPolicy,
                Clock clock, AdminProperties properties) {
            return new com.lightai.admin.provider.ProviderService(dataSource, providerRepository,
                    referenceRepository, runtimeStateRepository, runtimeStateWriter,
                    checkRecordRepository, draftChangeRepository,
                    draftWriteService, impactService, typeRegistry, targetUrlPolicy,
                    new com.lightai.admin.query.PageResultFactory(clock), properties.getRuntimeMode());
        }

        @Bean
        public com.lightai.admin.pool.PoolService lightAiPoolService(
                DataSource dataSource,
                com.lightai.storage.pool.JdbcPoolRepository poolRepository,
                com.lightai.storage.reference.JdbcConfigReferenceRepository referenceRepository,
                DraftChangeRepository draftChangeRepository,
                com.lightai.admin.draft.DraftWriteService draftWriteService,
                com.lightai.admin.impact.ImpactService impactService,
                Clock clock, AdminProperties properties, StorageProperties storageProperties) {
            return new com.lightai.admin.pool.PoolService(dataSource, poolRepository,
                    referenceRepository, draftChangeRepository, draftWriteService, impactService,
                    new com.lightai.admin.query.PageResultFactory(clock), properties.getRuntimeMode(),
                    storageProperties.getSchemaName());
        }

        @Bean
        public com.lightai.admin.check.ProviderCheckService lightAiProviderCheckService(
                DataSource dataSource,
                com.lightai.storage.provider.JdbcProviderRepository providerRepository,
                com.lightai.storage.reference.JdbcConfigReferenceRepository referenceRepository,
                com.lightai.storage.check.JdbcProviderCheckRecordRepository checkRecordRepository,
                com.lightai.storage.runtime.JdbcRuntimeStateWriter runtimeStateWriter,
                org.springframework.beans.factory.ObjectProvider<com.lightai.spi.check.ProviderCheckExecutor> executors,
                AdminProperties properties) {
            return new com.lightai.admin.check.ProviderCheckService(dataSource, providerRepository,
                    referenceRepository, checkRecordRepository, runtimeStateWriter,
                    executors.orderedStream().toList(), properties.getRuntimeMode());
        }

        @Bean
        public com.lightai.admin.provider.ProviderController lightAiProviderController(
                com.lightai.admin.provider.ProviderService providerService,
                com.lightai.admin.check.ProviderCheckService providerCheckService) {
            return new com.lightai.admin.provider.ProviderController(providerService, providerCheckService);
        }

        @Bean
        public com.lightai.admin.pool.PoolController lightAiPoolController(
                com.lightai.admin.pool.PoolService poolService) {
            return new com.lightai.admin.pool.PoolController(poolService);
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
