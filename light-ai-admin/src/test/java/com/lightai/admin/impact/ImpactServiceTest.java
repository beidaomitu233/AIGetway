package com.lightai.admin.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.impact.ImpactAnalysis;
import com.lightai.client.impact.ImpactReference;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 引用影响摘要票据验收（BE-010/BE-012）：
 * 确认后引用变化 → IMPACT_ANALYSIS_EXPIRED，页面重新展示影响。
 */
class ImpactServiceTest {

    private static final UUID ENTITY_ID = UUID.randomUUID();

    @Test
    void impactVersionIsStableForSameReferenceSet() {
        List<ImpactReference> references = List.of(
                new ImpactReference("credential_pool", UUID.randomUUID().toString(), "池A", "CHILD"));
        String first = ImpactService.computeVersion("provider", ENTITY_ID, references, List.of());
        String second = ImpactService.computeVersion("provider", ENTITY_ID, references, List.of());
        assertThat(first).isEqualTo(second).hasSize(64);
    }

    @Test
    void impactVersionChangesWhenReferencesChange() {
        List<ImpactReference> before = List.of(
                new ImpactReference("credential_pool", UUID.randomUUID().toString(), "池A", "CHILD"));
        List<ImpactReference> after = List.of(
                new ImpactReference("credential_pool", before.get(0).id(), "池A", "CHILD"),
                new ImpactReference("provider_model", UUID.randomUUID().toString(), "gpt4o", "CHILD"));
        String beforeVersion = ImpactService.computeVersion("provider", ENTITY_ID, before, List.of());
        String afterVersion = ImpactService.computeVersion("provider", ENTITY_ID, after, List.of());
        assertThat(beforeVersion).isNotEqualTo(afterVersion);
    }

    @Test
    void confirmMismatchExpires() {
        ImpactService service = new ImpactService(new com.lightai.storage.reference.JdbcConfigReferenceRepository());
        ImpactAnalysis fresh = new ImpactAnalysis("ticket-1", "provider", ENTITY_ID.toString(),
                List.of(new ImpactReference("credential_pool", UUID.randomUUID().toString(), "池A", "CHILD")),
                List.of(), false, List.of("credential_pool:池A"));
        assertThatCode(() -> service.verifyConfirmedImpact("ticket-1", fresh))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> service.verifyConfirmedImpact("ticket-stale", fresh))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.IMPACT_ANALYSIS_EXPIRED);
    }
}
