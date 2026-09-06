package com.lightai.server;

import com.lightai.runtime.chat.ChatPipeline;
import com.lightai.runtime.chat.ReliabilityBudgets;
import com.lightai.runtime.local.LocalLightAiClientFactory;
import com.lightai.runtime.ports.AccessTokenPort;
import com.lightai.runtime.ports.AdapterRegistryPort;
import com.lightai.runtime.ports.CapacityPort;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import com.lightai.runtime.ports.CredentialSecretPort;
import com.lightai.runtime.ports.RoutingPort;
import com.lightai.runtime.trace.InMemoryTraceStore;
import com.lightai.runtime.trace.TraceStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import java.util.Optional;

/** Standalone Server 可执行入口。生产配置通过宿主 Bean/Starter 注入，默认端口保持拒绝调用。 */
@SpringBootApplication
public class ServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }

    @Bean
    public io.micrometer.core.instrument.MeterRegistry meterRegistry() {
        return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    }

    @Bean
    public ConfigSnapshotPort configSnapshotPort() {
        return ConfigSnapshotPort.empty();
    }

    @Bean
    public AccessTokenPort accessTokenPort() {
        return AccessTokenPort.denyAll();
    }

    @Bean
    public CapacityPort capacityPort() {
        return CapacityPort.unlimited();
    }

    @Bean
    public com.lightai.runtime.chat.ModelsService modelsService(ConfigSnapshotPort snapshotPort) {
        return new com.lightai.runtime.chat.ModelsService(snapshotPort);
    }

    @Bean
    public ChatPipeline chatPipeline(ConfigSnapshotPort snapshotPort, AccessTokenPort accessTokenPort, CapacityPort capacityPort) {
        RoutingPort routing = (alias, request, estimated) -> new RoutingPort.RoutingResult(
                alias.enabledCandidates(), false, false);
        AdapterRegistryPort adapters = type -> Optional.empty();
        CredentialSecretPort credentials = CredentialSecretPort.inMemory(Map.of());
        TraceStore traces = new InMemoryTraceStore();
        return new ChatPipeline(snapshotPort, () -> Optional.empty(), routing, capacityPort,
                credentials, adapters, traces, () -> ReliabilityBudgets.DEFAULT, 120_000L);
    }
}
