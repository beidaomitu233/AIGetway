package com.lightai.client.alias;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 候选与重排命令校验（BE-017/018）：priority/weight 边界、
 * 重排完整集合与无重复 id、显式 version。
 */
class CandidateCommandTest {

    @Test
    void candidateBoundsEnforced() {
        RouteCandidateSaveCommand valid = new RouteCandidateSaveCommand(
                UUID.randomUUID(), UUID.randomUUID(), 100, 1, true, null);
        assertThatCode(valid::validateForCreate).doesNotThrowAnyException();

        RouteCandidateSaveCommand badPriority = new RouteCandidateSaveCommand(
                UUID.randomUUID(), UUID.randomUUID(), 0, 50, true, null);
        assertThatThrownBy(badPriority::validateForCreate).isInstanceOf(IllegalArgumentException.class);

        RouteCandidateSaveCommand badWeight = new RouteCandidateSaveCommand(
                UUID.randomUUID(), UUID.randomUUID(), 50, 101, true, null);
        assertThatThrownBy(badWeight::validateForCreate).isInstanceOf(IllegalArgumentException.class);

        RouteCandidateSaveCommand missingRefs = new RouteCandidateSaveCommand(
                null, null, 50, 50, true, null);
        assertThatThrownBy(missingRefs::validateForCreate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reorderRequiresCompleteDistinctVersionedItems() {
        ReorderCommand empty = new ReorderCommand(List.of());
        assertThatThrownBy(empty::validate).isInstanceOf(IllegalArgumentException.class);

        String id = UUID.randomUUID().toString();
        ReorderCommand duplicated = new ReorderCommand(List.of(
                new ReorderCommand.ReorderItem(id, 1, 1L),
                new ReorderCommand.ReorderItem(id, 2, 1L)));
        assertThatThrownBy(duplicated::validate).isInstanceOf(IllegalArgumentException.class);

        ReorderCommand missingVersion = new ReorderCommand(List.of(
                new ReorderCommand.ReorderItem(id, 1, null)));
        assertThatThrownBy(missingVersion::validate).isInstanceOf(IllegalArgumentException.class);

        ReorderCommand valid = new ReorderCommand(List.of(
                new ReorderCommand.ReorderItem(id, 10, 3L),
                new ReorderCommand.ReorderItem(UUID.randomUUID().toString(), 20, 1L)));
        assertThatCode(valid::validate).doesNotThrowAnyException();
    }

    @Test
    void aliasNamingRules() {
        assertThatCode(() -> new ModelAliasSaveCommand("gpt-4o", "GPT 4o", null, true, null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new ModelAliasSaveCommand("gpt 4o!", "GPT 4o", null, true, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelAliasSaveCommand("a", "GPT 4o", null, true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
