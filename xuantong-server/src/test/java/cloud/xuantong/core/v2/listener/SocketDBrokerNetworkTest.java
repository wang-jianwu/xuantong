package cloud.xuantong.core.v2.listener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.noear.socketd.SocketD;
import org.noear.socketd.broker.BrokerListener;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.entity.StringEntity;
import org.noear.socketd.transport.core.listener.EventListener;
import org.noear.socketd.transport.server.Server;

import java.net.ServerSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketDBrokerNetworkTest {
    @Test
    void broadcastsToEverySameNamedSocketDSessionAndHonorsPreclose() throws Exception {
        int port = freePort();
        BrokerListener broker = new BrokerListener();
        Server server = SocketD.createServer("sd:ws")
                .config(config -> config.host("127.0.0.1").port(port))
                .listen(broker)
                .start();
        List<ClientSession> clients = new ArrayList<>();
        CountDownLatch received = new CountDownLatch(3);
        try {
            Thread.sleep(100L);
            for (int i = 0; i < 3; i++) {
                ClientSession client = SocketD.createClient(
                                "sd:ws://127.0.0.1:" + port + "/?@=config:public:DEFAULT_GROUP")
                        .config(config -> config.connectTimeout(3_000L).autoReconnect(false))
                        .listen(new EventListener().doOn("/config-change",
                                (session, message) -> received.countDown()))
                        .openOrThow();
                clients.add(client);
            }
            waitForPlayerCount(broker, "config:public:DEFAULT_GROUP", 3);
            broker.broadcast("/config-change",
                    new StringEntity("change").at("config:public:DEFAULT_GROUP*"));

            assertTrue(received.await(3L, TimeUnit.SECONDS));
            assertEquals(0L, received.getCount());

            ClientSession first = clients.get(0);
            first.preclose();
            waitUntilBrokerHasInactiveSession(broker, "config:public:DEFAULT_GROUP");
            assertTrue(broker.getPlayerAll("config:public:DEFAULT_GROUP")
                    .stream().anyMatch(session -> !session.isActive()));
        } finally {
            for (ClientSession client : clients) {
                try {
                    client.close();
                } catch (Exception ignored) {
                }
            }
            server.prestop();
            server.stop();
        }
    }

    private int freePort() throws Exception {
        try {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            }
        } catch (SocketException e) {
            Assumptions.assumeTrue(false,
                    "Local socket binding is unavailable in this sandbox: " + e.getMessage());
            return -1;
        }
    }

    private void waitForPlayerCount(BrokerListener broker, String name, int expected)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (System.nanoTime() < deadline) {
            if (broker.getPlayerCount(name) == expected) {
                return;
            }
            Thread.sleep(10L);
        }
        assertEquals(expected, broker.getPlayerCount(name));
    }

    private void waitUntilBrokerHasInactiveSession(BrokerListener broker, String name)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (System.nanoTime() < deadline && broker.getPlayerAll(name)
                .stream().noneMatch(session -> !session.isActive())) {
            Thread.sleep(10L);
        }
    }
}
