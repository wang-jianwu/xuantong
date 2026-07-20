package cloud.xuantong.client.tls;

import cloud.xuantong.client.TlsOptions;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Builds per-Gateway SSL contexts and detects client trust/key material rotation. */
public final class TlsContextFactory {
    private final TlsOptions options;
    private volatile MaterialFingerprint activeFingerprint;
    private volatile long nextReloadCheckNanos;

    public TlsContextFactory(TlsOptions options) {
        this.options = options == null ? TlsOptions.disabled() : options;
    }

    public boolean enabled() {
        return options.enabled();
    }

    public synchronized SSLContext create(String peerHost) {
        if (!options.enabled()) {
            return null;
        }
        try {
            LoadedMaterial material = loadMaterial();
            SSLContext context = buildContext(peerHost, material);
            activeFingerprint = material.fingerprint();
            nextReloadCheckNanos = deadlineAfter(options.reloadIntervalMs());
            return context;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Unable to load Xuantong TLS material", e);
        }
    }

    /**
     * Returns true only after changed stores have been parsed successfully. The caller
     * can then drain the old Socket.D session and reconnect with a freshly built context.
     */
    public synchronized boolean reloadRequired() {
        if (!options.enabled() || System.nanoTime() < nextReloadCheckNanos) {
            return false;
        }
        nextReloadCheckNanos = deadlineAfter(options.reloadIntervalMs());
        try {
            LoadedMaterial material = loadMaterial();
            if (material.fingerprint().equals(activeFingerprint)) {
                return false;
            }
            // Parse key/trust managers before retiring a working connection. Hostname
            // validation is performed during the next TLS handshake for the real peer.
            buildContext("rotation-validation.invalid", material);
            activeFingerprint = material.fingerprint();
            return true;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Changed Xuantong TLS material is not usable", e);
        }
    }

    private LoadedMaterial loadMaterial() throws IOException, GeneralSecurityException {
        KeyManager[] keyManagers = loadKeyManagers();
        X509TrustManager trustManager = loadTrustManager();
        MaterialFingerprint fingerprint = new MaterialFingerprint(
                fingerprint(options.trustStore()), fingerprint(options.keyStore()));
        return new LoadedMaterial(keyManagers, trustManager, fingerprint);
    }

    private SSLContext buildContext(String peerHost, LoadedMaterial material)
            throws GeneralSecurityException {
        X509TrustManager trustManager = material.trustManager();
        if (options.hostnameVerification()) {
            if (peerHost == null || peerHost.isBlank()) {
                throw new GeneralSecurityException(
                        "TLS hostname verification requires a Gateway host");
            }
            trustManager = new HostnameVerifyingTrustManager(trustManager, peerHost);
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(
                material.keyManagers(),
                new TrustManager[]{trustManager},
                new SecureRandom());
        return context;
    }

    private KeyManager[] loadKeyManagers() throws IOException, GeneralSecurityException {
        if (options.keyStore().isEmpty()) {
            return null;
        }
        KeyStore keyStore = loadStore(
                options.keyStore(), options.keyStoreType(), options.keyStorePassword());
        KeyManagerFactory factory = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, options.keyPassword().toCharArray());
        return factory.getKeyManagers();
    }

    private X509TrustManager loadTrustManager()
            throws IOException, GeneralSecurityException {
        KeyStore trustStore = options.trustStore().isEmpty()
                ? null
                : loadStore(options.trustStore(), options.trustStoreType(),
                options.trustStorePassword());
        TrustManagerFactory factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509TrustManager) {
                return x509TrustManager;
            }
        }
        throw new GeneralSecurityException("No X509 TrustManager is available");
    }

    private KeyStore loadStore(String file, String type, String password)
            throws IOException, GeneralSecurityException {
        Path path = readablePath(file);
        KeyStore store = KeyStore.getInstance(type);
        try (InputStream input = Files.newInputStream(path)) {
            store.load(input, password.toCharArray());
        }
        return store;
    }

    private String fingerprint(String file) throws IOException, GeneralSecurityException {
        if (file == null || file.isEmpty()) {
            return "<jvm-default>";
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Path path = readablePath(file);
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Path readablePath(String file) throws IOException {
        Path path = Path.of(file).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IOException("TLS store is not a readable file: " + path);
        }
        return path;
    }

    private long deadlineAfter(long delayMs) {
        long delayNanos = TimeUnit.MILLISECONDS.toNanos(delayMs);
        long now = System.nanoTime();
        return now > Long.MAX_VALUE - delayNanos ? Long.MAX_VALUE : now + delayNanos;
    }

    private record LoadedMaterial(
            KeyManager[] keyManagers,
            X509TrustManager trustManager,
            MaterialFingerprint fingerprint) {
    }

    private record MaterialFingerprint(String trustStore, String keyStore) {
    }

    private static final class HostnameVerifyingTrustManager
            extends X509ExtendedTrustManager {
        private final X509TrustManager delegate;
        private final String expectedHost;

        private HostnameVerifyingTrustManager(
                X509TrustManager delegate, String expectedHost) {
            this.delegate = delegate;
            this.expectedHost = normalizeHost(expectedHost);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
            verify(chain);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }

        @Override
        public void checkClientTrusted(
                X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            if (delegate instanceof X509ExtendedTrustManager extended) {
                extended.checkClientTrusted(chain, authType, socket);
            } else {
                delegate.checkClientTrusted(chain, authType);
            }
        }

        @Override
        public void checkServerTrusted(
                X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            if (delegate instanceof X509ExtendedTrustManager extended) {
                extended.checkServerTrusted(chain, authType, socket);
            } else {
                delegate.checkServerTrusted(chain, authType);
            }
            verify(chain);
        }

        @Override
        public void checkClientTrusted(
                X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            if (delegate instanceof X509ExtendedTrustManager extended) {
                extended.checkClientTrusted(chain, authType, engine);
            } else {
                delegate.checkClientTrusted(chain, authType);
            }
        }

        @Override
        public void checkServerTrusted(
                X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            if (delegate instanceof X509ExtendedTrustManager extended) {
                extended.checkServerTrusted(chain, authType, engine);
            } else {
                delegate.checkServerTrusted(chain, authType);
            }
            verify(chain);
        }

        private void verify(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("Server certificate chain is empty");
            }
            X509Certificate certificate = chain[0];
            certificate.checkValidity();
            if (!matches(certificate, expectedHost)) {
                throw new CertificateException(
                        "Server certificate does not match Gateway host " + expectedHost);
            }
        }
    }

    static boolean matches(X509Certificate certificate, String host)
            throws CertificateException {
        String expected = normalizeHost(host);
        boolean ipAddress = isIpAddress(expected);
        Collection<List<?>> names = certificate.getSubjectAlternativeNames();
        boolean relevantSanPresent = false;
        if (names != null) {
            for (List<?> name : names) {
                if (name == null || name.size() < 2 || !(name.get(0) instanceof Integer type)) {
                    continue;
                }
                if (ipAddress && type == 7) {
                    relevantSanPresent = true;
                    if (ipEquals(expected, String.valueOf(name.get(1)))) {
                        return true;
                    }
                } else if (!ipAddress && type == 2) {
                    relevantSanPresent = true;
                    if (dnsMatches(expected, String.valueOf(name.get(1)))) {
                        return true;
                    }
                }
            }
        }
        if (relevantSanPresent || ipAddress) {
            return false;
        }
        String commonName = commonName(certificate.getSubjectX500Principal());
        return commonName != null && dnsMatches(expected, commonName);
    }

    private static String commonName(X500Principal principal) throws CertificateException {
        try {
            for (Rdn rdn : new LdapName(principal.getName(X500Principal.RFC2253)).getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    return String.valueOf(rdn.getValue());
                }
            }
            return null;
        } catch (Exception e) {
            throw new CertificateException("Unable to parse certificate subject", e);
        }
    }

    private static boolean dnsMatches(String host, String pattern) {
        String normalizedPattern = normalizeHost(pattern);
        if (!normalizedPattern.startsWith("*.")) {
            return host.equals(normalizedPattern);
        }
        String suffix = normalizedPattern.substring(1);
        if (!host.endsWith(suffix)) {
            return false;
        }
        String prefix = host.substring(0, host.length() - suffix.length());
        return !prefix.isEmpty() && prefix.indexOf('.') < 0;
    }

    private static boolean ipEquals(String left, String right) {
        try {
            return InetAddress.getByName(left).equals(InetAddress.getByName(right));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isIpAddress(String host) {
        if (host.indexOf(':') >= 0) {
            return host.matches("[0-9a-fA-F:.%]+$");
        }
        return host.matches("(?:\\d{1,3}\\.){3}\\d{1,3}");
    }

    private static String normalizeHost(String value) {
        String host = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }
}
