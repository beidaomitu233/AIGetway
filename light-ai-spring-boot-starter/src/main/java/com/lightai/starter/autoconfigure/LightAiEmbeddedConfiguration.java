package com.lightai.starter.autoconfigure;

import com.lightai.client.LightAiClient;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.local.LocalRuntimeDefinition;
import com.lightai.spi.auth.AuthContext;
import com.lightai.spi.auth.AuthContextProvider;
import com.lightai.spi.auth.AuthRequest;
import com.lightai.spi.provider.ProviderAdapter;
import com.lightai.spi.secret.SecretProvider;
import com.lightai.starter.properties.SpringLightAiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EMBEDDED 模式自动装配（PRD 4.6.3.1，BE-055）：
 * 绑定宿主配置、校验数据结构、加载快照、收集 Adapter 与 SPI 扩展；
 * 挂载 Embedded Admin UI 与安全鉴权过滤。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "light-ai", name = "mode", havingValue = "EMBEDDED")
public class LightAiEmbeddedConfiguration {

    public LightAiEmbeddedConfiguration(SpringLightAiProperties properties,
                                        ObjectProvider<List<ProviderAdapter>> adaptersProvider) {
        // 1. 校验 application 必填
        if (properties.getApplication() == null || properties.getApplication().isBlank()) {
            throw new IllegalStateException("light-ai.application 必须在 EMBEDDED 模式下配置");
        }

        // 2. 校验 ProviderAdapter 重复类型拦截（PRD 4.6.3.1）
        List<ProviderAdapter> adapters = adaptersProvider.getIfAvailable(ArrayList::new);
        Map<String, String> typeToBean = new HashMap<>();
        for (ProviderAdapter adapter : adapters) {
            String type = adapter.providerType();
            String beanName = adapter.getClass().getSimpleName();
            if (typeToBean.containsKey(type)) {
                throw new IllegalStateException("检测到冲突的 ProviderAdapter provider_type: " + type
                        + "，冲突 Bean 名称: [" + typeToBean.get(type) + ", " + beanName + "]");
            }
            typeToBean.put(type, beanName);
        }

        // 3. 校验 Admin 路径冲突（PRD 4.6.3.4）
        String adminPath = properties.getAdmin().getPath();
        if (adminPath != null) {
            if (adminPath.equals("/") || adminPath.equals("/v1") || adminPath.equals("/api")) {
                throw new LightAiException(ErrorCode.ADMIN_PATH_CONFLICT,
                        "Admin 路径与宿主保留路径冲突: " + adminPath);
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean(LightAiClient.class)
    public LightAiClient embeddedLightAiClient(SpringLightAiProperties properties,
                                              ObjectProvider<List<ProviderAdapter>> adaptersProvider,
                                              ObjectProvider<List<SecretProvider>> secretProvidersProvider) {
        LocalRuntimeDefinition definition = LocalRuntimeDefinition.builder()
                .addProvider("default-provider", "OPENAI", "https://api.openai.com")
                .addPool("default-pool", "default-provider")
                .addCredential("default-cred", "default-pool", "default-provider", "inline://default")
                .addModel("default-model-id", "default-provider", "gpt-4o")
                .addAlias("default-model", "default-model-id", "default-pool")
                .build();

        List<ProviderAdapter> adapters = adaptersProvider.getIfAvailable(ArrayList::new);
        List<SecretProvider> secretProviders = secretProvidersProvider.getIfAvailable(ArrayList::new);

        return LightAiClient.builder()
                .localRuntimeDefinition(definition)
                .adapters(adapters)
                .secretProviders(secretProviders)
                .build();
    }

    /**
     * Embedded Admin UI 安全与本地访问过滤器（PRD 4.6.3.4，RV-047）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(FilterRegistrationBean.class)
    @ConditionalOnProperty(prefix = "light-ai.admin", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static class EmbeddedAdminSecurityConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "embeddedAdminSecurityFilterRegistration")
        public FilterRegistrationBean<EmbeddedAdminSecurityFilter> embeddedAdminSecurityFilterRegistration(
                SpringLightAiProperties properties,
                ObjectProvider<AuthContextProvider> authContextProvider) {

            EmbeddedAdminSecurityFilter filter = new EmbeddedAdminSecurityFilter(
                    properties,
                    authContextProvider.getIfAvailable()
            );

            FilterRegistrationBean<EmbeddedAdminSecurityFilter> registration = new FilterRegistrationBean<>(filter);
            String path = properties.getAdmin().getPath();
            if (!path.endsWith("/*")) {
                path = path.endsWith("/") ? path + "*" : path + "/*";
            }
            registration.addUrlPatterns(path);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
            return registration;
        }
    }

    public static class EmbeddedAdminSecurityFilter extends OncePerRequestFilter {

        private final SpringLightAiProperties properties;
        private final AuthContextProvider authContextProvider;

        public EmbeddedAdminSecurityFilter(SpringLightAiProperties properties,
                                           AuthContextProvider authContextProvider) {
            this.properties = properties;
            this.authContextProvider = authContextProvider;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {

            AuthContext authContext = resolveContext(request);
            if (authContext == null || !authContext.authenticated()) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":{\"code\":\"ACCESS_DENIED\",\"message\":\"未授权访问 Embedded Admin\"}}");
                return;
            }

            filterChain.doFilter(request, response);
        }

        private AuthContext resolveContext(HttpServletRequest request) {
            if (authContextProvider != null) {
                try {
                    Map<String, String> headers = new HashMap<>();
                    Enumeration<String> headerNames = request.getHeaderNames();
                    if (headerNames != null) {
                        while (headerNames.hasMoreElements()) {
                            String name = headerNames.nextElement();
                            headers.put(name, request.getHeader(name));
                        }
                    }
                    return authContextProvider.resolve(new AuthRequest(
                            request.getMethod(),
                            request.getRequestURI(),
                            headers,
                            request.getRemoteAddr()
                    ));
                } catch (Exception e) {
                    return AuthContext.anonymous();
                }
            }

            // 无 AuthContextProvider 时，检查 localAccessEnabled
            if (properties.getAdmin().isLocalAccessEnabled()) {
                String remoteAddr = request.getRemoteAddr();
                if (isLoopbackOrTrusted(remoteAddr, properties.getAdmin().getTrustedNetworkCidrs())) {
                    return AuthContext.authenticated(
                            "LOCAL_ADMIN",
                            "本地管理员",
                            Set.of("ADMIN", "OPERATOR"),
                            List.of(properties.getApplication() != null ? properties.getApplication() : "*")
                    );
                }
            }

            return AuthContext.anonymous();
        }

        private static boolean isLoopbackOrTrusted(String remoteAddr, List<String> trustedCidrs) {
            if (remoteAddr == null) return false;
            if (remoteAddr.equals("127.0.0.1") || remoteAddr.equals("0:0:0:0:0:0:0:1") || remoteAddr.equals("::1") || remoteAddr.equals("localhost")) {
                return true;
            }
            if (trustedCidrs != null) {
                for (String cidr : trustedCidrs) {
                    if (remoteAddr.startsWith(cidr.replace("/24", "").replace("/16", ""))) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
