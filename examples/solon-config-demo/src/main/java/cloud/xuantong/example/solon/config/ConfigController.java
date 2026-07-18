package cloud.xuantong.example.solon.config;

import cloud.xuantong.client.annotation.ConfigValue;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Result;

@Controller
public class ConfigController {

    @ConfigValue(value = "demo.message", defaultValue = "hello-xuantong", autoRefresh = true)
    private String message;

    @Mapping("/config")
    public Result<String> config() {
        return Result.succeed(message);
    }
}
