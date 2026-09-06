package com.lightai.provider.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lightai.spi.provider.ProviderChatRequest;
import com.lightai.spi.provider.ProviderConfigView;
import com.lightai.spi.provider.ProviderChatResponse;
import com.lightai.spi.provider.ProviderErrorClassification;
import com.lightai.spi.provider.ProviderFailure;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adapter 共享 HTTP 传输（4.7.2.3）：复用 JDK HttpClient 连接池，不每次新建客户端；
 * 每次方法恰好一次外部请求，无内置重试；遵守 deadline 与取消。
 * 密钥仅在构造 Authorization 头的最小作用域读取，用后清零，不进日志。
 */
public class AdapterHttp {

    private static final Map<Integer, HttpClient> CLIENTS = new ConcurrentHashMap<>();

    /** 传输失败信号：Adapter 把它交给 classifyError，不在传输层决定恢复。 */
    public static final class TransportException extends com.lightai.spi.provider.ProviderTransportException {
        public TransportException(ProviderFailure failure, Throwable cause) {
            super(failure, cause);
        }
    }

    public static HttpClient client(int connectTimeoutMs) {
        int key = Math.max(1, Math.min(connectTimeoutMs, 60_000));
        return CLIENTS.computeIfAbsent(key, k -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(k))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    public static void clearClients() {
        CLIENTS.clear();
    }

    /** 非流式 POST：返回 2xx 响应体；非 2xx/网络异常包装为 TransportException。 */
    public static String postJson(ProviderConfigView config, String path, String jsonBody,
                           Instant deadline, Map<String, String> extraHeaders) {
        long readTimeoutMs = remainingMs(config, deadline);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(config.baseUrl()) + path))
                .timeout(Duration.ofMillis(Math.max(1, readTimeoutMs)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, java.nio.charset.StandardCharsets.UTF_8));
        for (Map.Entry<String, String> header : config.defaultHeaders().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        if (extraHeaders != null) {
            extraHeaders.forEach(builder::header);
        }
        try {
            HttpResponse<String> response = client(config.connectTimeoutMs())
                    .send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new TransportException(
                        ProviderFailure.http(response.statusCode(), response.headers().firstValue("x-request-id").orElse(null),
                                safeSnippet(response.body())),
                        null);
            }
            return response.body();
        } catch (HttpTimeoutException e) {
            throw new TransportException(ProviderFailure.connectTimeout("connect/read timeout"), e);
        } catch (IOException e) {
            throw new TransportException(ProviderFailure.network(e.getClass().getSimpleName()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException(ProviderFailure.network("interrupted"), e);
        }
    }

    /** 流式 POST：返回原始响应体流；调用方负责关闭并遵守取消。 */
    public static HttpResponse<java.io.InputStream> postStream(ProviderConfigView config, String path, String jsonBody,
                                                        Instant deadline,
                                                        Map<String, String> extraHeaders) {
        long readTimeoutMs = remainingMs(config, deadline);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(config.baseUrl()) + path))
                .timeout(Duration.ofMillis(Math.max(1, readTimeoutMs)))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, java.nio.charset.StandardCharsets.UTF_8));
        for (Map.Entry<String, String> header : config.defaultHeaders().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        if (extraHeaders != null) {
            extraHeaders.forEach(builder::header);
        }
        try {
            HttpResponse<java.io.InputStream> response = client(config.connectTimeoutMs())
                    .send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                String body = new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                throw new TransportException(
                        ProviderFailure.http(response.statusCode(),
                                response.headers().firstValue("x-request-id").orElse(null), safeSnippet(body)),
                        null);
            }
            return response;
        } catch (HttpTimeoutException e) {
            throw new TransportException(ProviderFailure.firstTokenTimeout("first token timeout"), e);
        } catch (IOException e) {
            throw new TransportException(ProviderFailure.network(e.getClass().getSimpleName()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException(ProviderFailure.network("interrupted"), e);
        }
    }

    /** 构造传输失败信号。 */
    public static TransportException transport(ProviderFailure failure) {
        return new TransportException(failure, null);
    }

    /** 解析 JSON 失败映射 PROVIDER_BAD_RESPONSE。 */
    public static JsonNode parseJson(ObjectMapper mapper, String body) {
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new TransportException(ProviderFailure.badResponse("json parse failed"), e);
        }
    }

    private static long remainingMs(ProviderConfigView config, Instant deadline) {
        long remaining = config.readTimeoutMs();
        if (deadline != null) {
            long toDeadline = Duration.between(Instant.now(), deadline).toMillis();
            remaining = Math.min(remaining, Math.max(1, toDeadline));
        }
        return remaining;
    }

    private static String stripTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** 错误正文安全片段：只保留异常类名与有限长度，避免正文进日志。 */
    public static String safeSnippet(String body) {
        if (body == null) {
            return "";
        }
        String snippet = body.length() > 200 ? body.substring(0, 200) : body;
        return snippet.replaceAll("\\s+", " ");
    }
}
