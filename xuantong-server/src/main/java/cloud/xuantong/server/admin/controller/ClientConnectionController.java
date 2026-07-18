package cloud.xuantong.server.admin.controller;

import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime;
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
    private ControlPlaneGatewayRuntime gatewayRuntime;

    @Get
    @Mapping
    public Result<Map<String, Object>> findAll() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("logicalClients", gatewayRuntime.logicalClients());
        data.put("sessions", gatewayRuntime.activeSessions());
        data.put("inFlightRequests", gatewayRuntime.inFlightRequests());
        data.put("subscriptions", gatewayRuntime.activeSubscriptions());
        data.put("sessionQuotaRejectedTotal",
                gatewayRuntime.gatewaySessionLimitRejectedTotal()
                        + gatewayRuntime.tenantSessionLimitRejectedTotal()
                        + gatewayRuntime.credentialSessionLimitRejectedTotal());
        data.put("rateLimitedTotal",
                gatewayRuntime.tenantRequestRateLimitedTotal()
                        + gatewayRuntime.tenantSubscriptionLimitRejectedTotal()
                        + gatewayRuntime.authenticationRateLimitedTotal());
        data.put("helloTimeoutClosedTotal", gatewayRuntime.helloTimeoutClosedTotal());
        data.put("connections", gatewayRuntime.connections());
        return Result.succeed(data);
    }
}
