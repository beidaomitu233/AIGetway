package com.lightai.starter.autoconfigure;

import com.lightai.client.LightAiClient;
import com.lightai.starter.properties.SpringLightAiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * STANDALONE_CLIENT 模式自动装配（PRD 4.6.3.1，BE-055）：
 * 仅装配客户端 HTTP 传输与 LightAiClient Bean；
 * 严禁创建 RuntimeCore、数据库仓储、Redis、Admin UI 与 Provider Adapter。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "light-ai", name = "mode", havingValue = "STANDALONE_CLIENT", matchIfMissing = true)
public class LightAiStandaloneClientConfiguration {

    @Bean
    @ConditionalOnMissingBean(LightAiClient.class)
    public LightAiClient standaloneLightAiClient(SpringLightAiProperties properties) {
        SpringLightAiProperties.ClientProperties clientProps = properties.getClient();
        String baseUrl = clientProps.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8080";
        }
        String token = clientProps.getAccessToken();
        if (token == null || token.isBlank()) {
            token = "default-token";
        }

        return LightAiClient.builder()
                .baseUrl(baseUrl)
                .token(token)
                .connectTimeout(Duration.ofMillis(clientProps.getConnectTimeoutMs()))
                .readTimeout(Duration.ofMillis(clientProps.getRequestTimeoutMs()))
                .transportRetryCount(clientProps.getTransportRetries())
                .build();
    }
}
