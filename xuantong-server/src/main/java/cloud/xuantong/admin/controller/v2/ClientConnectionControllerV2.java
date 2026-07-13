package cloud.xuantong.admin.controller.v2;

import cloud.xuantong.core.v2.listener.BrokerClientSessionRegistry;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Result;

import java.util.LinkedHashMap;
import java.util.Map;

@Controller
@Mapping("/api/v2/connections")
public class ClientConnectionControllerV2 {
    @Inject
    private BrokerClientSessionRegistry sessionRegistry;

    @Get
    @Mapping
    public Result<Map<String, Object>> findAll() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configClients", sessionRegistry.logicalClientCount(
                BrokerClientSessionRegistry.Channel.CONFIG));
        data.put("configSessions", sessionRegistry.sessionCount(
                BrokerClientSessionRegistry.Channel.CONFIG));
        data.put("discoveryClients", sessionRegistry.logicalClientCount(
                BrokerClientSessionRegistry.Channel.DISCOVERY));
        data.put("discoverySessions", sessionRegistry.sessionCount(
                BrokerClientSessionRegistry.Channel.DISCOVERY));
        data.put("connections", sessionRegistry.connections());
        return Result.succeed(data);
    }
}
