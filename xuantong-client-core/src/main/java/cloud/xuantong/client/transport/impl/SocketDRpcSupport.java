package cloud.xuantong.client.transport.impl;

import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.Session;
import org.noear.socketd.transport.stream.RequestStream;

/**
 * Shared Socket.D request/reply handling for the Solon SmartHTTP WebSocket bridge.
 *
 * SmartHTTP 2.5.x can leave an isolated binary reply pending until the next
 * WebSocket I/O. Transport pings are side-effect free and progressively advance
 * the channel while a request is outstanding. The bounded schedule stays within
 * the normal five-second RPC timeout and adds no traffic after completion.
 */
final class SocketDRpcSupport {
    private static final long[] PING_CHECKPOINTS_MS =
            new long[]{50L, 150L, 300L, 550L, 950L, 1_600L, 2_600L, 4_100L};

    private SocketDRpcSupport() {
    }

    static Entity request(
            ClientSession session, String event, Entity entity, long timeoutMs) throws Exception {
        RequestStream request = session.sendAndRequest(event, entity, timeoutMs);
        if (!(session instanceof Session)) {
            return request.await();
        }

        long previousCheckpoint = 0L;
        for (long checkpoint : PING_CHECKPOINTS_MS) {
            if (checkpoint >= timeoutMs) {
                break;
            }
            Thread.sleep(checkpoint - previousCheckpoint);
            previousCheckpoint = checkpoint;
            if (request.isDone()) {
                break;
            }
            ((Session) session).sendPing();
        }
        return request.await();
    }
}
