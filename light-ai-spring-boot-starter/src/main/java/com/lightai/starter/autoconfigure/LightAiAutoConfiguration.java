package com.lightai.starter.autoconfigure;

import com.lightai.starter.properties.SpringLightAiProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * 轻享 AI 主自动装配入口（PRD 4.6.3，BE-055）：
 * 统一注册在 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "light-ai", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SpringLightAiProperties.class)
@Import({
        LightAiStandaloneClientConfiguration.class,
        LightAiEmbeddedConfiguration.class
})
public class LightAiAutoConfiguration {
}
