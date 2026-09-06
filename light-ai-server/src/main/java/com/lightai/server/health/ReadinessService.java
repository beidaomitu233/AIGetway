package com.lightai.server.health;

import com.lightai.runtime.ports.AdapterRegistryPort;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import com.lightai.server.lifecycle.ServerLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 就绪检查服务（PRD 4.6.4.3，BE-056）：
 * /health/ready 同时要求 DATABASE、CAPACITY_STORE、CONFIG_SNAPSHOT 和 ADAPTER_REGISTRY 为 UP，
 * 并要求 accepting_requests=true。外部 Provider 暂时不可用不影响 Server readiness。
 * 公开 readiness 响应只暴露 status 与 time，不暴露内部拓扑细节。
 */
@Service
public class ReadinessService {

    private static final Logger log = LoggerFactory.getLogger(ReadinessService.class);

    private final ServerLifecycleService lifecycleService;
    private final ConfigSnapshotPort configSnapshotPort;
    private final AdapterRegistryPort adapterRegistryPort;

    // 可插拔/可测试的健康探针检查器
    private BooleanSupplier databaseHealthCheck = () -> true;
    private BooleanSupplier capacityStoreHealthCheck = () -> true;
    private BooleanSupplier adapterRegistryHealthCheck;

    private final AtomicBoolean databaseUp = new AtomicBoolean(true);
    private final AtomicBoolean capacityStoreUp = new AtomicBoolean(true);

    public ReadinessService(
            ServerLifecycleService lifecycleService,
            ObjectProvider<ConfigSnapshotPort> snapshotPortProvider,
            ObjectProvider<AdapterRegistryPort> adapterRegistryPortProvider) {
        this(lifecycleService,
                snapshotPortProvider != null ? snapshotPortProvider.getIfAvailable(ConfigSnapshotPort::empty) : ConfigSnapshotPort.empty(),
                adapterRegistryPortProvider != null ? adapterRegistryPortProvider.getIfAvailable() : null);
    }

    public ReadinessService(
            ServerLifecycleService lifecycleService,
            ConfigSnapshotPort configSnapshotPort,
            AdapterRegistryPort adapterRegistryPort) {
        this.lifecycleService = lifecycleService;
        this.configSnapshotPort = configSnapshotPort != null ? configSnapshotPort : ConfigSnapshotPort.empty();
        this.adapterRegistryPort = adapterRegistryPort;
    }

    public boolean isReady() {
        if (lifecycleService != null && !lifecycleService.isAcceptingRequests()) {
            return false;
        }
        if (!isDatabaseUp()) {
            return false;
        }
        if (!isCapacityStoreUp()) {
            return false;
        }
        if (!isConfigSnapshotUp()) {
            return false;
        }
        if (!isAdapterRegistryUp()) {
            return false;
        }
        return true;
    }

    public boolean isDatabaseUp() {
        return databaseUp.get() && databaseHealthCheck.getAsBoolean();
    }

    public boolean isCapacityStoreUp() {
        return capacityStoreUp.get() && capacityStoreHealthCheck.getAsBoolean();
    }

    public boolean isConfigSnapshotUp() {
        if (configSnapshotPort == null) {
            return false;
        }
        try {
            ConfigSnapshotPort.ActiveSnapshot snapshot = configSnapshotPort.active();
            return snapshot != null && snapshot.snapshotNo() > 0;
        } catch (Exception e) {
            log.warn("Config snapshot check failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean isAdapterRegistryUp() {
        if (adapterRegistryHealthCheck != null) {
            return adapterRegistryHealthCheck.getAsBoolean();
        }
        if (adapterRegistryPort == null) {
            return true;
        }
        return true;
    }

    public void setDatabaseUp(boolean up) {
        this.databaseUp.set(up);
    }

    public void setCapacityStoreUp(boolean up) {
        this.capacityStoreUp.set(up);
    }

    public void setDatabaseHealthCheck(BooleanSupplier check) {
        this.databaseHealthCheck = check != null ? check : () -> true;
    }

    public void setCapacityStoreHealthCheck(BooleanSupplier check) {
        this.capacityStoreHealthCheck = check != null ? check : () -> true;
    }

    public void setAdapterRegistryHealthCheck(BooleanSupplier check) {
        this.adapterRegistryHealthCheck = check;
    }

    /**
     * 内部/管理员查看的完整 Readiness 详情（PRD 4.6.4.3）
     */
    public Map<String, Object> getDiagnostics() {
        Map<String, Object> details = new LinkedHashMap<>();
        boolean ready = isReady();
        details.put("status", ready ? "UP" : "DOWN");
        details.put("time", Instant.now().toString());
        details.put("accepting_requests", lifecycleService != null && lifecycleService.isAcceptingRequests());
        details.put("database", isDatabaseUp() ? "UP" : "DOWN");
        details.put("capacity_store", isCapacityStoreUp() ? "UP" : "DOWN");
        details.put("config_snapshot", isConfigSnapshotUp() ? "UP" : "DOWN");
        details.put("adapter_registry", isAdapterRegistryUp() ? "UP" : "DOWN");
        if (configSnapshotPort != null) {
            try {
                details.put("active_snapshot_no", configSnapshotPort.active().snapshotNo());
            } catch (Exception ignored) {
            }
        }
        return details;
    }
}
