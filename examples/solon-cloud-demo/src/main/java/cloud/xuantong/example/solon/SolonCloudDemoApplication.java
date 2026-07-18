package cloud.xuantong.example.solon;

import org.noear.solon.Solon;
import org.noear.solon.annotation.SolonMain;

@SolonMain
public class SolonCloudDemoApplication {
    public static void main(String[] args) {
        Solon.start(SolonCloudDemoApplication.class, args);
    }
}
