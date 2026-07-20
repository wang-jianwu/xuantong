package cloud.xuantong.gateway.socketd;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.client.exception.DiscoveryLeaseException;
import cloud.xuantong.client.model.ServiceInstance;
import cloud.xuantong.client.model.ServiceSnapshot;
import cloud.xuantong.client.model.ServiceWatchBatch;
import cloud.xuantong.client.transport.impl.SocketDDiscoveryTransport;
import cloud.xuantong.client.transport.WatchSubscription;
import cloud.xuantong.server.state.ConfigStatePlaneProperties;
import cloud.xuantong.server.state.ControlStatePlaneRuntime;
import cloud.xuantong.server.state.RegistryStatePlaneProperties;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.registry.state.ActivateServiceDefinition;
import cloud.xuantong.registry.state.DeleteServiceDefinition;
import cloud.xuantong.registry.state.RegistryActor;
import cloud.xuantong.registry.state.RegistrySnapshotRequest;
import cloud.xuantong.registry.state.RegistryStateCodec;
import cloud.xuantong.registry.state.ServiceKey;
import cloud.xuantong.state.api.ApplyStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.net.socketd.listener.PathListenerPlus;

import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryControlPlaneProductionIntegrationTest {
    @TempDir
    Path tempDirectory;

    @Test
    void productionDiscoveryAgentUsesSocketdGatewayAndAuthoritativeRegistryGroup()
            throws Exception {
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
                        true, "registry-default", 1_000L, 60_000L);
        ControlStatePlaneRuntime stateRuntime = new ControlStatePlaneRuntime(
                configProperties, registryProperties, dispatcher);
        ControlPlaneGatewayServer gatewayServer = null;
        SocketDDiscoveryTransport provider = null;
        SocketDDiscoveryTransport replacementProvider = null;
        SocketDDiscoveryTransport consumer = null;
        WatchSubscription watchSubscription = null;
        try {
            stateRuntime.start();
            awaitRegistryLeader(stateRuntime, registryProperties);
            assertEquals(ApplyStatus.APPLIED,
                    stateRuntime.stateClient().submit(
                                    RegistryStateCodec.mutationCommand(
                                            registryProperties.stateGroupId(),
                                            "activate-orders",
                                            new ActivateServiceDefinition(
                                                    RegistryActor.system("management"),
                                                    new ServiceKey(
                                                            "public",
                                                            "DEFAULT_GROUP",
                                                            "orders"),
                                                    0L,
                                                    System.currentTimeMillis())))
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS)
                            .status());

            ControlPlaneGatewayProperties gatewayProperties =
                    new ControlPlaneGatewayProperties(
                            "127.0.0.1",
                            gatewayPort,
                            "cluster-discovery-test",
                            "gateway-1",
                            1,
                            5_000,
                            32,
                            2,
                            64,
                            2_000,
                            ControlPlaneGatewayProperties.ClientAuth.NONE);
            ControlPlaneGatewayRuntime gatewayRuntime =
                    new ControlPlaneGatewayRuntime(gatewayProperties);
            PathListenerPlus router = new PathListenerPlus(true);
            router.doOf(ControlPlaneProtocol.CONTROL_PATH,
                    new ControlPlaneGatewayEndpoint(
                            gatewayProperties, gatewayRuntime, dispatcher));
            gatewayServer = new ControlPlaneGatewayServer(
                    gatewayProperties, gatewayRuntime, router, null);
            gatewayServer.start();

            ControlPlaneOptions options = new ControlPlaneOptions(
                    "tenant-a",
                    "registry-default",
                    "cluster-discovery-test",
                    1L,
                    "tcp-default",
                    3_000L,
                    3_000L,
                    6_000L,
                    1_000L);
            provider = new SocketDDiscoveryTransport(
                    new ClientIdentity("orders", "orders@node-1"), options);
            consumer = new SocketDDiscoveryTransport(
                    new ClientIdentity("consumer", "consumer@node-1"), options);
            List<String> addresses = List.of("127.0.0.1:" + gatewayPort);
            provider.connect(addresses, "public", "DEFAULT_GROUP", "orders", "");
            consumer.connect(addresses, "public", "DEFAULT_GROUP", "orders", "");
            CountDownLatch streamedRegistration = new CountDownLatch(1);
            AtomicReference<ServiceWatchBatch> streamedBatch = new AtomicReference<>();
            watchSubscription = consumer.subscribe(0L, batch -> {
                streamedBatch.set(batch);
                if (!batch.events().isEmpty()) {
                    streamedRegistration.countDown();
                }
                return batch.coveredThroughRevision();
            });

            ServiceInstance registration = new ServiceInstance();
            registration.setInstanceId("orders-1");
            registration.setIp("10.0.0.8");
            registration.setPort(8080);
            registration.setWeight(2D);
            registration.setEnabled(true);
            registration.setHealthy(true);
            registration.setMetadata("{\"zone\":\"cn-east\"}");

            ServiceInstance registered = provider.register(registration);
            assertNotNull(registered.getLeaseId());
            assertEquals(1L, registered.getLeaseEpoch());
            assertEquals(1L, registered.getRecoveryEpoch());
            assertEquals(0L, registered.getRenewSequence());
            assertTrue(streamedRegistration.await(5, TimeUnit.SECONDS));
            assertEquals("INSTANCE_REGISTERED",
                    streamedBatch.get().events().getFirst().eventType());

            ServiceSnapshot snapshot = consumer.fetchInstances();
            assertEquals(1L, snapshot.getRevision());
            assertEquals(1, snapshot.getInstances().size());
            assertEquals(registered.getLeaseId(),
                    snapshot.getInstances().getFirst().getLeaseId());

            ServiceWatchBatch registeredEvent = consumer.watchBatch(0L, 10);
            assertEquals(1L, registeredEvent.coveredThroughRevision());
            assertEquals(1, registeredEvent.events().size());
            assertEquals("INSTANCE_REGISTERED",
                    registeredEvent.events().getFirst().eventType());

            ServiceInstance renewed = provider.heartbeat(registered);
            assertEquals(1L, renewed.getRenewSequence());
            ServiceWatchBatch afterHeartbeat = consumer.watchBatch(1L, 10);
            assertTrue(afterHeartbeat.events().isEmpty(),
                    "Lease renewals must not churn the service-view revision");

            replacementProvider = new SocketDDiscoveryTransport(
                    new ClientIdentity("orders", "orders@node-2"), options);
            replacementProvider.connect(
                    addresses, "public", "DEFAULT_GROUP", "orders", "");
            ServiceInstance replacement = replacementProvider.takeover(renewed);
            assertFalse(renewed.getLeaseId().equals(replacement.getLeaseId()));
            assertEquals(2L, replacement.getLeaseEpoch());
            assertEquals(2L, replacement.getRecoveryEpoch());
            assertEquals(0L, replacement.getRenewSequence());
            assertEquals("orders@node-2", replacement.getOwnerNodeId());

            ServiceWatchBatch takeoverEvent = consumer.watchBatch(1L, 10);
            assertEquals(2L, takeoverEvent.coveredThroughRevision());
            assertEquals("INSTANCE_TAKEN_OVER",
                    takeoverEvent.events().getFirst().eventType());
            ServiceSnapshot afterTakeover = consumer.fetchInstances();
            assertEquals(2L, afterTakeover.getRevision());
            assertEquals(replacement.getLeaseId(),
                    afterTakeover.getInstances().getFirst().getLeaseId());

            SocketDDiscoveryTransport oldProvider = provider;
            DiscoveryLeaseException oldHeartbeat = assertThrows(
                    DiscoveryLeaseException.class,
                    () -> oldProvider.heartbeat(renewed));
            assertEquals(DiscoveryLeaseException.Reason.FENCED,
                    oldHeartbeat.reason());
            DiscoveryLeaseException oldDeregister = assertThrows(
                    DiscoveryLeaseException.class,
                    () -> oldProvider.deregister(renewed));
            assertEquals(DiscoveryLeaseException.Reason.FENCED,
                    oldDeregister.reason());
            assertEquals(replacement.getLeaseId(),
                    consumer.fetchInstances().getInstances().getFirst().getLeaseId(),
                    "The old owner must not renew or delete the replacement lease");

            ServiceInstance replacementRenewed = replacementProvider.heartbeat(replacement);
            assertEquals(1L, replacementRenewed.getRenewSequence());
            assertTrue(replacementProvider.deregister(replacementRenewed));
            ServiceWatchBatch deregisteredEvent = consumer.watchBatch(2L, 10);
            assertEquals(3L, deregisteredEvent.coveredThroughRevision());
            assertEquals("INSTANCE_DEREGISTERED",
                    deregisteredEvent.events().getFirst().eventType());
            assertFalse(consumer.fetchInstances().getInstances().stream()
                    .anyMatch(value -> "orders-1".equals(value.getInstanceId())));

            assertEquals(ApplyStatus.APPLIED,
                    stateRuntime.stateClient().submit(
                                    RegistryStateCodec.mutationCommand(
                                            registryProperties.stateGroupId(),
                                            "delete-orders",
                                            new DeleteServiceDefinition(
                                                    RegistryActor.system("management"),
                                                    new ServiceKey(
                                                            "public",
                                                            "DEFAULT_GROUP",
                                                            "orders"),
                                                    1L,
                                                    System.currentTimeMillis())))
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS)
                            .status());
            assertEquals(ApplyStatus.APPLIED,
                    stateRuntime.stateClient().submit(
                                    RegistryStateCodec.mutationCommand(
                                            registryProperties.stateGroupId(),
                                            "reactivate-orders",
                                            new ActivateServiceDefinition(
                                                    RegistryActor.system("management"),
                                                    new ServiceKey(
                                                            "public",
                                                            "DEFAULT_GROUP",
                                                            "orders"),
                                                    1L,
                                                    System.currentTimeMillis())))
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS)
                            .status());

            registration.setServiceGeneration(1L);
            SocketDDiscoveryTransport activeProvider = provider;
            DiscoveryLeaseException fenced = assertThrows(
                    DiscoveryLeaseException.class,
                    () -> activeProvider.register(registration));
            assertEquals(DiscoveryLeaseException.Reason.SERVICE_FENCED,
                    fenced.reason());

            registration.setServiceGeneration(null);
            ServiceInstance generationTwo = provider.register(registration);
            assertEquals(2L, generationTwo.getServiceGeneration());
            assertTrue(provider.deregister(generationTwo));
        } finally {
            if (watchSubscription != null) {
                watchSubscription.close();
            }
            if (consumer != null) {
                consumer.close();
            }
            if (replacementProvider != null) {
                replacementProvider.close();
            }
            if (provider != null) {
                provider.close();
            }
            if (gatewayServer != null) {
                gatewayServer.stop();
            }
            stateRuntime.stop();
        }
    }

    private void awaitRegistryLeader(
            ControlStatePlaneRuntime runtime,
            RegistryStatePlaneProperties properties) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                runtime.stateClient().query(RegistryStateCodec.snapshotQuery(
                                properties.stateGroupId(),
                                new RegistrySnapshotRequest(
                                        "public", "DEFAULT_GROUP", List.of("orders")),
                                cloud.xuantong.state.api.ReadOptions.linearizable()))
                        .toCompletableFuture().get(3, TimeUnit.SECONDS);
                return;
            } catch (Exception e) {
                last = e;
                Thread.sleep(100L);
            }
        }
        throw last == null
                ? new IllegalStateException("Registry State did not elect a leader")
                : last;
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (SocketException e) {
            Assumptions.assumeTrue(false,
                    "Local socket binding is unavailable in this sandbox: " + e.getMessage());
            return -1;
        }
    }
}
