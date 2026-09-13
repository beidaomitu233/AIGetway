package com.lightai.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lightai.provider.anthropic.AnthropicAdapter;
import com.lightai.provider.deepseek.DeepSeekAdapter;
import com.lightai.provider.gemini.GeminiAdapter;
import com.lightai.provider.openai.OpenAiAdapter;
import com.lightai.server.ServerApplication;
import com.lightai.spi.adapter.AdapterMetadataSource;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Standalone 装配验证（UI-ANT-CONTRACT-001）：ServerApplication 必须装配
 * AdapterMetadataSource，使 GET /admin/bootstrap 输出 adapters、渠道类型
 * 下拉获得选项；声明不得包含密钥或凭证类字段。
 */
class ServerAdapterMetadataAssemblyTest {

    @Test
    void serverAssemblyDeclaresAllBuiltInAdapters() {
        AdapterMetadataSource source = new ServerApplication().adapterMetadataSource(
                List.of(new OpenAiAdapter(), new AnthropicAdapter(),
                        new GeminiAdapter(), new DeepSeekAdapter()));

        Map<String, String> baseUrlByType = source.declarations().stream()
                .collect(Collectors.toMap(declaration -> declaration.providerType(),
                        declaration -> declaration.defaultBaseUrl(),
                        (left, right) -> left, java.util.LinkedHashMap::new));

        assertEquals(4, baseUrlByType.size());
        assertEquals("https://api.openai.com/v1/", baseUrlByType.get("OPENAI"));
        assertEquals("https://api.anthropic.com/v1/", baseUrlByType.get("ANTHROPIC"));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/", baseUrlByType.get("GEMINI"));
        assertEquals("https://api.deepseek.com/v1/", baseUrlByType.get("DEEPSEEK"));

        source.declarations().forEach(declaration -> {
            assertTrue(declaration.capabilities().contains("CHAT"));
            assertTrue(declaration.capabilities().contains("STREAM"));
            assertTrue(declaration.adapterVersion() == null || !declaration.adapterVersion().isBlank());
        });
    }
}
