package cloud.xuantong.server.admin.controller;

import cloud.xuantong.common.metrics.FixedLatencyHistogram;
import cloud.xuantong.security.service.ClientAccessTokenService;
import cloud.xuantong.server.state.management.ConfigStateManagementService;
import cloud.xuantong.server.state.management.RegistryStateManagementService;
import cloud.xuantong.server.state.RegistryStatePlaneProperties;
import cloud.xuantong.registry.state.RegistryOverview;
import cloud.xuantong.server.state.ConfigStatePlaneProperties;
import cloud.xuantong.server.state.ControlStatePlaneRuntime;
import cloud.xuantong.server.state.StateStorageTelemetry;
import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime;
import cloud.xuantong.server.cluster.GatewayClusterProperties;
import cloud.xuantong.server.cluster.GatewayClusterView;
import cloud.xuantong.server.cluster.GatewayClusterViewProvider;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Context;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
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
    @Inject private GatewayClusterProperties gatewayClusterProperties;
    @Inject private GatewayClusterViewProvider gatewayClusterViewProvider;
    @Inject private ConfigStatePlaneProperties configStateProperties;
    @Inject private ControlStatePlaneRuntime configStateRuntime;
    @Inject private StateStorageTelemetry stateStorageTelemetry;
    @Inject("${solon.app.version:dev}") private String applicationVersion;

    @Mapping("/health")
    public Map<String, Object> health(Context context) {
        Map<String, Object> components = new LinkedHashMap<>();
        boolean databaseUp = databaseUp();
        components.put("database", databaseUp ? "UP" : "DOWN");
        boolean gatewayUp = !gatewayRuntime.isDraining();
        components.put("controlPlaneGateway", gatewayUp ? "UP" : "DOWN");
        GatewayClusterView clusterView = gatewayClusterViewProvider.currentView();
        boolean clusterCoordinationUp = !gatewayClusterProperties.isCoordinationEnabled()
                || clusterView.clusterAggregated()
                && clusterView.localQuotaAllocation().admissionsEnabled()
                && clusterView.localQuotaAllocation().leaseValid(System.currentTimeMillis());
        components.put("gatewayClusterCoordination",
                gatewayClusterProperties.isCoordinationEnabled()
                        ? (clusterCoordinationUp ? "UP" : "DOWN") : "DISABLED");
        boolean configStateUp = !configStateProperties.isEnabled()
                || configStateRuntime.isRunning();
        components.put("configStatePlane", configStateProperties.isEnabled()
                ? (configStateUp ? "UP" : "DOWN") : "DISABLED");
        StateStorageTelemetry.Health stateStorage = stateStorageTelemetry.health();
        boolean stateStorageUp = !configStateProperties.isEnabled()
                || stateStorage.accessible()
                && stateStorage.storageFreeSpaceAboveMinimum();
        components.put("stateStorage", configStateProperties.isEnabled()
                ? (stateStorageUp ? "UP" : "DOWN") : "DISABLED");
        boolean registryStateUp = registryStateUp();
        components.put("registryStatePlane", registryStateProperties.isEnabled()
                ? (registryStateUp ? "UP" : "DOWN") : "DISABLED");
        boolean up = databaseUp && gatewayUp && clusterCoordinationUp
                && configStateUp && stateStorageUp && registryStateUp;
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
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        StateStorageTelemetry.Snapshot stateStorage = stateStorageTelemetry.snapshot();
        FixedLatencyHistogram.Snapshot requestLatency =
                gatewayRuntime.requestLatencySnapshot();
        FixedLatencyHistogram.Snapshot watchAckLatency =
                gatewayRuntime.watchAckLatencySnapshot();
        FixedLatencyHistogram.Snapshot stateApplyLatency =
                configStateRuntime.stateApplyLatencySnapshot();
        RegistryOverview registryOverview = registryOverview();
        GatewayClusterView clusterView = gatewayClusterViewProvider.currentView();
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
        histogram(out, "xuantong_control_plane_watch_ack_duration_seconds",
                watchAckLatency);
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
        metric(out, "xuantong_control_plane_work_queue_depth_peak",
                gatewayRuntime.peakWorkQueueDepth());
        metric(out, "xuantong_control_plane_state_callback_active_threads",
                gatewayRuntime.stateCallbackActiveThreads());
        metric(out, "xuantong_control_plane_state_callback_queue_depth",
                gatewayRuntime.stateCallbackQueueDepth());
        metric(out, "xuantong_control_plane_state_callback_queue_depth_peak",
                gatewayRuntime.peakStateCallbackQueueDepth());
        metric(out, "xuantong_control_plane_callback_scheduled_tasks",
                gatewayRuntime.callbackScheduledTaskCount());
        metric(out, "xuantong_control_plane_callback_scheduled_tasks_peak",
                gatewayRuntime.peakCallbackScheduledTaskCount());
        metric(out, "xuantong_control_plane_requests_accepted_total",
                gatewayRuntime.requestAcceptedTotal());
        metric(out, "xuantong_control_plane_requests_completed_total",
                gatewayRuntime.requestCompletedTotal());
        metric(out, "xuantong_control_plane_requests_overloaded_rejected_total",
                gatewayRuntime.requestOverloadedRejectedTotal());
        metric(out, "xuantong_control_plane_requests_draining_rejected_total",
                gatewayRuntime.requestDrainingRejectedTotal());
        histogram(out, "xuantong_control_plane_request_duration_seconds",
                requestLatency);
        metric(out, "xuantong_control_plane_late_replies_dropped_total",
                gatewayRuntime.lateReplyDroppedTotal());
        metric(out, "xuantong_control_plane_in_flight_requests_peak",
                gatewayRuntime.peakInFlightRequests());
        metric(out, "xuantong_control_plane_sessions_opened_total",
                gatewayRuntime.sessionOpenedTotal());
        metric(out, "xuantong_control_plane_sessions_closed_total",
                gatewayRuntime.sessionClosedTotal());
        metric(out, "xuantong_control_plane_sessions_peak",
                gatewayRuntime.peakActiveSessions());
        metric(out, "xuantong_control_plane_subscriptions_opened_total",
                gatewayRuntime.subscriptionOpenedTotal());
        metric(out, "xuantong_control_plane_subscriptions_closed_total",
                gatewayRuntime.subscriptionClosedTotal());
        metric(out, "xuantong_control_plane_subscriptions_peak",
                gatewayRuntime.peakActiveSubscriptions());
        metric(out, "xuantong_control_plane_watch_pending_acknowledgements_peak",
                gatewayRuntime.peakPendingWatchAcknowledgements());
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
        metric(out, "xuantong_gateway_cluster_active_gateways",
                clusterView.activeGatewayCount());
        metric(out, "xuantong_gateway_cluster_stale_gateways",
                clusterView.staleGatewayCount());
        metric(out, "xuantong_gateway_cluster_truncated_gateways",
                clusterView.truncatedGatewayCount());
        metric(out, "xuantong_gateway_cluster_view_complete",
                clusterView.clusterViewComplete() ? 1 : 0);
        metric(out, "xuantong_gateway_cluster_sessions", clusterView.sessions());
        metric(out, "xuantong_gateway_cluster_logical_clients",
                clusterView.logicalClients());
        metric(out, "xuantong_gateway_cluster_coordination_rejected_total",
                gatewayRuntime.clusterCoordinationRejectedTotal());
        metric(out, "xuantong_gateway_local_allocated_sessions",
                clusterView.localQuotaAllocation().maxSessions());
        metric(out, "xuantong_gateway_local_allocated_subscriptions",
                clusterView.localQuotaAllocation().maxSubscriptions());
        metric(out, "xuantong_gateway_local_allocated_tenant_request_rate",
                clusterView.localQuotaAllocation().tenantRequestRatePerSecond());
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
        metric(out, "jvm_heap_used_bytes", memory.getHeapMemoryUsage().getUsed());
        metric(out, "jvm_heap_committed_bytes", memory.getHeapMemoryUsage().getCommitted());
        metric(out, "jvm_heap_max_bytes", memory.getHeapMemoryUsage().getMax());
        metric(out, "jvm_non_heap_used_bytes", memory.getNonHeapMemoryUsage().getUsed());
        metric(out, "jvm_non_heap_committed_bytes",
                memory.getNonHeapMemoryUsage().getCommitted());
        metric(out, "jvm_threads_live", threads.getThreadCount());
        metric(out, "jvm_threads_peak", threads.getPeakThreadCount());
        metric(out, "jvm_threads_daemon", threads.getDaemonThreadCount());
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(
                BufferPoolMXBean.class)) {
            String poolName = metricLabel(pool.getName());
            metric(out, "jvm_buffer_pool_" + poolName + "_count", pool.getCount());
            metric(out, "jvm_buffer_pool_" + poolName + "_used_bytes",
                    pool.getMemoryUsed());
            metric(out, "jvm_buffer_pool_" + poolName + "_capacity_bytes",
                    pool.getTotalCapacity());
        }
        for (GarbageCollectorMXBean collector
                : ManagementFactory.getGarbageCollectorMXBeans()) {
            String collectorName = metricLabel(collector.getName());
            metric(out, "jvm_gc_" + collectorName + "_collections_total",
                    collector.getCollectionCount());
            metric(out, "jvm_gc_" + collectorName + "_collection_milliseconds_total",
                    collector.getCollectionTime());
        }
        metric(out, "xuantong_state_storage_scan_complete",
                stateStorage.scanComplete() ? 1 : 0);
        metric(out, "xuantong_state_storage_scan_failure_total",
                stateStorage.scanFailureTotal());
        metric(out, "xuantong_state_storage_free_bytes",
                stateStorage.storageFreeBytes());
        metric(out, "xuantong_state_storage_free_minimum_bytes",
                stateStorage.storageFreeSpaceMinBytes());
        metric(out, "xuantong_state_storage_free_above_minimum",
                stateStorage.storageFreeSpaceAboveMinimum() ? 1 : 0);
        metric(out, "xuantong_state_storage_files", stateStorage.totalFileCount());
        metric(out, "xuantong_state_storage_bytes", stateStorage.totalBytes());
        metric(out, "xuantong_state_wal_files", stateStorage.walFileCount());
        metric(out, "xuantong_state_wal_bytes", stateStorage.walBytes());
        metric(out, "xuantong_state_snapshot_files", stateStorage.snapshotFileCount());
        metric(out, "xuantong_state_snapshot_bytes", stateStorage.snapshotBytes());
        metric(out, "xuantong_state_snapshot_checksum_verified",
                stateStorage.snapshotChecksumVerifiedCount());
        metric(out, "xuantong_state_snapshot_checksum_mismatches",
                stateStorage.snapshotChecksumMismatchCount());
        metric(out, "xuantong_state_snapshot_checksum_unverified",
                stateStorage.snapshotChecksumUnverifiedCount());
        metric(out, "xuantong_state_snapshot_checksum_failure_total",
                stateStorage.snapshotChecksumFailureTotal());
        if (stateApplyLatency != null) {
            histogram(out, "xuantong_state_apply_duration_seconds", stateApplyLatency);
        }
        metric(out, "xuantong_state_storage_other_files", stateStorage.otherFileCount());
        metric(out, "xuantong_state_storage_other_bytes", stateStorage.otherBytes());
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
    private String metricLabel(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }
    private void histogram(
            StringBuilder out,
            String name,
            FixedLatencyHistogram.Snapshot snapshot) {
        long[] bounds = snapshot.upperBoundsMillis();
        long[] counts = snapshot.cumulativeCounts();
        for (int i = 0; i < bounds.length; i++) {
            out.append(name)
                    .append("_bucket{le=\"")
                    .append(bounds[i] / 1000D)
                    .append("\"} ")
                    .append(counts[i])
                    .append('\n');
        }
        out.append(name).append("_bucket{le=\"+Inf\"} ")
                .append(snapshot.count()).append('\n');
        metric(out, name + "_sum", snapshot.totalSeconds());
        metric(out, name + "_count", snapshot.count());
    }
    private void metric(StringBuilder out, String name, Number value) { out.append(name).append(' ').append(value).append('\n'); }
}
