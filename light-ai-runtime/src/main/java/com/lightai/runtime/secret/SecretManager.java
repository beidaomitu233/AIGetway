package com.lightai.runtime.secret;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.spi.secret.ResolvedSecret;
import com.lightai.spi.secret.SecretProvider;
import com.lightai.spi.secret.SecretResolveRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部密钥管理器（BE-053，4.6.3.6）：
 * 1. 唯一匹配校验：多个 SecretProvider 匹配报 SECRET_PROVIDER_CONFLICT；
 * 2. 短期缓存：缓存未过期 ResolvedSecret，过期不命中；
 * 3. 主动失效：invalidate 立即清空缓存并委托给 SPI；
 * 4. 内存安全：关闭时清空并擦除缓存密钥。
 */
public class SecretManager {

    private final List<SecretProvider> providers;
    private final Map<String, ResolvedSecret> cache = new ConcurrentHashMap<>();

    public SecretManager(List<SecretProvider> providers) {
        this.providers = providers != null ? List.copyOf(providers) : List.of();
    }

    public boolean hasProvider() {
        return !providers.isEmpty();
    }

    /**
     * 解析密钥引用。
     */
    public CompletionStage<ResolvedSecret> resolve(SecretResolveRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        String ref = request.secretRef();

        // 检查缓存
        ResolvedSecret cached = cache.get(ref);
        if (cached != null) {
            if (!cached.isExpired(Instant.now()) && !cached.isCleared()) {
                return CompletableFuture.completedFuture(cached);
            } else {
                cache.remove(ref);
                cached.clear();
            }
        }

        // 检查唯一匹配
        SecretProvider matched = findSingleProvider(ref);

        return matched.resolve(request).thenApply(resolved -> {
            if (resolved != null && !resolved.isExpired(Instant.now())) {
                cache.put(ref, resolved);
            }
            return resolved;
        }).exceptionally(t -> {
            if (t instanceof LightAiException lae) {
                throw lae;
            }
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            if (cause instanceof LightAiException lae) {
                throw lae;
            }
            throw new LightAiException(ErrorCode.SECRET_RESOLUTION_FAILED,
                    "解析密钥失败: " + matched.mask(ref) + " - " + cause.getMessage());
        });
    }

    /**
     * 同步解析密钥。
     */
    public ResolvedSecret resolveSync(String secretRef) {
        try {
            return resolve(SecretResolveRequest.of(secretRef)).toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException e) {
            if (e.getCause() instanceof LightAiException lae) {
                throw lae;
            }
            throw new LightAiException(ErrorCode.SECRET_RESOLUTION_FAILED, e.getCause().getMessage());
        }
    }

    /**
     * 主动失效特定引用（BE-053）。
     */
    public void invalidate(String secretRef, int version) {
        ResolvedSecret old = cache.remove(secretRef);
        if (old != null) {
            old.clear();
        }
        for (SecretProvider p : providers) {
            try {
                if (p.supports(secretRef)) {
                    p.invalidate(secretRef, version);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private SecretProvider findSingleProvider(String secretRef) {
        List<SecretProvider> matched = new ArrayList<>();
        for (SecretProvider p : providers) {
            try {
                if (p.supports(secretRef)) {
                    matched.add(p);
                }
            } catch (Exception ignored) {
            }
        }

        if (matched.size() > 1) {
            throw new LightAiException(ErrorCode.SECRET_PROVIDER_CONFLICT,
                    "多个 SecretProvider 同时匹配该密钥引用: " + mask(secretRef));
        }

        if (matched.isEmpty()) {
            throw new LightAiException(ErrorCode.SECRET_RESOLUTION_FAILED,
                    "没有匹配的 SecretProvider: " + mask(secretRef));
        }

        return matched.get(0);
    }

    private String mask(String secretRef) {
        if (secretRef == null) return "****";
        return secretRef.length() > 12 ? secretRef.substring(0, 12) + "…" : secretRef;
    }

    public void clear() {
        for (ResolvedSecret secret : cache.values()) {
            try {
                secret.clear();
            } catch (Exception ignored) {
            }
        }
        cache.clear();
    }
}