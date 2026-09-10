package com.lightai.admin;

import com.lightai.admin.bootstrap.BootstrapService;
import com.lightai.admin.web.ReactiveAdminWebFilter;
import com.lightai.admin.web.ReactiveBootstrapController;
import com.lightai.admin.web.ReactiveCsrfTokenService;
import com.lightai.spi.auth.AuthContextProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.server.WebFilter;

/** Reactive 管理入口适配；管理业务服务和 JDBC 仓储由公共自动装配复用。 */
@AutoConfiguration(after = LightAiAdminAutoConfiguration.class)
@ConditionalOnClass(WebFilter.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(name = "light-ai.admin.enabled", havingValue = "true", matchIfMissing = true)
public class LightAiReactiveAdminAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ReactiveCsrfTokenService lightAiReactiveCsrfTokenService() {
        return new ReactiveCsrfTokenService();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactiveAdminWebFilter lightAiReactiveAdminWebFilter(
            AuthContextProvider authContextProvider,
            ReactiveCsrfTokenService csrfTokenService,
            AdminProperties properties) {
        return new ReactiveAdminWebFilter(
                authContextProvider, csrfTokenService, properties.isCsrfEnabled());
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactiveBootstrapController lightAiReactiveBootstrapController(
            BootstrapService bootstrapService,
            ReactiveCsrfTokenService csrfTokenService,
            AdminProperties properties) {
        return new ReactiveBootstrapController(
                bootstrapService, csrfTokenService, properties.isCsrfEnabled());
    }
}
