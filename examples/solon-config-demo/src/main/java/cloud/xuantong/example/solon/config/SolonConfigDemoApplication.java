package cloud.xuantong.example.solon.config;

import org.noear.solon.Solon;
import org.noear.solon.annotation.SolonMain;

@SolonMain
public class SolonConfigDemoApplication {

    public static void main(String[] args) {
        Solon.start(SolonConfigDemoApplication.class, args);
    }
}
