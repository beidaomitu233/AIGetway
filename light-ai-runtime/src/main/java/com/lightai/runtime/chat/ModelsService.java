package com.lightai.runtime.chat;

import com.lightai.client.chat.UnifiedModelList;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.ports.AccessTokenPort;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import java.util.Comparator;
import java.util.List;

/**
 * 模型目录（BE-027，4.7.1.2）：从当前 ACTIVE 快照读取 ModelAlias，
 * 只保留 enabled、至少一个可用候选且位于 allowed_alias_ids 内的对象；
 * 实时容量耗尽与临时熔断不从列表移除；空目录仍返回 200；不创建 Trace。
 */
public class ModelsService {

    private final ConfigSnapshotPort snapshotPort;

    public ModelsService(ConfigSnapshotPort snapshotPort) {
        this.snapshotPort = snapshotPort;
    }

    public UnifiedModelList list(AccessTokenPort.Principal principal) {
        ConfigSnapshotPort.ActiveSnapshot snapshot = snapshotPort.active();
        List<UnifiedModelList.ModelSummary> items = snapshot.aliases().stream()
                .filter(ConfigSnapshotPort.AliasView::enabled)
                .filter(alias -> !alias.enabledCandidates().isEmpty())
                .filter(alias -> principal == null || principal.aliasAllowed(alias.alias()))
                .sorted(Comparator.comparing(ConfigSnapshotPort.AliasView::alias))
                .map(alias -> new UnifiedModelList.ModelSummary(
                        alias.alias(), "model", 0L, "light-ai",
                        new UnifiedModelList.LightAiModelInfo(
                                alias.displayName(), alias.supportsStream(),
                                alias.enabledCandidates().stream()
                                        .anyMatch(candidate -> !Boolean.FALSE.equals(candidate.supportSystem())),
                                null, null, null, null, null,
                                firstContextWindow(alias), firstMaxOutput(alias), null)))
                .toList();
        return new UnifiedModelList("list", items);
    }

    private static Long firstContextWindow(ConfigSnapshotPort.AliasView alias) {
        return alias.enabledCandidates().stream()
                .map(ConfigSnapshotPort.CandidateView::contextWindow)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    private static Long firstMaxOutput(ConfigSnapshotPort.AliasView alias) {
        return alias.enabledCandidates().stream()
                .map(ConfigSnapshotPort.CandidateView::maxOutputTokens)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    static LightAiException unsupportedPath() {
        return new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "V1.0 不提供该端点");
    }
}
