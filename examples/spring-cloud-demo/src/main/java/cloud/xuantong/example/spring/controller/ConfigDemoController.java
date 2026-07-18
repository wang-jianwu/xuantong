package cloud.xuantong.example.spring.controller;

import cloud.xuantong.client.annotation.ConfigValue;
import lombok.Getter;
import lombok.ToString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * author 封于修
 * date 2025/11/20 23:37
 */
@RestController
public class ConfigDemoController {
    private static final Logger logger = LoggerFactory.getLogger(ConfigDemoController.class);

    @ConfigValue(value = "demo.message", defaultValue = "hello-xuantong", autoRefresh = true)
    private String message;

    /**
     * json [{"name":"德莱厄斯","age":18},{"name":"锐雯","age":18}]
     */
    @ConfigValue(value = "demo.list", required = false)
    private List<User> list;

    /**
     * json {"MALE":[{"name":"德莱厄斯","age":18}],"FEMALE":[{"name":"锐雯","age":18}]}
     */
    @ConfigValue(value = "demo.map", required = false)
    private Map<Gender, List<User>> map;

    @GetMapping("/")
    public ResponseEntity<String> test() {
        String value = this.message + "\n"
                + this.list + "\n"
                + (this.map == null ? null : this.map.get(Gender.MALE));
        logger.info("test() 方法被调用，当前 test 字段值: {}", value);
        return ResponseEntity.ok(value);
    }

    @Getter
    @ToString
    static class User {
        private String name;
        private int age;
    }

    @Getter
    enum Gender {
        MALE, FEMALE
    }
}
