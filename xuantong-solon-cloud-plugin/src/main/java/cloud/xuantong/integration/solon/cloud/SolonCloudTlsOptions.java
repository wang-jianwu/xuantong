package cloud.xuantong.integration.solon.cloud;

import cloud.xuantong.client.TlsOptions;
import org.noear.solon.Solon;

final class SolonCloudTlsOptions {
    private static final String PREFIX = "solon.cloud.xuantong.tls.";

    private SolonCloudTlsOptions() {
    }

    static TlsOptions load() {
        return new TlsOptions(
                Solon.cfg().getBool(PREFIX + "enabled", false),
                Solon.cfg().get(PREFIX + "trustStore", ""),
                Solon.cfg().get(PREFIX + "trustStoreType", "PKCS12"),
                Solon.cfg().get(PREFIX + "trustStorePassword", ""),
                Solon.cfg().get(PREFIX + "keyStore", ""),
                Solon.cfg().get(PREFIX + "keyStoreType", "PKCS12"),
                Solon.cfg().get(PREFIX + "keyStorePassword", ""),
                Solon.cfg().get(PREFIX + "keyPassword", ""),
                Solon.cfg().getBool(PREFIX + "hostnameVerification", true),
                Solon.cfg().getLong(PREFIX + "reloadIntervalMs", 30_000L));
    }
}
