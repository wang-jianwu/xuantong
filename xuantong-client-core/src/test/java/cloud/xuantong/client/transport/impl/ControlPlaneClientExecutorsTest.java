package cloud.xuantong.client.transport.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPlaneClientExecutorsTest {
    @Test
    void everyTransportSharesOneBoundedSocketdWorkExecutor() {
        assertSame(
                ControlPlaneClientExecutors.socketdWorkExecutor(),
                ControlPlaneClientExecutors.socketdWorkExecutor());
        assertTrue(ControlPlaneClientExecutors.workThreadLimit() >= 4);
        assertTrue(ControlPlaneClientExecutors.workThreadLimit() <= 16);
        assertTrue(ControlPlaneClientExecutors.workQueueDepth() >= 0);
        assertFalse(ControlPlaneClientExecutors.socketdWorkExecutor().isShutdown());
    }
}
