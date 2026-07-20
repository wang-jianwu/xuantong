package cloud.xuantong.client.tls;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class TestTlsMaterial {
    static final String PASSWORD = "changeit";

    private final Path serverKeyStore;
    private final Path clientKeyStore;
    private final Path trustStore;

    private TestTlsMaterial(Path serverKeyStore, Path clientKeyStore, Path trustStore) {
        this.serverKeyStore = serverKeyStore;
        this.clientKeyStore = clientKeyStore;
        this.trustStore = trustStore;
    }

    static TestTlsMaterial create(Path directory) throws Exception {
        return create(directory, "SAN=dns:localhost,ip:127.0.0.1", null, 3_650);
    }

    static TestTlsMaterial create(
            Path directory, String serverSan, String serverStartDate, int serverValidity)
            throws Exception {
        Files.createDirectories(directory);
        Path ca = directory.resolve("ca.p12");
        Path caCertificate = directory.resolve("ca.crt");
        Path server = directory.resolve("server.p12");
        Path serverCsr = directory.resolve("server.csr");
        Path serverCertificate = directory.resolve("server.crt");
        Path client = directory.resolve("client.p12");
        Path clientCsr = directory.resolve("client.csr");
        Path clientCertificate = directory.resolve("client.crt");
        Path trust = directory.resolve("trust.p12");

        runKeytool(directory,
                "-genkeypair", "-alias", "ca", "-dname", "CN=Xuantong-Test-CA",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                "-ext", "bc=ca:true", "-storetype", "PKCS12", "-keystore", ca.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD, "-noprompt");
        runKeytool(directory,
                "-exportcert", "-alias", "ca", "-keystore", ca.toString(),
                "-storepass", PASSWORD, "-rfc", "-file", caCertificate.toString());

        runKeytool(directory,
                "-genkeypair", "-alias", "server", "-dname", "CN=localhost",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                "-ext", serverSan, "-storetype", "PKCS12",
                "-keystore", server.toString(), "-storepass", PASSWORD,
                "-keypass", PASSWORD, "-noprompt");
        runKeytool(directory,
                "-certreq", "-alias", "server", "-keystore", server.toString(),
                "-storepass", PASSWORD, "-file", serverCsr.toString(),
                "-ext", serverSan);
        List<String> serverCertificateCommand = new ArrayList<>(List.of(
                "-gencert", "-alias", "ca", "-keystore", ca.toString(),
                "-storepass", PASSWORD, "-infile", serverCsr.toString(),
                "-outfile", serverCertificate.toString(), "-rfc",
                "-validity", Integer.toString(serverValidity),
                "-ext", "KU=digitalSignature,keyEncipherment",
                "-ext", "EKU=serverAuth", "-ext", serverSan));
        if (serverStartDate != null) {
            serverCertificateCommand.add("-startdate");
            serverCertificateCommand.add(serverStartDate);
        }
        runKeytool(directory, serverCertificateCommand.toArray(String[]::new));
        importCertificate(directory, caCertificate, "ca", server);
        importCertificate(directory, serverCertificate, "server", server);

        runKeytool(directory,
                "-genkeypair", "-alias", "client", "-dname", "CN=xuantong-test-client",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                "-storetype", "PKCS12", "-keystore", client.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD, "-noprompt");
        runKeytool(directory,
                "-certreq", "-alias", "client", "-keystore", client.toString(),
                "-storepass", PASSWORD, "-file", clientCsr.toString());
        runKeytool(directory,
                "-gencert", "-alias", "ca", "-keystore", ca.toString(),
                "-storepass", PASSWORD, "-infile", clientCsr.toString(),
                "-outfile", clientCertificate.toString(), "-rfc", "-validity", "3650",
                "-ext", "KU=digitalSignature,keyEncipherment", "-ext", "EKU=clientAuth");
        importCertificate(directory, caCertificate, "ca", client);
        importCertificate(directory, clientCertificate, "client", client);
        importCertificate(directory, caCertificate, "ca", trust);

        return new TestTlsMaterial(server, client, trust);
    }

    static Path combineTrustStores(
            Path output, TestTlsMaterial first, TestTlsMaterial second) throws Exception {
        KeyStore combined = KeyStore.getInstance("PKCS12");
        combined.load(null, PASSWORD.toCharArray());
        copyCertificates(loadStore(first.trustStore), combined, "first-");
        copyCertificates(loadStore(second.trustStore), combined, "second-");
        try (var stream = Files.newOutputStream(output)) {
            combined.store(stream, PASSWORD.toCharArray());
        }
        return output;
    }

    Path mutableTrustStore(Path destination) throws Exception {
        return Files.copy(trustStore, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    Path clientKeyStore() {
        return clientKeyStore;
    }

    Path trustStore() {
        return trustStore;
    }

    SSLContext serverContext() throws Exception {
        return sslContext(serverKeyStore, trustStore);
    }

    private static SSLContext sslContext(Path keyStorePath, Path trustStorePath)
            throws Exception {
        KeyManager[] keyManagers = null;
        if (keyStorePath != null) {
            KeyStore keyStore = loadStore(keyStorePath);
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, PASSWORD.toCharArray());
            keyManagers = keyManagerFactory.getKeyManagers();
        }
        KeyStore trustStore = loadStore(trustStorePath);
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers, trustManagerFactory.getTrustManagers(), new SecureRandom());
        return context;
    }

    private static KeyStore loadStore(Path path) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path)) {
            keyStore.load(input, PASSWORD.toCharArray());
        }
        return keyStore;
    }

    private static void copyCertificates(
            KeyStore source, KeyStore target, String prefix) throws Exception {
        Enumeration<String> aliases = source.aliases();
        int index = 0;
        while (aliases.hasMoreElements()) {
            Certificate certificate = source.getCertificate(aliases.nextElement());
            if (certificate != null) {
                target.setCertificateEntry(prefix + index++, certificate);
            }
        }
    }

    private static void importCertificate(
            Path directory, Path certificate, String alias, Path keyStore) throws Exception {
        runKeytool(directory,
                "-importcert", "-alias", alias, "-file", certificate.toString(),
                "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                "-storepass", PASSWORD, "-noprompt");
    }

    private static void runKeytool(Path directory, String... arguments) throws Exception {
        Path executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                        ? "keytool.exe" : "keytool");
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("keytool timed out: " + command);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                    "keytool failed: " + command + System.lineSeparator() + output);
        }
    }
}
