package com.lightai.server.lifecycle;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.ports.CapacityPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server 优雅停机与生命周期管理（PRD 4.6.4.5，BE-056）：
 * 1. 停机或收到信号时立即 accepting_requests = false，使 readiness=DOWN，新请求返回 503 SERVER_DRAINING；
 * 2. 追踪存量请求，等待其自然完成（最长 shutdown-timeout-seconds，默认 30s）；
 * 3. 超时后向未完成请求发送取消信号，并强制释放容量预占。
 */
@Service
public class ServerLifecycleService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ServerLifecycleService.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean acceptingRequests = new AtomicBoolean(true);
    private final Map<String, ActiveRequestHandle> activeRequests = new ConcurrentHashMap<>();

    private final int shutdownTimeoutSeconds;
    private final CapacityPort capacityPort;

    public ServerLifecycleService(
            @Value("${light-ai.server.shutdown-timeout-seconds:30}") int shutdownTimeoutSeconds,
            ObjectProvider<CapacityPort> capacityPortProvider) {
        this(shutdownTimeoutSeconds,
                capacityPortProvider != null ? capacityPortProvider.getIfAvailable(CapacityPort::unlimited) : CapacityPort.unlimited());
    }

    public ServerLifecycleService(int shutdownTimeoutSeconds, CapacityPort capacityPort) {
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
        this.capacityPort = capacityPort != null ? capacityPort : CapacityPort.unlimited();
    }

    public boolean isAcceptingRequests() {
        return acceptingRequests.get();
    }

    public void setAcceptingRequests(boolean accepting) {
        this.acceptingRequests.set(accepting);
        log.info("Server accepting_requests set to {}", accepting);
    }

    public int getActiveRequestCount() {
        return activeRequests.size();
    }

    public ActiveRequestHandle trackRequestStart(String traceId, CapacityPort.Reservation reservation, Runnable onCancel) {
        if (!isAcceptingRequests()) {
            throw new LightAiException(ErrorCode.SERVER_DRAINING, "Server 正在优雅停机摘流中，拒绝新请求");
        }
        String id = traceId != null ? traceId : java.util.UUID.randomUUID().toString();
        ActiveRequestHandle handle = new ActiveRequestHandle(id, reservation, onCancel);
        activeRequests.put(id, handle);
        return handle;
    }

    public void trackRequestEnd(String requestId) {
        if (requestId != null) {
            activeRequests.remove(requestId);
        }
    }

    /**
     * 启动摘流并等待存量请求退出。
     * @return true 若所有请求已正常退出，false 若超时并强制取消。
     */
    public boolean startDraining() {
        log.info("Starting server draining, setting accepting_requests=false. Active requests: {}", activeRequests.size());
        acceptingRequests.set(false);
        if (activeRequests.isEmpty()) {
            log.info("No active requests, draining complete immediately.");
            return true;
        }

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(shutdownTimeoutSeconds);
        while (!activeRequests.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (activeRequests.isEmpty()) {
            log.info("All active requests completed normally during draining.");
            return true;
        }

        log.warn("Draining timeout ({}s) reached with {} remaining requests. Cancelling and releasing reservations.",
                shutdownTimeoutSeconds, activeRequests.size());
        cancelRemainingRequests();
        return false;
    }

    private void cancelRemainingRequests() {
        for (Map.Entry<String, ActiveRequestHandle> entry : activeRequests.entrySet()) {
            ActiveRequestHandle handle = entry.getValue();
            try {
                handle.cancel();
            } catch (Exception e) {
                log.error("Failed to cancel active request {}: {}", entry.getKey(), e.getMessage());
            }
            if (handle.reservation() != null && capacityPort != null) {
                try {
                    capacityPort.release(handle.reservation().reservationId());
                } catch (Exception e) {
                    log.error("Failed to release reservation for request {}: {}", entry.getKey(), e.getMessage());
                }
            }
        }
        activeRequests.clear();
    }

    @Override
    public void start() {
        running.set(true);
        acceptingRequests.set(true);
        log.info("ServerLifecycleService started, accepting requests.");
    }

    @Override
    public void stop() {
        startDraining();
        running.set(false);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    public static class ActiveRequestHandle {
        private final String requestId;
        private final CapacityPort.Reservation reservation;
        private final Runnable onCancel;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        public ActiveRequestHandle(String requestId, CapacityPort.Reservation reservation, Runnable onCancel) {
            this.requestId = requestId;
            this.reservation = reservation;
            this.onCancel = onCancel;
        }

        public String requestId() {
            return requestId;
        }

        public CapacityPort.Reservation reservation() {
            return reservation;
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                if (onCancel != null) {
                    onCancel.run();
                }
            }
        }
    }
}
