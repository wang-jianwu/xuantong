package cloud.xuantong.gateway.socketd;

import cloud.xuantong.protocol.v2.Envelope;

import java.util.concurrent.CompletionStage;

public interface ControlPlaneRequestHandler {
    String event();

    CompletionStage<ControlPlaneReply> handle(
            ControlPlaneRequestContext context, Envelope request);
}
