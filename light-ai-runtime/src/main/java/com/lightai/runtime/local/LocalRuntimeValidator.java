package com.lightai.runtime.local;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Local Runtime 离线静态校验器（BE-050，4.6.2.3）：
 * 纯内存离线校验，引用完整性、能力边界与价格，校验失败立即抛出异常阻止客户端创建。
 */
public final class LocalRuntimeValidator {

    private LocalRuntimeValidator() {
    }

    public static void validate(LocalRuntimeDefinition def) {
        if (def == null) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "LocalRuntimeDefinition 不能为空");
        }

        Map<String, LocalRuntimeDefinition.LocalProviderDefinition> providers = def.providers().stream()
                .collect(Collectors.toMap(LocalRuntimeDefinition.LocalProviderDefinition::providerId, p -> p, (a, b) -> a));

        Map<String, LocalRuntimeDefinition.LocalPoolDefinition> pools = def.pools().stream()
                .collect(Collectors.toMap(LocalRuntimeDefinition.LocalPoolDefinition::poolId, p -> p, (a, b) -> a));

        Map<String, LocalRuntimeDefinition.LocalModelDefinition> models = def.models().stream()
                .collect(Collectors.toMap(LocalRuntimeDefinition.LocalModelDefinition::modelId, m -> m, (a, b) -> a));

        // 校验 Model 与 Provider 关联
        for (LocalRuntimeDefinition.LocalModelDefinition model : def.models()) {
            if (model.modelId() == null || model.modelId().isBlank()) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "modelId 不能为空", "model_id");
            }
            if (!providers.containsKey(model.providerId())) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "模型 " + model.modelId() + " 关联的 provider " + model.providerId() + " 不存在", "provider_id");
            }
            if (model.contextWindow() != null && model.maxOutputTokens() != null) {
                if (model.contextWindow() < model.maxOutputTokens()) {
                    throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "模型 " + model.modelId() + " 的 context_window (" + model.contextWindow() + ") 不能小于 max_output_tokens (" + model.maxOutputTokens() + ")");
                }
            }
            if (model.priceUnit() <= 0) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "模型 " + model.modelId() + " 的 price_unit 必须大于 0");
            }
        }

        // 校验 Pool 与 Provider 关联
        for (LocalRuntimeDefinition.LocalPoolDefinition pool : def.pools()) {
            if (!providers.containsKey(pool.providerId())) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "凭证池 " + pool.poolId() + " 关联的 provider " + pool.providerId() + " 不存在", "provider_id");
            }
        }

        // 校验 Credential
        for (LocalRuntimeDefinition.LocalCredentialDefinition cred : def.credentials()) {
            if (cred.poolId() != null && !pools.containsKey(cred.poolId())) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "凭证 " + cred.credentialId() + " 关联的凭证池 " + cred.poolId() + " 不存在", "pool_id");
            }
            if (cred.poolId() != null) {
                LocalRuntimeDefinition.LocalPoolDefinition pool = pools.get(cred.poolId());
                if (!pool.providerId().equals(cred.providerId())) {
                    throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "凭证 " + cred.credentialId() + " 的 providerId 与关联池的 providerId 不一致");
                }
            }
        }

        // 校验 Aliases
        if (def.aliases().isEmpty()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "LocalRuntimeDefinition 至少需要配置一个 ModelAlias", "aliases");
        }

        Set<String> aliasNames = new HashSet<>();
        for (LocalRuntimeDefinition.LocalAliasDefinition alias : def.aliases()) {
            if (alias.alias() == null || alias.alias().isBlank()) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "alias 标识不能为空", "alias");
            }
            if (!aliasNames.add(alias.alias())) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "重复的 alias 标识: " + alias.alias(), "alias");
            }
            if (alias.candidates().isEmpty()) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "Alias " + alias.alias() + " 至少需要配置一个候选", "candidates");
            }

            for (LocalRuntimeDefinition.LocalCandidateDefinition cand : alias.candidates()) {
                if (!models.containsKey(cand.modelId())) {
                    throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "Alias " + alias.alias() + " 候选模型 " + cand.modelId() + " 不存在", "model_id");
                }
                if (cand.poolId() != null && !pools.containsKey(cand.poolId())) {
                    throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "Alias " + alias.alias() + " 候选引用的凭证池 " + cand.poolId() + " 不存在", "pool_id");
                }
                if (cand.poolId() != null) {
                    LocalRuntimeDefinition.LocalModelDefinition m = models.get(cand.modelId());
                    LocalRuntimeDefinition.LocalPoolDefinition p = pools.get(cand.poolId());
                    if (!m.providerId().equals(p.providerId())) {
                        throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "Alias " + alias.alias() + " 候选模型与凭证池的 provider 不一致");
                    }
                }
            }
        }
    }
}