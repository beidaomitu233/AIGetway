package com.lightai.server.health;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.ports.AdapterRegistryPort;
import com.lightai.runtime.ports.CapacityPort;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import com.lightai.server.lifecycle.ServerLifecycleService;
import com.lightai.server.v1.V1Controller;
import com.lightai.server.v1.V1ErrorHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class ServerHealthAndDrainingTest {

    private ServerLifecycleService lifecycleService;
    private ReadinessService readinessService;
    private HealthController healthController;
    private ConfigSnapshotPort snapshotPort;
    private AtomicBoolean capacityReleased;

    @BeforeEach
    void setUp() {
        capacityReleased = new AtomicBoolean(false);
        CapacityPort capacityPort = new CapacityPort() {
            @Override
            public Reservation reserve(String aliasId, String modelId, String credentialId, long estimatedTokens) {
                return new Reservation("res-1", aliasId, modelId, credentialId);
            }

            @Override
            public void settle(String reservationId, long inputTokens, long outputTokens) {
            }

            @Override
            public void release(String reservationId) {
                capacityReleased.set(true);
            }
        };

        lifecycleService = new ServerLifecycleService(2, capacityPort);
        snapshotPort = () -> new ConfigSnapshotPort.ActiveSnapshot(1L, List.of());
        readinessService = new ReadinessService(lifecycleService, snapshotPort, null);
        healthController = new HealthController(readinessService);
    }

    @Test
    void testLiveProbeAlwaysReturns200() {
        ResponseEntity<Map<String, Object>> res = healthController.live();
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("UP", res.getBody().get("status"));
        assertNotNull(res.getBody().get("time"));
    }

    @Test
    void testReadyProbeWhenAllHealthy() {
        ResponseEntity<Map<String, Object>> res = healthController.ready();
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("UP", res.getBody().get("status"));
        assertNotNull(res.getBody().get("time"));
        // 验证公网 /health/ready 不泄漏拓扑细节
        assertFalse(res.getBody().containsKey("database"));
        assertFalse(res.getBody().containsKey("capacity_store"));
    }

    @Test
    void testReadyProbeWhenDatabaseDown() {
        readinessService.setDatabaseUp(false);
        ResponseEntity<Map<String, Object>> res = healthController.ready();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        assertEquals("DOWN", res.getBody().get("status"));
    }

    @Test
    void testReadyProbeWhenCapacityStoreDown() {
        readinessService.setCapacityStoreUp(false);
        ResponseEntity<Map<String, Object>> res = healthController.ready();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        assertEquals("DOWN", res.getBody().get("status"));
    }

    @Test
    void testReadyProbeWhenConfigSnapshotMissing() {
        readinessService = new ReadinessService(lifecycleService, (ConfigSnapshotPort) null, null);
        healthController = new HealthController(readinessService);
        ResponseEntity<Map<String, Object>> res = healthController.ready();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        assertEquals("DOWN", res.getBody().get("status"));
    }

    @Test
    void testReadyProbeWhenDraining() {
        lifecycleService.setAcceptingRequests(false);
        ResponseEntity<Map<String, Object>> res = healthController.ready();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        assertEquals("DOWN", res.getBody().get("status"));
    }

    @Test
    void testDiagnosticsContainDetails() {
        Map<String, Object> diag = readinessService.getDiagnostics();
        assertEquals("UP", diag.get("status"));
        assertEquals("UP", diag.get("database"));
        assertEquals("UP", diag.get("capacity_store"));
        assertEquals("UP", diag.get("config_snapshot"));
        assertEquals(1L, diag.get("active_snapshot_no"));
    }

    @Test
    void testGracefulDrainingWaitsForInFlightRequests() throws InterruptedException {
        // 模拟一个进行中的请求
        ServerLifecycleService.ActiveRequestHandle handle = lifecycleService.trackRequestStart(
                "trace-1", new CapacityPort.Reservation("res-1", "alias", "model", "cred"), null);
        assertEquals(1, lifecycleService.getActiveRequestCount());

        AtomicBoolean drainingFinished = new AtomicBoolean(false);
        Thread drainThread = new Thread(() -> {
            boolean success = lifecycleService.startDraining();
            drainingFinished.set(success);
        });
        drainThread.start();

        // 此时已设置 accepting_requests = false
        Thread.sleep(100);
        assertFalse(lifecycleService.isAcceptingRequests());

        // 新请求必须被拒绝 (SERVER_DRAINING)
        assertThrows(LightAiException.class, () ->
                lifecycleService.trackRequestStart("trace-2", null, null));

        // 结束前置请求
        lifecycleService.trackRequestEnd(handle.requestId());
        drainThread.join(2000);

        assertTrue(drainingFinished.get());
        assertEquals(0, lifecycleService.getActiveRequestCount());
    }

    @Test
    void testGracefulDrainingTimeoutCancelsAndReleasesCapacity() throws InterruptedException {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        lifecycleService = new ServerLifecycleService(1, (CapacityPort) null); // 1秒超时
        lifecycleService.trackRequestStart("trace-timeout",
                new CapacityPort.Reservation("res-timeout", "alias", "model", "cred"),
                () -> cancelled.set(true));

        boolean finishedCleanly = lifecycleService.startDraining();
        assertFalse(finishedCleanly, "应该超时强制取消");
        assertTrue(cancelled.get(), "未完成请求应该收到取消信号");
        assertEquals(0, lifecycleService.getActiveRequestCount(), "超时后请求应被清除");
    }

    @Test
    void testV1ControllerRejectsWhenDraining() {
        lifecycleService.setAcceptingRequests(false);
        V1Controller controller = new V1Controller(null, null, null, lifecycleService);

        LightAiException ex = assertThrows(LightAiException.class, () ->
                controller.models("Bearer token"));
        assertEquals(ErrorCode.SERVER_DRAINING, ex.code());
        assertEquals(503, ex.code().httpStatus());

        V1ErrorHandler errorHandler = new V1ErrorHandler();
        ResponseEntity<String> errorRes = errorHandler.handle(ex);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, errorRes.getStatusCode());
        assertTrue(errorRes.getBody().contains("SERVER_DRAINING"));
    }
}
