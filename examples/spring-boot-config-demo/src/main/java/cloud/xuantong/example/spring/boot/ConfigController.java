package cloud.xuantong.example.spring.boot;

import cloud.xuantong.client.annotation.ConfigValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ConfigController {

    @ConfigValue(value = "demo.message", defaultValue = "hello-xuantong", autoRefresh = true)
    private String message;

    @GetMapping("/config")
    public Map<String, String> config() {
        return Map.of("demo.message", message);
    }
}
