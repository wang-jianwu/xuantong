package cloud.xuantong.probe;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.client.TlsOptions;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

record ProbeSettings(
        List<String> servers,
        Profile profile,
        String namespace,
        String group,
        String accessToken,
        ClientIdentity identity,
        ControlPlaneOptions controlPlane,
        String bindHost,
        int port,
        long intervalMs) {

    ProbeSettings {
        servers = servers == null ? List.of() : List.copyOf(servers);
        if (servers.isEmpty()) {
            throw new IllegalArgumentException("At least one probe server is required");
        }
        if (profile == null || identity == null || controlPlane == null) {
            throw new IllegalArgumentException("Probe profile, identity and options are required");
        }
        namespace = requireText("namespace", namespace);
        group = requireText("group", group);
        accessToken = accessToken == null ? "" : accessToken;
        bindHost = requireText("bindHost", bindHost);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Probe HTTP port must be between 1 and 65535");
        }
        if (intervalMs < 1_000L || intervalMs > 3_600_000L) {
            throw new IllegalArgumentException(
                    "Probe interval must be between 1000 and 3600000 ms");
        }
    }

    static ProbeSettings fromEnvironment(Map<String, String> environment) {
        Map<String, String> env = environment == null ? Map.of() : environment;
        Profile profile = Profile.parse(value(env, "config", "XUANTONG_PROBE_PROFILE"));
        List<String> servers = parseServers(value(
                env, "127.0.0.1:8090", "XUANTONG_PROBE_SERVERS", "XUANTONG_SERVERS"));
        String namespace = value(env, "public", "XUANTONG_PROBE_NAMESPACE", "XUANTONG_NAMESPACE");
        String group = value(env, "DEFAULT_GROUP", "XUANTONG_PROBE_GROUP", "XUANTONG_GROUP");
        String token = value(env, "", "XUANTONG_PROBE_TOKEN", "XUANTONG_ACCESS_TOKEN");
        String applicationName = value(
                env, "xuantong-probe-" + profile.label(), "XUANTONG_PROBE_APPLICATION_NAME");
        String instanceId = value(env, "", "XUANTONG_PROBE_INSTANCE_ID");

        String defaultStateGroup = profile == Profile.CONFIG
                ? value(env, "config-default", "XUANTONG_CONFIG_GROUP_ID")
                : value(env, "registry-default", "XUANTONG_REGISTRY_GROUP_ID");
        TlsOptions tls = tlsOptions(env);
        ControlPlaneOptions controlPlane = new ControlPlaneOptions(
                value(env, "default", "XUANTONG_PROBE_TENANT", "XUANTONG_TENANT"),
                value(env, defaultStateGroup, "XUANTONG_PROBE_STATE_GROUP_ID"),
                value(env, "", "XUANTONG_PROBE_CLUSTER_ID", "XUANTONG_CLUSTER_ID"),
                longValue(env, 0L, 0L, Long.MAX_VALUE,
                        "XUANTONG_PROBE_TRANSPORT_GENERATION", "XUANTONG_TRANSPORT_GENERATION"),
                value(env, "tcp-default", "XUANTONG_PROBE_TRANSPORT_POOL", "XUANTONG_TRANSPORT_POOL"),
                longValue(env, 3_000L, 100L, 3_600_000L,
                        "XUANTONG_PROBE_CONNECT_TIMEOUT_MS"),
                longValue(env, 3_000L, 100L, 3_600_000L,
                        "XUANTONG_PROBE_REQUEST_TIMEOUT_MS"),
                longValue(env, 6_000L, 100L, 3_600_000L,
                        "XUANTONG_PROBE_OPERATION_TIMEOUT_MS"),
                longValue(env, 1_000L, 0L, 3_600_000L,
                        "XUANTONG_PROBE_CLOSING_TIMEOUT_MS"),
                tls);
        return new ProbeSettings(
                servers,
                profile,
                namespace,
                group,
                token,
                new ClientIdentity(applicationName, instanceId),
                controlPlane,
                value(env, "0.0.0.0", "XUANTONG_PROBE_BIND_HOST"),
                intValue(env, 9_118, 1, 65_535, "XUANTONG_PROBE_PORT"),
                longValue(env, 15_000L, 1_000L, 3_600_000L,
                        "XUANTONG_PROBE_INTERVAL_MS"));
    }

    private static TlsOptions tlsOptions(Map<String, String> env) {
        boolean enabled = booleanValue(env, false,
                "XUANTONG_PROBE_TLS_ENABLED", "XUANTONG_CLIENT_TLS_ENABLED");
        if (!enabled) {
            return TlsOptions.disabled();
        }
        return TlsOptions.enabled(
                value(env, "", "XUANTONG_PROBE_TLS_TRUST_STORE", "XUANTONG_CLIENT_TLS_TRUST_STORE"),
                value(env, "PKCS12", "XUANTONG_PROBE_TLS_TRUST_STORE_TYPE", "XUANTONG_CLIENT_TLS_TRUST_STORE_TYPE"),
                secretValue(env, "XUANTONG_PROBE_TLS_TRUST_STORE_PASSWORD", "XUANTONG_CLIENT_TLS_TRUST_STORE_PASSWORD"),
                value(env, "", "XUANTONG_PROBE_TLS_KEY_STORE", "XUANTONG_CLIENT_TLS_KEY_STORE"),
                value(env, "PKCS12", "XUANTONG_PROBE_TLS_KEY_STORE_TYPE", "XUANTONG_CLIENT_TLS_KEY_STORE_TYPE"),
                secretValue(env, "XUANTONG_PROBE_TLS_KEY_STORE_PASSWORD", "XUANTONG_CLIENT_TLS_KEY_STORE_PASSWORD"),
                secretValue(env, "XUANTONG_PROBE_TLS_KEY_PASSWORD", "XUANTONG_CLIENT_TLS_KEY_PASSWORD"),
                booleanValue(env, true,
                        "XUANTONG_PROBE_TLS_HOSTNAME_VERIFICATION",
                        "XUANTONG_CLIENT_TLS_HOSTNAME_VERIFICATION"),
                longValue(env, 30_000L, 1_000L, 3_600_000L,
                        "XUANTONG_PROBE_TLS_RELOAD_INTERVAL_MS",
                        "XUANTONG_CLIENT_TLS_RELOAD_INTERVAL_MS"));
    }

    private static List<String> parseServers(String raw) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .forEach(result::add);
        if (result.isEmpty()) {
            throw new IllegalArgumentException("XUANTONG_PROBE_SERVERS must not be blank");
        }
        return List.copyOf(result);
    }

    private static String value(
            Map<String, String> env, String defaultValue, String... names) {
        for (String name : names) {
            String value = env.get(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return defaultValue;
    }

    private static String secretValue(Map<String, String> env, String... names) {
        for (String name : names) {
            String value = env.get(name);
            if (value != null) {
                return value;
            }
        }
        return "";
    }

    private static boolean booleanValue(
            Map<String, String> env, boolean defaultValue, String... names) {
        String raw = value(env, Boolean.toString(defaultValue), names);
        if ("true".equalsIgnoreCase(raw)) return true;
        if ("false".equalsIgnoreCase(raw)) return false;
        throw new IllegalArgumentException(names[0] + " must be true or false");
    }

    private static int intValue(
            Map<String, String> env,
            int defaultValue,
            int minimum,
            int maximum,
            String... names) {
        long value = longValue(env, defaultValue, minimum, maximum, names);
        return Math.toIntExact(value);
    }

    private static long longValue(
            Map<String, String> env,
            long defaultValue,
            long minimum,
            long maximum,
            String... names) {
        String raw = value(env, Long.toString(defaultValue), names);
        try {
            long parsed = Long.parseLong(raw);
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(
                        names[0] + " must be between " + minimum + " and " + maximum);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(names[0] + " must be an integer", e);
        }
    }

    private static String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    @Override
    public String toString() {
        return "ProbeSettings[servers=" + servers
                + ", profile=" + profile
                + ", namespace=" + namespace
                + ", group=" + group
                + ", accessToken=<redacted>"
                + ", identity=" + identity.getClientInstanceId()
                + ", controlPlane=" + controlPlane
                + ", bindHost=" + bindHost
                + ", port=" + port
                + ", intervalMs=" + intervalMs + ']';
    }

    enum Profile {
        CONFIG,
        DISCOVERY;

        static Profile parse(String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "XUANTONG_PROBE_PROFILE must be config or discovery", e);
            }
        }

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
