package com.lightai.server.health;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 健康与就绪检查接口（PRD 4.6.4.2，4.6.4.3，BE-056）：
 * GET /health/live：仅确认进程存活，返回 200。
 * GET /health/ready：严格校验存储、快照、Adapter 与 accepting_requests 状态；不暴露拓扑细节。
 */
@RestController
public class HealthController {

    private final ReadinessService readinessService;

    public HealthController(ReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    @GetMapping("/health/live")
    public ResponseEntity<Map<String, Object>> live() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "time", Instant.now().toString()
        ));
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        boolean ready = readinessService.isReady();
        HttpStatus status = ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(Map.of(
                "status", ready ? "UP" : "DOWN",
                "time", Instant.now().toString()
        ));
    }
}
