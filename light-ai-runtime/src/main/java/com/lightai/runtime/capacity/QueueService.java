package com.lightai.runtime.capacity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 队列服务端口（BE-024）：FIFO 按 Alias；队首取得容量后重新选完整路径；
 * deadline 受总超时约束；队列满返回 QUEUE_FULL，等待超时 QUEUE_TIMEOUT。
 * V1 不提供管理端删除单个 QueueEntry（4.3.5.1）。
 */
public interface QueueService {

    /** 容量不足时入队；队列满抛 QUEUE_FULL。 */
    QueueTicket enqueue(UUID aliasId, UUID traceId, long deadlineEpochMilli, Instant now);

    /** 取得队首容量；到达 deadline 未取得返回 empty（调用方映射 QUEUE_TIMEOUT）。 */
    AcquireResult tryAcquire(UUID aliasId, Instant now);

    /** 客户端取消等待（Trace CANCELLED 传播）。 */
    boolean cancel(UUID ticketId);

    /** 队列长度（管理查询只读）。 */
    long queueLength(UUID aliasId);

    record QueueTicket(UUID ticketId, UUID aliasId, UUID traceId,
                       Instant enqueuedAt, long deadlineEpochMilli) {
    }

    /** 取得结果：容量句柄或空（未到队首/未超时）。 */
    record AcquireResult(UUID ticketId, CapacityStore.ReservationHandle handle) {

        public boolean acquired() {
            return handle != null;
        }
    }
}
