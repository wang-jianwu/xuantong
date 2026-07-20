package cloud.xuantong.server.admin;

import org.noear.solon.Solon;
import org.noear.solon.annotation.Import;
import org.noear.solon.annotation.SolonMain;

@Import(scanPackages = "cloud.xuantong", profilesIfAbsent = "classpath:core.yml")
@SolonMain
public class AdminApp {
    public static void main(String[] args) {
        Solon.start(AdminApp.class, args);
    }
}
