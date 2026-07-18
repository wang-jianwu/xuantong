package cloud.xuantong.server.admin.controller;

import cloud.xuantong.security.service.ClientAccessTokenService;
import cloud.xuantong.server.state.management.ConfigStateManagementService;
import cloud.xuantong.server.state.management.RegistryStateManagementService;
import cloud.xuantong.server.state.RegistryStatePlaneProperties;
import cloud.xuantong.registry.state.RegistryOverview;
import cloud.xuantong.server.state.ConfigStatePlaneProperties;
import cloud.xuantong.server.state.ControlStatePlaneRuntime;
import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime;
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
    @Inject private ConfigStateManagementService configStateManagementService;
    @Inject private RegistryStateManagementService registryState;
    @Inject private RegistryStatePlaneProperties registryStateProperties;
    @Inject private ClientAccessTokenService tokenService;
    @Inject private ControlPlaneGatewayRuntime gatewayRuntime;
    @Inject private ConfigStatePlaneProperties configStateProperties;
    @Inject private ControlStatePlaneRuntime configStateRuntime;
    @Inject("${solon.app.version:dev}") private String applicationVersion;

    @Mapping("/health")
    public Map<String, Object> health(Context context) {
        Map<String, Object> components = new LinkedHashMap<>();
        boolean databaseUp = databaseUp();
        components.put("database", databaseUp ? "UP" : "DOWN");
        boolean gatewayUp = !gatewayRuntime.isDraining();
        components.put("controlPlaneGateway", gatewayUp ? "UP" : "DOWN");
        boolean configStateUp = !configStateProperties.isEnabled()
                || configStateRuntime.isRunning();
        components.put("configStatePlane", configStateProperties.isEnabled()
                ? (configStateUp ? "UP" : "DOWN") : "DISABLED");
        boolean registryStateUp = registryStateUp();
        components.put("registryStatePlane", registryStateProperties.isEnabled()
                ? (registryStateUp ? "UP" : "DOWN") : "DISABLED");
        boolean up = databaseUp && gatewayUp && configStateUp && registryStateUp;
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
        RegistryOverview registryOverview = registryOverview();
        StringBuilder out = new StringBuilder();
        metric(out, "xuantong_config_publish_committed_total",
                configStateManagementService.publishCommittedTotal());
        metric(out, "xuantong_config_rollback_committed_total",
                configStateManagementService.rollbackCommittedTotal());
        metric(out, "xuantong_config_rollout_started_committed_total",
                configStateManagementService.rolloutStartedCommittedTotal());
        metric(out, "xuantong_config_rollout_promoted_committed_total",
                configStateManagementService.rolloutPromotedCommittedTotal());
        metric(out, "xuantong_config_rollout_aborted_committed_total",
                configStateManagementService.rolloutAbortedCommittedTotal());
        metric(out, "xuantong_config_commit_unknown_total",
                configStateManagementService.commitUnknownTotal());
        metric(out, "xuantong_config_projection_failure_total",
                configStateManagementService.projectionFailureTotal());
        metric(out, "xuantong_config_projection_recovered_total",
                configStateManagementService.projectionRecoveredTotal());
        metric(out, "xuantong_config_state_enabled", configStateProperties.isEnabled() ? 1 : 0);
        metric(out, "xuantong_config_state_running", configStateRuntime.isRunning() ? 1 : 0);
        metric(out, "xuantong_control_plane_clients", gatewayRuntime.logicalClients());
        metric(out, "xuantong_control_plane_sessions", gatewayRuntime.activeSessions());
        metric(out, "xuantong_control_plane_in_flight_requests",
                gatewayRuntime.inFlightRequests());
        metric(out, "xuantong_control_plane_subscriptions",
                gatewayRuntime.activeSubscriptions());
        metric(out, "xuantong_control_plane_watch_pending_acknowledgements",
                gatewayRuntime.pendingWatchAcknowledgements());
        metric(out, "xuantong_control_plane_watch_acknowledged_total",
                gatewayRuntime.watchAcknowledgedTotal());
        metric(out, "xuantong_control_plane_watch_poll_total",
                gatewayRuntime.watchPollTotal());
        metric(out, "xuantong_control_plane_watch_idle_poll_total",
                gatewayRuntime.watchIdlePollTotal());
        metric(out, "xuantong_control_plane_watch_reply_total",
                gatewayRuntime.watchReplyTotal());
        metric(out, "xuantong_control_plane_watch_ack_latency_milliseconds_total",
                gatewayRuntime.watchAckLatencyMsTotal());
        metric(out, "xuantong_control_plane_watch_ack_latency_milliseconds_max",
                gatewayRuntime.watchAckLatencyMsMax());
        metric(out, "xuantong_control_plane_watch_ack_timeout_closed_total",
                gatewayRuntime.watchAckTimeoutClosedTotal());
        metric(out, "xuantong_control_plane_watch_stream_rotated_total",
                gatewayRuntime.watchStreamRotatedTotal());
        metric(out, "xuantong_control_plane_state_callback_rejected_total",
                gatewayRuntime.stateCallbackRejectedTotal());
        metric(out, "xuantong_control_plane_work_active_threads",
                gatewayRuntime.workActiveThreads());
        metric(out, "xuantong_control_plane_work_queue_depth",
                gatewayRuntime.workQueueDepth());
        metric(out, "xuantong_control_plane_state_callback_active_threads",
                gatewayRuntime.stateCallbackActiveThreads());
        metric(out, "xuantong_control_plane_state_callback_queue_depth",
                gatewayRuntime.stateCallbackQueueDepth());
        metric(out, "xuantong_control_plane_callback_scheduled_tasks",
                gatewayRuntime.callbackScheduledTaskCount());
        metric(out, "xuantong_control_plane_gateway_session_limit_rejected_total",
                gatewayRuntime.gatewaySessionLimitRejectedTotal());
        metric(out, "xuantong_control_plane_tenant_session_limit_rejected_total",
                gatewayRuntime.tenantSessionLimitRejectedTotal());
        metric(out, "xuantong_control_plane_credential_session_limit_rejected_total",
                gatewayRuntime.credentialSessionLimitRejectedTotal());
        metric(out, "xuantong_control_plane_tenant_subscription_limit_rejected_total",
                gatewayRuntime.tenantSubscriptionLimitRejectedTotal());
        metric(out, "xuantong_control_plane_tenant_request_rate_limited_total",
                gatewayRuntime.tenantRequestRateLimitedTotal());
        metric(out, "xuantong_control_plane_authentication_rate_limited_total",
                gatewayRuntime.authenticationRateLimitedTotal());
        metric(out, "xuantong_control_plane_hello_timeout_closed_total",
                gatewayRuntime.helloTimeoutClosedTotal());
        metric(out, "xuantong_registry_state_enabled",
                registryStateProperties.isEnabled() ? 1 : 0);
        metric(out, "xuantong_registry_state_running", registryState.available() ? 1 : 0);
        metric(out, "xuantong_discovery_instances",
                registryOverview == null ? 0 : registryOverview.activeInstanceCount());
        metric(out, "xuantong_discovery_services",
                registryOverview == null ? 0 : registryOverview.activeServiceCount());
        metric(out, "xuantong_registry_revision",
                registryOverview == null ? 0 : registryOverview.registryRevision());
        metric(out, "xuantong_client_auth_success_total", tokenService.authSuccessTotal());
        metric(out, "xuantong_client_auth_failure_total", tokenService.authFailureTotal());
        metric(out, "xuantong_client_token_issued_total", tokenService.issuedTotal());
        metric(out, "xuantong_client_token_revoked_total", tokenService.revokedTotal());
        metric(out, "jvm_memory_used_bytes", runtime.totalMemory() - runtime.freeMemory());
        metric(out, "process_uptime_seconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000D);
        context.output(out.toString());
    }

    private boolean registryStateUp() {
        if (!registryStateProperties.isEnabled()) {
            return true;
        }
        try {
            registryState.overview();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private RegistryOverview registryOverview() {
        if (!registryState.available()) {
            return null;
        }
        try {
            return registryState.overview();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean databaseUp() {
        try (Connection connection = dataSource.getConnection()) { return connection.isValid(2); }
        catch (Exception e) { return false; }
    }
    private void metric(StringBuilder out, String name, Number value) { out.append(name).append(' ').append(value).append('\n'); }
}
