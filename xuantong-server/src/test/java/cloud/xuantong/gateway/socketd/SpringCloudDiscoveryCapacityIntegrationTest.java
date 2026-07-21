package cloud.xuantong.gateway.socketd;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.integration.spring.cloud.autoconfigure.XuantongSpringCloudProperties;
import cloud.xuantong.integration.spring.cloud.discovery.XuantongDiscoveryClientFactory;
import cloud.xuantong.integration.spring.cloud.discovery.XuantongDiscoveryClientManager;
import cloud.xuantong.integration.spring.cloud.discovery.XuantongDiscoveryOperations;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.server.state.ConfigStatePlaneProperties;
import cloud.xuantong.server.state.ControlStatePlaneRuntime;
import cloud.xuantong.server.state.RegistryStatePlaneProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.net.socketd.listener.PathListenerPlus;

import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringCloudDiscoveryCapacityIntegrationTest {
    @TempDir
    Path tempDirectory;

    @Test
    void manyDownstreamServicesShareOneSessionAndBoundedAgentThreads()
            throws Exception {
        int services = 64;
        int statePort = freePort();
        int gatewayPort = freePort();
        ControlPlaneRequestDispatcher dispatcher = new ControlPlaneRequestDispatcher();
        ConfigStatePlaneProperties configProperties = new ConfigStatePlaneProperties(
                true,
                "state-1",
                "config-default",
                "state-1@127.0.0.1:" + statePort,
                tempDirectory.resolve("state"),
                true);
        RegistryStatePlaneProperties registryProperties =
                new RegistryStatePlaneProperties(
                        true, "registry-default", 3_000L, 120_000L);
        ControlStatePlaneRuntime stateRuntime = new ControlStatePlaneRuntime(
                configProperties, registryProperties, dispatcher);
        ControlPlaneGatewayProperties gatewayProperties =
                new ControlPlaneGatewayProperties(
                        "127.0.0.1",
                        gatewayPort,
                        "cluster-spring-cloud-capacity",
                        "gateway-spring-cloud-capacity",
                        1L,
                        10_000L,
                        512,
                        4,
                        4_096,
                        3_000L,
                        ControlPlaneGatewayProperties.ClientAuth.NONE);
        ControlPlaneGatewayRuntime gatewayRuntime =
                new ControlPlaneGatewayRuntime(gatewayProperties);
        PathListenerPlus router = new PathListenerPlus(true);
        router.doOf(ControlPlaneProtocol.CONTROL_PATH,
                new ControlPlaneGatewayEndpoint(
                        gatewayProperties, gatewayRuntime, dispatcher));
        ControlPlaneGatewayServer gatewayServer = new ControlPlaneGatewayServer(
                gatewayProperties, gatewayRuntime, router, null);
        XuantongDiscoveryClientManager manager = null;
        try {
            stateRuntime.start();
            gatewayServer.start();

            XuantongSpringCloudProperties properties =
                    new XuantongSpringCloudProperties();
            properties.setServerAddresses(List.of("127.0.0.1:" + gatewayPort));
            properties.setNamespace("public");
            properties.setGroup("DEFAULT_GROUP");
            properties.setClusterId("cluster-spring-cloud-capacity");
            properties.setTransportGeneration(1L);
            properties.setConnectTimeout(Duration.ofSeconds(3));
            properties.setRequestTimeout(Duration.ofSeconds(3));
            properties.setOperationTimeout(Duration.ofSeconds(6));
            properties.getDiscovery().setStateGroupId("registry-default");
            XuantongDiscoveryClientFactory factory =
                    new XuantongDiscoveryClientFactory(
                            properties,
                            new ClientIdentity(
                                    "spring-cloud-capacity",
                                    "spring-cloud-capacity@node-1"));
            manager = new XuantongDiscoveryClientManager(factory);

            List<XuantongDiscoveryOperations> agents = new ArrayList<>();
            for (int index = 0; index < services; index++) {
                String serviceName = "downstream-" + index;
                XuantongDiscoveryOperations agent = manager.get(serviceName);
                agents.add(agent);
                assertTrue(agent.getInstances().isEmpty());
                assertSame(agent, manager.get(serviceName),
                        "Repeated Spring Cloud lookups must reuse the service agent");
            }

            awaitTrue(() -> gatewayRuntime.activeSessions() == 1
                            && gatewayRuntime.activeSubscriptions() == services,
                    Duration.ofSeconds(15));
            assertEquals(1L, gatewayRuntime.sessionOpenedTotal(),
                    "Service count must not multiply Socket.D Sessions");
            assertEquals(services, gatewayRuntime.subscriptionOpenedTotal(),
                    "Each service keeps one independent revisioned Watch");
            assertTrue(threadCount("xuantong-discovery-agent-") <= 2,
                    "Discovery maintenance threads must be JVM-scoped, not service-scoped");

            manager.close();
            manager = null;
            awaitTrue(() -> gatewayRuntime.activeSessions() == 0
                            && gatewayRuntime.activeSubscriptions() == 0
                            && gatewayRuntime.pendingWatchAcknowledgements() == 0,
                    Duration.ofSeconds(10));
            assertEquals(gatewayRuntime.sessionOpenedTotal(),
                    gatewayRuntime.sessionClosedTotal());
            assertEquals(gatewayRuntime.subscriptionOpenedTotal(),
                    gatewayRuntime.subscriptionClosedTotal());
        } finally {
            if (manager != null) {
                manager.close();
            }
            gatewayServer.stop();
            stateRuntime.stop();
        }
    }

    private void awaitTrue(Check condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) {
                return;
            }
            Thread.sleep(25L);
        }
        assertTrue(condition.evaluate(), "Condition was not satisfied before timeout");
    }

    private int threadCount(String prefix) {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (SocketException e) {
            Assumptions.assumeTrue(false,
                    "Local socket binding is unavailable in this sandbox: "
                            + e.getMessage());
            return -1;
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean evaluate() throws Exception;
    }
}
