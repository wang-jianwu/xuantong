package cloud.xuantong.admin;

import org.noear.solon.Solon;
import org.noear.solon.annotation.Import;
import org.noear.solon.annotation.SolonMain;

@Import(scanPackages = {"cloud.xuantong.core", "cloud.xuantong.admin"}, profilesIfAbsent = {"classpath:core.yml"})
@SolonMain
public class AdminApp {
    public static void main(String[] args) {
        Solon.start(AdminApp.class, args, app -> {
            // 启用WebSocket支持
            app.enableWebSocket(true);
            app.enableSocketD(true);
        });
    }
}