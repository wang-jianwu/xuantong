package cloud.xuantong.server.admin.controller;

import cloud.xuantong.server.cluster.GatewayClusterView;
import cloud.xuantong.server.cluster.GatewayClusterViewProvider;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Result;

import java.util.LinkedHashMap;
import java.util.Map;

@Controller
@Mapping("/api/v2/connections")
public class ClientConnectionController {
    @Inject
    private GatewayClusterViewProvider clusterViewProvider;

    @Get
    @Mapping
    public Result<Map<String, Object>> findAll() {
        GatewayClusterView view = clusterViewProvider.currentView();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", view.scope());
        data.put("clusterAggregated", view.clusterAggregated());
        data.put("clusterViewComplete", view.clusterViewComplete());
        data.put("clusterId", view.clusterId());
        data.put("activeGatewayCount", view.activeGatewayCount());
        data.put("staleGatewayCount", view.staleGatewayCount());
        data.put("truncatedGatewayCount", view.truncatedGatewayCount());
        data.put("generatedAt", view.generatedAtEpochMs());
        data.put("logicalClients", view.logicalClients());
        data.put("sessions", view.sessions());
        data.put("inFlightRequests", view.inFlightRequests());
        data.put("subscriptions", view.subscriptions());
        data.put("pendingWatchAcknowledgements", view.pendingWatchAcknowledgements());
        data.put("sessionQuotaRejectedTotal", view.sessionQuotaRejectedTotal());
        data.put("rateLimitedTotal", view.rateLimitedTotal());
        data.put("clusterCoordinationRejectedTotal",
                view.clusterCoordinationRejectedTotal());
        data.put("tenantSessionCounts", view.tenantSessionCounts());
        data.put("credentialSessionCounts", view.credentialSessionCounts());
        data.put("tenantSubscriptionCounts", view.tenantSubscriptionCounts());
        data.put("localQuotaAllocation", view.localQuotaAllocation());
        data.put("gateways", view.gateways());
        data.put("connections", view.connections());
        return Result.succeed(data);
    }
}
