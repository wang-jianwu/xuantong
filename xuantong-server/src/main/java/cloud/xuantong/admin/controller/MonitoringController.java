package cloud.xuantong.admin.controller;

import cloud.xuantong.core.service.ClientAccessTokenService;
import cloud.xuantong.core.v2.service.ConfigResourceServiceV2;
import cloud.xuantong.core.v2.service.ServiceInstanceRegistry;
import cloud.xuantong.core.v2.listener.BrokerClientSessionRegistry;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Context;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

@Controller
public class MonitoringController {
    @Inject private DataSource dataSource;
    @Inject private ConfigResourceServiceV2 configService;
    @Inject private ServiceInstanceRegistry instanceRegistry;
    @Inject private ClientAccessTokenService tokenService;
    @Inject private BrokerClientSessionRegistry sessionRegistry;
    @Inject("${solon.app.version:dev}") private String applicationVersion;

    @Mapping("/health")
    public Map<String, Object> health(Context context) {
        Map<String, Object> components = new LinkedHashMap<>();
        boolean databaseUp = databaseUp();
        components.put("database", databaseUp ? "UP" : "DOWN");
        components.put("configBroker", "UP");
        components.put("discoveryBroker", instanceRegistry.cleanupRunning() ? "UP" : "DOWN");
        boolean up = databaseUp && instanceRegistry.cleanupRunning();
        if (!up) context.status(503);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", up ? "UP" : "DOWN");
        result.put("version", applicationVersion);
        result.put("components", components);
        return result;
    }

    @Mapping("/metrics")
    public void metrics(Context context) {
        context.contentType("text/plain; version=0.0.4; charset=utf-8");
        Runtime runtime = Runtime.getRuntime();
        StringBuilder out = new StringBuilder();
        metric(out, "xuantong_config_publish_total", configService.publishTotal());
        metric(out, "xuantong_config_rollback_total", configService.rollbackTotal());
        metric(out, "xuantong_config_batch_total", configService.batchTotal());
        metric(out, "xuantong_config_batch_release_total", configService.batchReleaseTotal());
        metric(out, "xuantong_config_clients", sessionRegistry.logicalClientCount(
                BrokerClientSessionRegistry.Channel.CONFIG));
        metric(out, "xuantong_config_sessions", sessionRegistry.sessionCount(
                BrokerClientSessionRegistry.Channel.CONFIG));
        metric(out, "xuantong_discovery_sessions", sessionRegistry.sessionCount(
                BrokerClientSessionRegistry.Channel.DISCOVERY));
        metric(out, "xuantong_discovery_register_total", instanceRegistry.registerTotal());
        metric(out, "xuantong_discovery_heartbeat_total", instanceRegistry.heartbeatTotal());
        metric(out, "xuantong_discovery_deregister_total", instanceRegistry.deregisterTotal());
        metric(out, "xuantong_discovery_expired_total", instanceRegistry.expiredTotal());
        metric(out, "xuantong_discovery_instances", instanceRegistry.activeInstanceCount());
        metric(out, "xuantong_discovery_services", instanceRegistry.trackedServiceCount());
        metric(out, "xuantong_client_auth_success_total", tokenService.authSuccessTotal());
        metric(out, "xuantong_client_auth_failure_total", tokenService.authFailureTotal());
        metric(out, "xuantong_client_token_issued_total", tokenService.issuedTotal());
        metric(out, "xuantong_client_token_revoked_total", tokenService.revokedTotal());
        metric(out, "jvm_memory_used_bytes", runtime.totalMemory() - runtime.freeMemory());
        metric(out, "process_uptime_seconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000D);
        context.output(out.toString());
    }

    private boolean databaseUp() {
        try (Connection connection = dataSource.getConnection()) { return connection.isValid(2); }
        catch (Exception e) { return false; }
    }
    private void metric(StringBuilder out, String name, Number value) { out.append(name).append(' ').append(value).append('\n'); }
}
