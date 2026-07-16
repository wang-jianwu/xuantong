# Solon native Socket.D versus the SmartHTTP WebSocket bridge

## Contents

1. Two official integration paths
2. Native `solon-server-socketd` path
3. `ToSocketdWebSocketListener` bridge path
4. Async lifecycle mismatch
5. Review checklist
6. Version-specific source evidence

## 1. Two official integration paths

Solon and Socket.D document two different integrations. Do not treat them as equivalent merely because both accept `sd:ws` clients.

### Native Socket.D server

[Solon article 1144](https://solon.noear.org/article/1144) describes `org.noear:solon-server-socketd`, one or more Socket.D transport dependencies, `app.enableSocketD(true)`, and a Socket.D `Listener` registered with `@ServerEndpoint`. The article marks the Java WebSocket and Netty transports as recommended choices.

### Reuse Solon's WebSocket/HTTP port

[Socket.D article 783](https://socketd.noear.org/article/783) explicitly documents `ToSocketdWebSocketListener` as a bridge that converts Solon's WebSocket listener into Socket.D framing so applications can reuse the HTTP port. It describes this path as needing Socket.D core plus `solon-net`, without a Socket.D server transport.

Both paths are official. The architecture question is which path supplies the lifecycle and failure semantics required by the application.

## 2. Native `solon-server-socketd` path

For Solon 4.0.3, `SocketdPlugin`:

- starts only when `app.enableSocketD()` is true;
- creates native Socket.D TCP, UDP, and WebSocket servers when corresponding providers exist;
- shares an exchange executor among those native servers;
- binds the `SocketdRouter` listener directly;
- exposes them as Socket.D signals on their own socket ports;
- stops the native servers during plugin shutdown.

This path preserves Socket.D's own transport channel, send completion, close, heartbeat, and server lifecycle. It does not reuse SmartHTTP's WebSocket callback lifecycle.

## 3. `ToSocketdWebSocketListener` bridge path

The bridge path is:

```text
Socket.D frame
  → Solon WebSocket endpoint
  → ToSocketdWebSocketListener
  → Socket.D Processor/Listener
  → Solon WebSocket send
  → SmartHTTP WebSocket response/write buffer
```

Solon `NetPlugin` registers `@ServerEndpoint` instances in this order:

1. `WebSocketListenerSupplier`
2. `WebSocketListener`
3. Socket.D `Listener`

Because `ToSocketdWebSocketListener` implements `WebSocketListener`, an endpoint extending it is registered in the WebSocket router, not the native Socket.D router. Calling both `enableWebSocket(true)` and `enableSocketD(true)` does not change that endpoint's routing class.

## 4. Async lifecycle mismatch

The critical sequence in Solon 4.0.3 and SmartHTTP 2.5.19 is:

1. SmartHTTP receives a binary WebSocket frame and calls Solon's WebSocket listener.
2. `ToSocketdWebSocketListener.onMessage(ByteBuffer)` decodes the Socket.D frame and calls `Processor.onReceive`.
3. For a Socket.D request, `ProcessorDefault.onMessage` submits the application listener to Socket.D's work executor and returns.
4. The SmartHTTP WebSocket handler completes the current callback lifecycle as soon as `handleBinaryMessage` returns.
5. The application listener may later call `reply` or `replyEnd` from the Socket.D worker thread.

The reply write path weakens completion semantics further:

- `ToSocketdWebSocketListener.InnerChannelAssistant.write()` calls `target.send(buffer)` but ignores the returned `Future<Void>` and immediately reports successful completion to Socket.D.
- SmartHTTP's `WebSocketImpl.send(ByteBuffer)` calls `sendBinaryMessage`, calls `flush`, and completes its `Future` immediately after those method calls; it does not represent peer receipt.

This does not prove that every asynchronous reply is lost. It does prove that Socket.D is told “write completed” before the adapter can establish durable transport completion, and that business replies occur outside the inbound WebSocket callback that triggered them. Treat the combination as a race-prone adapter boundary and reproduce it under the exact server/version before relying on it for a correctness-critical request/reply control plane.

If application code sends extra pings to make an isolated reply arrive, record that as evidence of an adapter/output-lifecycle defect. A ping is not an acknowledgement for the original reply and must not become the permanent fix.

## 5. Review checklist

- Resolve the actual server dependency tree. `solon-web` commonly brings `solon-server-smarthttp`; it does not imply `solon-server-socketd` is present.
- Inspect the endpoint base type. `ToSocketdWebSocketListener` means bridge; a Socket.D `Listener` under the native plugin means native server path.
- Confirm the endpoint path and port used by clients.
- Check whether application listener execution is asynchronous relative to the WebSocket callback.
- Check whether the adapter waits for the WebSocket send future and propagates failures.
- Test replies sent immediately, after a short delay, and concurrently with close/preclose.
- Test a response after the inbound callback has returned.
- Test final close delivery and client reconnection after a server rollout.
- Do not use a native `SocketD.createServer("sd:ws")` unit/integration test as proof that the SmartHTTP bridge works.

For control-plane request/reply traffic, prefer native Socket.D transport on a dedicated signal/port unless the bridge has an end-to-end test proving its required delivery and shutdown semantics.

## 6. Version-specific source evidence

### Solon 4.0.3

`~/.m2/repository/org/noear/solon-net/4.0.3/solon-net-4.0.3-sources.jar`:

| Concern | Source | Key lines |
|---|---|---:|
| Endpoint registration precedence | `org/noear/solon/net/integration/NetPlugin.java` | 64-96 |
| Decode inbound frame | `org/noear/solon/net/websocket/socketd/ToSocketdWebSocketListener.java` | 128-140 |
| Bridge write ignores Future | same file | 218-235 |

`~/.m2/repository/org/noear/solon-server-socketd/4.0.3/solon-server-socketd-4.0.3-sources.jar`:

| Concern | Source | Key lines |
|---|---|---:|
| Start native Socket.D servers | `org/noear/solon/server/socketd/integration/SocketdPlugin.java` | 51-117 |
| Bind router, port, and server lifecycle | same file | 119-158 |

### SmartHTTP 2.5.19 through Solon 4.0.3

`~/.m2/repository/org/noear/solon-server-smarthttp/4.0.3/solon-server-smarthttp-4.0.3-sources.jar`:

| Concern | Source | Key lines |
|---|---|---:|
| Binary send and immediately completed Future | `org/noear/solon/server/smarthttp/websocket/WebSocketImpl.java` | 98-113 |
| Binary callback into Solon router | `org/noear/solon/server/smarthttp/websocket/SmWebSocketHandleImpl.java` | 150-160 |

`~/.m2/repository/org/noear/smart-http-server/2.5.19/smart-http-server-2.5.19-sources.jar`:

| Concern | Source | Key lines |
|---|---|---:|
| Per-frame callback Future and finish | `org/smartboot/http/server/WebSocketHandler.java` | 81-127 |
| Handler Future completes when synchronous callback returns | `org/smartboot/http/server/ServerHandler.java` | 33-41 |

Re-run this inspection for the project's resolved versions; line numbers and behavior may change.
