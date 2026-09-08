package com.lightai.server.runtime;

import com.lightai.admin.publish.ConfigPublishService;
import com.lightai.admin.publish.JdbcConfigSnapshotPortAdapter;
import com.lightai.client.publish.InstanceActivationCommand;
import com.lightai.client.publish.InstanceLoadReport;
import com.lightai.client.publish.InstancePrepareCommand;
import com.lightai.client.publish.RuntimeHeartbeatResponse;
import com.lightai.client.publish.RuntimeInstanceHeartbeat;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Standalone Server 本地运行时实例协调器（BE-041/042，4.5.2.4 两阶段加载）：
 * 1. 本地启动后周期性向 ConfigPublishService 发送心跳，注册自身为 ONLINE 实例；
 * 2. 接收 InstancePrepareCommand 时预载目标快照并上报 READY；
 * 3. 接收 InstanceActivationCommand 时刷新本地快照缓存并上报 LOADED，完成配置发布收敛；
 * 4. 容器停止时上报 acceptingRequests=false（DRAINING），实现优雅下线。
 */
public class ServerInstanceCoordinator implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ServerInstanceCoordinator.class);
    private static final long HEARTBEAT_INTERVAL_SECONDS = 3;

    private final ConfigPublishService publishService;
    private final ConfigSnapshotPort snapshotPort;
    private final UUID instanceId = UUID.randomUUID();
    private volatile long activeSnapshotNo = 0L;
    private volatile boolean running = false;
    private ScheduledExecutorService executor;

    public ServerInstanceCoordinator(ConfigPublishService publishService, ConfigSnapshotPort snapshotPort) {
        this.publishService = publishService;
        this.snapshotPort = snapshotPort;
    }

    public UUID instanceId() {
        return instanceId;
    }

    public long activeSnapshotNo() {
        return activeSnapshotNo;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (snapshotPort != null && snapshotPort.hasActiveSnapshot()) {
            try {
                activeSnapshotNo = snapshotPort.active().snapshotNo();
            } catch (Exception ignored) {
                activeSnapshotNo = 0L;
            }
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "light-ai-instance-coordinator");
            t.setDaemon(true);
            return t;
        });
        running = true;
        // 立即执行一次初始心跳注册
        executor.submit(this::heartbeatCycle);
        // 定期心跳维护
        executor.scheduleWithFixedDelay(this::heartbeatCycle,
                HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("ServerInstanceCoordinator 已启动，instanceId={}", instanceId);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            // 离线前上报 acceptingRequests=false (DRAINING)
            publishService.heartbeat(new RuntimeInstanceHeartbeat(
                    instanceId.toString(),
                    "STANDALONE_SERVER",
                    "0.1.0",
                    "light-ai-server",
                    "default",
                    List.of("1"),
                    List.of("OPENAI", "ANTHROPIC", "GEMINI", "DEEPSEEK"),
                    activeSnapshotNo,
                    false,
                    OffsetDateTime.now()
            ));
        } catch (Exception e) {
            log.debug("实例下线心跳上报已忽略 exception={}", e.getClass().getSimpleName());
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        log.info("ServerInstanceCoordinator 已停止，instanceId={}", instanceId);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    public void triggerHeartbeat() {
        if (running && executor != null) {
            executor.submit(this::heartbeatCycle);
        }
    }

    private void heartbeatCycle() {
        if (!running) {
            return;
        }
        try {
            RuntimeHeartbeatResponse response = publishService.heartbeat(new RuntimeInstanceHeartbeat(
                    instanceId.toString(),
                    "STANDALONE_SERVER",
                    "0.1.0",
                    "light-ai-server",
                    "default",
                    List.of("1"),
                    List.of("OPENAI", "ANTHROPIC", "GEMINI", "DEEPSEEK"),
                    activeSnapshotNo,
                    true,
                    OffsetDateTime.now()
            ));
            handleHeartbeatResponse(response);
        } catch (Exception e) {
            log.warn("运行时实例心跳异常 exception={}", e.getClass().getSimpleName());
        }
    }

    private void handleHeartbeatResponse(RuntimeHeartbeatResponse response) {
        if (response == null) {
            return;
        }
        InstancePrepareCommand prepare = response.prepareCommand();
        if (prepare != null) {
            log.info("收到发布准备命令: publishId={}, snapshotNo={}",
                    prepare.publishId(), prepare.snapshotNo());
            try {
                // 预载并校验快照
                publishService.snapshotContent(instanceId, prepare.snapshotNo(), prepare.contentChecksum());
                // 上报 READY
                publishService.applyReport(UUID.fromString(prepare.publishId()), instanceId,
                        new InstanceLoadReport(prepare.snapshotNo(), "READY", OffsetDateTime.now(),
                                0, 10L, null, null));
                log.info("已完成发布准备并上报 READY: publishId={}", prepare.publishId());
                // 快速触发下一轮心跳以立即接收激活指令
                if (executor != null && running) {
                    executor.submit(this::heartbeatCycle);
                }
            } catch (Exception e) {
                log.error("发布准备失败: publishId={}, exception={}",
                        prepare.publishId(), e.getClass().getSimpleName());
                try {
                    publishService.applyReport(UUID.fromString(prepare.publishId()), instanceId,
                            new InstanceLoadReport(prepare.snapshotNo(), "FAILED", OffsetDateTime.now(),
                                    0, 10L, "PREPARE_FAILED", e.getClass().getSimpleName()));
                } catch (Exception ex) {
                    log.error("上报准备失败异常 exception={}", ex.getClass().getSimpleName());
                }
            }
            return;
        }

        InstanceActivationCommand activation = response.activationCommand();
        if (activation != null) {
            log.info("收到发布激活命令: publishId={}, snapshotNo={}",
                    activation.publishId(), activation.snapshotNo());
            try {
                // 使本地快照失效重新加载
                if (snapshotPort instanceof JdbcConfigSnapshotPortAdapter adapter) {
                    adapter.invalidate();
                } else if (snapshotPort != null) {
                    snapshotPort.active();
                }
                activeSnapshotNo = activation.snapshotNo();
                // 上报 LOADED
                publishService.applyReport(UUID.fromString(activation.publishId()), instanceId,
                        new InstanceLoadReport(activation.snapshotNo(), "LOADED", OffsetDateTime.now(),
                                0, 10L, null, null));
                log.info("已完成快照激活并上报 LOADED: publishId={}, snapshotNo={}",
                        activation.publishId(), activeSnapshotNo);
            } catch (Exception e) {
                log.error("发布激活失败: publishId={}, exception={}",
                        activation.publishId(), e.getClass().getSimpleName());
                try {
                    publishService.applyReport(UUID.fromString(activation.publishId()), instanceId,
                            new InstanceLoadReport(activation.snapshotNo(), "FAILED", OffsetDateTime.now(),
                                    0, 10L, "ACTIVATION_FAILED", e.getClass().getSimpleName()));
                } catch (Exception ex) {
                    log.error("上报激活失败异常 exception={}", ex.getClass().getSimpleName());
                }
            }
        }
    }
}
