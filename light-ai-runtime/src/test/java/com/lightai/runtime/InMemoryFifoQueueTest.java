package com.lightai.runtime.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * FIFO 队列验收（BE-024）：队列满 QUEUE_FULL、FIFO 顺序取得、
 * 客户端取消、deadline 超时不再取得。
 */
class InMemoryFifoQueueTest {

    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    @Test
    void fullQueueRejectsWithQueueFull() {
        InMemoryFifoQueue queue = new InMemoryFifoQueue(2, null);
        UUID aliasId = UUID.randomUUID();
        queue.enqueue(aliasId, UUID.randomUUID(), NOW.plusSeconds(5).toEpochMilli(), NOW);
        queue.enqueue(aliasId, UUID.randomUUID(), NOW.plusSeconds(5).toEpochMilli(), NOW);
        assertThatThrownBy(() -> queue.enqueue(aliasId, UUID.randomUUID(),
                NOW.plusSeconds(5).toEpochMilli(), NOW))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.QUEUE_FULL);
    }

    @Test
    void fifoOrderPreservedOnAcquire() {
        InMemoryFifoQueue queue = new InMemoryFifoQueue(10, new InMemoryCapacityStore());
        UUID aliasId = UUID.randomUUID();
        List<UUID> tickets = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tickets.add(queue.enqueue(aliasId, UUID.randomUUID(),
                    NOW.plusSeconds(10).toEpochMilli(), NOW).ticketId());
        }
        queue.registerReserveFactory(aliasId, () ->
                new InMemoryCapacityStore().reserve(new CapacityStore.ReserveRequest(
                        aliasId, UUID.randomUUID(), UUID.randomUUID(), 1, 1)));
        var first = queue.tryAcquire(aliasId, NOW.plusSeconds(1));
        assertThat(first.acquired()).isTrue();
        assertThat(first.ticketId()).isEqualTo(tickets.get(0));
        assertThat(queue.queueLength(aliasId)).isEqualTo(2);
    }

    @Test
    void cancelledTicketNeverAcquired() {
        InMemoryFifoQueue queue = new InMemoryFifoQueue(10, new InMemoryCapacityStore());
        UUID aliasId = UUID.randomUUID();
        var ticket = queue.enqueue(aliasId, UUID.randomUUID(),
                NOW.plusSeconds(10).toEpochMilli(), NOW);
        assertThat(queue.cancel(ticket.ticketId())).isTrue();
        queue.registerReserveFactory(aliasId, () -> null);
        var result = queue.tryAcquire(aliasId, NOW.plusSeconds(1));
        assertThat(result.acquired()).isFalse();
    }

    @Test
    void expiredDeadlineNotAcquired() {
        InMemoryFifoQueue queue = new InMemoryFifoQueue(10, new InMemoryCapacityStore());
        UUID aliasId = UUID.randomUUID();
        queue.enqueue(aliasId, UUID.randomUUID(), NOW.plusSeconds(2).toEpochMilli(), NOW);
        queue.registerReserveFactory(aliasId, () ->
                new InMemoryCapacityStore().reserve(new CapacityStore.ReserveRequest(
                        aliasId, UUID.randomUUID(), UUID.randomUUID(), 1, 1)));
        // deadline 已过：队首被清理
        var result = queue.tryAcquire(aliasId, NOW.plusSeconds(10));
        assertThat(result.acquired()).isFalse();
        assertThat(queue.queueLength(aliasId)).isZero();
    }
}
