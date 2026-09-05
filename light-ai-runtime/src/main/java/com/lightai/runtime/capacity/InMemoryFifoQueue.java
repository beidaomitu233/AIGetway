package com.lightai.runtime.capacity;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 进程内 FIFO 队列（BE-024）：按 Alias 独立队列；队首取得容量后由调用方
 * 重新选完整路径（唤醒不沿用旧路径）；deadline 到期标记 TIMEOUT；
 * 队列上限取生效策略最小允许值，满则 QUEUE_FULL。
 */
public class InMemoryFifoQueue implements QueueService {

    /** 入队上限（部署/策略合成，最小允许值）。 */
    private final int maxQueueSize;
    private final CapacityStore capacityStore;
    private final Map<UUID, Deque<Waiting>> queues = new ConcurrentHashMap<>();
    private final Map<UUID, Waiting> byTicket = new ConcurrentHashMap<>();

    /** 每别名的预占工厂：由调用方提供重新选路 + 预占逻辑。 */
    private final Map<UUID, Supplier<CapacityStore.ReservationHandle>> reserveFactories =
            new ConcurrentHashMap<>();

    public InMemoryFifoQueue(int maxQueueSize, CapacityStore capacityStore) {
        this.maxQueueSize = maxQueueSize;
        this.capacityStore = capacityStore;
    }

    public void registerReserveFactory(UUID aliasId,
                                       Supplier<CapacityStore.ReservationHandle> factory) {
        reserveFactories.put(aliasId, factory);
    }

    @Override
    public QueueTicket enqueue(UUID aliasId, UUID traceId, long deadlineEpochMilli, Instant now) {
        Deque<Waiting> queue = queues.computeIfAbsent(aliasId, missing -> new ArrayDeque<>());
        synchronized (queue) {
            if (queue.size() >= maxQueueSize) {
                throw new LightAiException(ErrorCode.QUEUE_FULL, "当前 Alias 队列已满");
            }
            if (deadlineEpochMilli <= now.toEpochMilli()) {
                throw new LightAiException(ErrorCode.QUEUE_TIMEOUT, "等待截止时间已过");
            }
            Waiting waiting = new Waiting(UUID.randomUUID(), aliasId, traceId, now,
                    deadlineEpochMilli);
            queue.addLast(waiting);
            byTicket.put(waiting.ticketId, waiting);
            return new QueueTicket(waiting.ticketId, aliasId, traceId, now, deadlineEpochMilli);
        }
    }

    @Override
    public AcquireResult tryAcquire(UUID aliasId, Instant now) {
        Deque<Waiting> queue = queues.get(aliasId);
        if (queue == null || queue.isEmpty()) {
            return new AcquireResult(null, null);
        }
        synchronized (queue) {
            // 先清理超时与取消项
            queue.removeIf(waiting -> waiting.timedOut(now) || waiting.cancelled);
            Waiting head = queue.peekFirst();
            if (head == null) {
                return new AcquireResult(null, null);
            }
            if (head.timedOut(now)) {
                queue.pollFirst();
                return new AcquireResult(null, null);
            }
            Supplier<CapacityStore.ReservationHandle> factory =
                    reserveFactories.get(aliasId);
            if (factory == null) {
                return new AcquireResult(null, null);
            }
            try {
                CapacityStore.ReservationHandle handle = factory.get();
                queue.pollFirst();
                return new AcquireResult(head.ticketId, handle);
            } catch (LightAiException e) {
                // 仍无容量：保持队首等待
                return new AcquireResult(null, null);
            }
        }
    }

    @Override
    public boolean cancel(UUID ticketId) {
        Waiting waiting = byTicket.remove(ticketId);
        if (waiting == null || waiting.cancelled) {
            return false;
        }
        waiting.cancelled = true;
        return true;
    }

    @Override
    public long queueLength(UUID aliasId) {
        Deque<Waiting> queue = queues.get(aliasId);
        return queue == null ? 0 : queue.size();
    }

    /** 等待项：deadline 由读取时判定（TIMEOUT 状态在查询时收敛）。 */
    private static final class Waiting {
        private final UUID ticketId;
        private final UUID aliasId;
        private final UUID traceId;
        private final Instant enqueuedAt;
        private final long deadlineEpochMilli;
        private volatile boolean cancelled;

        Waiting(UUID ticketId, UUID aliasId, UUID traceId, Instant enqueuedAt,
                long deadlineEpochMilli) {
            this.ticketId = ticketId;
            this.aliasId = aliasId;
            this.traceId = traceId;
            this.enqueuedAt = enqueuedAt;
            this.deadlineEpochMilli = deadlineEpochMilli;
        }

        boolean timedOut(Instant now) {
            return now.toEpochMilli() > deadlineEpochMilli;
        }
    }
}
