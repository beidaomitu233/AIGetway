package com.lightai.runtime.circuit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 熔断共享状态存储端口（BE-023）。
 * 语义：结果记录与状态迁移原子；HALF_OPEN 探测名额有限；人工命令以
 * expected_state_version CAS 应用（C-013）。事件由实现以 event_key 幂等回调。
 */
public interface CircuitStateStore {

    /** 当前快照（含惰性窗口迁移：OPEN 到期进入 HALF_OPEN）。 */
    CircuitSnapshot snapshot(CircuitKey key, CircuitPolicy policy, Instant now);

    /**
     * 记录一次真实调用结果（Attempt 结束）：429 不计失败由分类传入。
     * 状态迁移（CLOSED→OPEN、HALF_OPEN 失败→OPEN）追加事件。
     */
    CircuitSnapshot recordResult(CircuitKey key, CircuitPolicy policy,
                                 boolean success, boolean throttled, Instant now);

    /** HALF_OPEN 探测名额；无名额返回 empty（不阻塞）。 */
    Optional<ProbeSlot> tryAcquireProbe(CircuitKey key, CircuitPolicy policy, Instant now);

    void releaseProbe(ProbeSlot slot, CircuitKey key, boolean success, Instant now);

    /**
     * 人工命令 CAS 应用：expected 与当前 state_version 不符返回 empty
     * （调用方映射 CIRCUIT_STATE_CONFLICT + current_state_version）。
     */
    Optional<CircuitSnapshot> applyManualCommand(CircuitKey key, CircuitPolicy policy,
                                                 ManualCommand command, Instant now);

    /** 列出当前全部熔断状态（管理查询）。 */
    List<CircuitSnapshot> all();

    record ProbeSlot(UUID slotId, CircuitKey key) {
    }

    record ManualCommand(UUID commandId, Action action, long expectedStateVersion,
                         String reason, Integer openSeconds, Instant issuedAt) {

        public enum Action {
            MANUAL_OPEN,
            MANUAL_RECOVER
        }
    }

    /** 状态迁移事件（event_key 幂等，跨存储重放去重）。 */
    record CircuitEventPayload(String eventKey, CircuitKey key, String fromState, String toState,
                               String triggerType, UUID commandId, String errorCode,
                               String reason, Instant occurredAt) {
    }

    /** 事件回调（SQL 侧记录由装配层实现；BE-P06 入库）。 */
    interface EventListener {
        void onEvent(CircuitEventPayload payload);
    }
}
