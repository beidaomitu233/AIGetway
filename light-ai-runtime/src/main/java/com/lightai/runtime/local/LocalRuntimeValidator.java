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
 *
 * V2 资源域：渠道直挂渠道 Key 与上游模型；候选绑定「渠道 + 上游模型」。
 */
public final class LocalRuntimeValidator {

    private LocalRuntimeValidator() {
    }

    public static void validate(LocalRuntimeDefinition def) {
        if (def == null) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "LocalRuntimeDefinition 不能为空");
        }

        Map<String, LocalRuntimeDefinition.LocalChannelDefinition> channels = def.channels().stream()
                .collect(Collectors.toMap(LocalRuntimeDefinition.LocalChannelDefinition::channelId, c -> c, (a, b) -> a));

        Map<String, LocalRuntimeDefinition.LocalUpstreamModelDefinition> models = def.models().stream()
                .collect(Collectors.toMap(LocalRuntimeDefinition.LocalUpstreamModelDefinition::upstreamModelId, m -> m, (a, b) -> a));

        // 校验上游模型与渠道关联
        for (LocalRuntimeDefinition.LocalUpstreamModelDefinition model : def.models()) {
            if (model.upstreamModelId() == null || model.upstreamModelId().isBlank()) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "upstreamModelId 不能为空", "upstream_model_id");
            }
            if (model.modelId() == null || model.modelId().isBlank()) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "modelId 不能为空", "model_id");
            }
            if (!channels.containsKey(model.channelId())) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "上游模型 " + model.modelId() + " 关联的渠道 " + model.channelId() + " 不存在", "channel_id");
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

        // 校验渠道 Key
        for (LocalRuntimeDefinition.LocalChannelCredentialDefinition cred : def.credentials()) {
            if (cred.channelId() != null && !channels.containsKey(cred.channelId())) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "渠道 Key " + cred.channelCredentialId() + " 关联的渠道 " + cred.channelId() + " 不存在", "channel_id");
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
                if (!models.containsKey(cand.upstreamModelId())) {
                    throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "Alias " + alias.alias() + " 候选上游模型 " + cand.upstreamModelId() + " 不存在", "upstream_model_id");
                }
                if (cand.channelId() != null) {
                    LocalRuntimeDefinition.LocalUpstreamModelDefinition m = models.get(cand.upstreamModelId());
                    if (!cand.channelId().equals(m.channelId())) {
                        throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "Alias " + alias.alias() + " 候选绑定的渠道与上游模型的渠道不一致");
                    }
                }
            }
        }
    }
}
