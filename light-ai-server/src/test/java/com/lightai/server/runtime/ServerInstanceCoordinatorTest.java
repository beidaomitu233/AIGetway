package com.lightai.server.runtime;

import com.lightai.admin.publish.ConfigPublishService;
import com.lightai.client.publish.ConfigSnapshotContentView;
import com.lightai.client.publish.InstanceActivationCommand;
import com.lightai.client.publish.InstanceLoadReport;
import com.lightai.client.publish.InstancePrepareCommand;
import com.lightai.client.publish.PublishInstanceResultView;
import com.lightai.client.publish.RuntimeHeartbeatResponse;
import com.lightai.client.publish.RuntimeInstanceHeartbeat;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ServerInstanceCoordinatorTest {

    @Test
    void testHeartbeatAndTwoPhaseCoordination() {
        ConfigPublishService publishService = mock(ConfigPublishService.class);
        ConfigSnapshotPort snapshotPort = mock(ConfigSnapshotPort.class);

        UUID publishId = UUID.randomUUID();
        long targetSnapshotNo = 2L;
        String checksum = "abc123sha";

        AtomicReference<RuntimeInstanceHeartbeat> capturedHeartbeat = new AtomicReference<>();
        AtomicReference<InstanceLoadReport> reportedReady = new AtomicReference<>();
        AtomicReference<InstanceLoadReport> reportedLoaded = new AtomicReference<>();

        // 第一次心跳返回准备指令
        RuntimeHeartbeatResponse prepareResponse = new RuntimeHeartbeatResponse(
                OffsetDateTime.now(),
                1L,
                new InstancePrepareCommand(publishId.toString(), targetSnapshotNo, checksum, 1, OffsetDateTime.now().plusSeconds(30)),
                null
        );

        // 第二次心跳返回激活指令
        RuntimeHeartbeatResponse activateResponse = new RuntimeHeartbeatResponse(
                OffsetDateTime.now(),
                1L,
                null,
                new InstanceActivationCommand(publishId.toString(), targetSnapshotNo, checksum)
        );

        // 第三次心跳正常心跳
        RuntimeHeartbeatResponse idleResponse = new RuntimeHeartbeatResponse(
                OffsetDateTime.now(),
                targetSnapshotNo,
                null,
                null
        );

        when(publishService.heartbeat(any(RuntimeInstanceHeartbeat.class)))
                .thenAnswer(invocation -> {
                    RuntimeInstanceHeartbeat hb = invocation.getArgument(0);
                    capturedHeartbeat.set(hb);
                    if (reportedReady.get() == null) {
                        return prepareResponse;
                    } else if (reportedLoaded.get() == null) {
                        return activateResponse;
                    } else {
                        return idleResponse;
                    }
                });

        when(publishService.snapshotContent(any(UUID.class), eq(targetSnapshotNo), eq(checksum)))
                .thenReturn(new ConfigSnapshotContentView(targetSnapshotNo, 1, "CREATED", checksum, Map.of(), OffsetDateTime.now()));

        when(publishService.applyReport(eq(publishId), any(UUID.class), any(InstanceLoadReport.class)))
                .thenAnswer(invocation -> {
                    InstanceLoadReport report = invocation.getArgument(2);
                    if ("READY".equals(report.status())) {
                        reportedReady.set(report);
                    } else if ("LOADED".equals(report.status())) {
                        reportedLoaded.set(report);
                    }
                    return new PublishInstanceResultView(invocation.getArgument(1).toString(),
                            "STANDALONE_SERVER", "0.1.0", List.of("1"), List.of("OPENAI"),
                            1L, targetSnapshotNo, report.status(), 0, 10L, null, null, OffsetDateTime.now());
                });

        ServerInstanceCoordinator coordinator = new ServerInstanceCoordinator(publishService, snapshotPort);
        coordinator.start();
        assertTrue(coordinator.isRunning());

        // 等待异步心跳循环执行完毕
        long start = System.currentTimeMillis();
        while ((reportedLoaded.get() == null) && (System.currentTimeMillis() - start < 3000)) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {}
        }

        assertNotNull(capturedHeartbeat.get(), "心跳应已发送");
        assertEquals("STANDALONE_SERVER", capturedHeartbeat.get().runtimeMode());
        assertTrue(capturedHeartbeat.get().acceptingRequests());

        assertNotNull(reportedReady.get(), "应上报 READY");
        assertEquals("READY", reportedReady.get().status());
        assertEquals(targetSnapshotNo, reportedReady.get().targetSnapshotNo());

        assertNotNull(reportedLoaded.get(), "应上报 LOADED");
        assertEquals("LOADED", reportedLoaded.get().status());
        assertEquals(targetSnapshotNo, reportedLoaded.get().targetSnapshotNo());
        assertEquals(targetSnapshotNo, coordinator.activeSnapshotNo());

        coordinator.stop();
        assertFalse(coordinator.isRunning());
    }
}
