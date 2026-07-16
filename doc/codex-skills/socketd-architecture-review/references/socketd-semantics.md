# Socket.D semantics relevant to architecture reviews

## Contents

1. Documentation corpus coverage
2. Protocol and stream model
3. Timeout and liveness model
4. Close, preclose, and reconnect
5. ClusterClient and multi-connection semantics
6. Broker semantics
7. Version-specific source evidence

## 1. Documentation corpus coverage

The official site had no usable sitemap at the time of the crawl. `robots.txt` allowed crawling. A breadth-first crawl started from the home page and the three top-level documentation sections, followed every same-origin `/article/*` link, normalized relative links and removed query/fragment variants, then downloaded each page through `?format=md`.

Result:

- 120 HTML pages visited, including the home page
- 119 article pages discovered
- 119 Markdown pages downloaded
- 0 failures
- 206,198 Markdown characters in the first complete crawl

See [socketd-docs-index.md](socketd-docs-index.md) for every title, URL, character count, and SHA-256. Re-run `../scripts/crawl_socketd_docs.py` when freshness matters.

## 2. Protocol and stream model

Socket.D is an event-oriented application protocol over transports such as TCP, UDP, WebSocket, and KCP. Frames carry a flag and an optional message containing `sid`, event, metadata, and data. The `sid` associates request and reply frames into a logical stream. See [protocol overview](https://socketd.noear.org/article/protocol), [instruction flow](https://socketd.noear.org/article/768), and [frame codes](https://socketd.noear.org/article/769).

The three outbound interaction models are:

- `send`: no reply demand, analogous to Qos0.
- `sendAndRequest`: exactly one reply is required; `Reply` and `ReplyEnd` both finish it.
- `sendAndSubscribe`: zero or more replies until `ReplyEnd` or timeout.

See [basic send interfaces](https://socketd.noear.org/article/704), [listener/session/entity](https://socketd.noear.org/article/718), [session interface](https://socketd.noear.org/article/719), and [stream interfaces](https://socketd.noear.org/article/772).

Architecture implication: request/reply correctness is a separate property from physical connection liveness. A WebSocket can remain open while a request stream receives no reply.

## 3. Timeout and liveness model

The official timeout documentation defines these defaults: `idleTimeout=60s`, `requestTimeout=10s`, `streamTimeout=2h`, `connectTimeout=10s`, and `heartbeatInterval=20s`. See [timeout concepts](https://socketd.noear.org/article/775) and [heartbeat](https://socketd.noear.org/article/822).

Important distinctions:

- `requestTimeout` limits one `sendAndRequest` wait.
- `streamTimeout` is the long-lived stream insurance timeout and may also be selected explicitly.
- `idleTimeout` is server-side connection idleness; regular client heartbeats prevent it.
- A request timeout does not, by itself, prove that the channel is invalid.

In Socket.D 2.6.0 Java source:

- `RequestStreamImpl.await()` waits on a `CompletableFuture` and throws `SocketDTimeoutException` when its timeout expires.
- `StreamBase.insuranceStart()` later removes the stream and reports a stream timeout.
- `StreamMangerDefault` owns the stream map; it removes an entry on reply completion or insurance timeout.
- `RequestStream` has no cancellation method.

Therefore cancelling an executor `Future` that happens to call `await()` only interrupts application work. It does not directly remove or cancel the underlying Socket.D stream. The stream remains until a reply, alarm, or its own timeout removes it.

## 4. Close, preclose, and reconnect

The official close documentation defines:

- `session.preclose()` sends protocol close code 1000 but keeps the connection open. The session becomes closing and should not receive new work; in-flight sends may finish.
- `session.close()` sends protocol close code 1001 and closes the connection.
- Safe shutdown is `preclose → bounded wait → close`.

See [close and safe close](https://socketd.noear.org/article/807).

The reconnect documentation states that heartbeat and send paths reconnect invalid sessions, but a locally closed session or a session closed through protocol instruction is an exception and requires explicit `reconnect()`. See [automatic reconnect](https://socketd.noear.org/article/808).

Socket.D 2.6.0 Java source adds an important failure mode:

- `ChannelDefault.isClosing()` is true only while internal close code is 1000.
- `SessionBase.isActive()` is `isValid() && !isClosing()`.
- `ClientChannel.heartbeatHandle()` returns immediately when the real channel is closing.
- The connection is replaced automatically when invalid or when send/heartbeat detects an error.

If a client receives preclose but never receives final close and the transport still reports valid, heartbeat does not advance reconnection. A client-side closing deadline or guaranteed final close is required to prevent a permanent closing state.

## 5. ClusterClient and multi-connection semantics

The official documentation distinguishes single connection from multiple connections. A `ClusterClientSession` exposes all sessions and one selected session. To send broadly, the documentation explicitly loops over `getSessionAll()`; normal `session.send(...)` is a single send. See [single and multiple connections](https://socketd.noear.org/article/786), [create client session](https://socketd.noear.org/article/738), and [join a Broker cluster](https://socketd.noear.org/article/712).

Socket.D 2.6.0 Java source is unambiguous:

- `ClusterClient` creates a client/session for each URL and reuses one work executor across clients.
- `ClusterClientSession.getSessionAny()` chooses one active session by round-robin or hash.
- `send`, `sendAndRequest`, and `sendAndSubscribe` each delegate to one selected session.
- `preclose`, `close`, and `reconnect` iterate over all sessions because those are lifecycle operations.

Architecture implication: keeping multiple sessions for availability is compatible with selecting one Broker per operation. Racing every request across all Brokers is a custom fan-out policy, not the default ClusterClient behavior.

## 6. Broker semantics

The Broker model lets named Players connect to one or more Broker centers. Broker routing uses the entity `at` value:

- `name`: one named session, round-robin.
- `name!`: one named session, IP hash.
- `name*`: all sessions with that name.
- `*`: all sessions other than the sender.

See [Broker mode](https://socketd.noear.org/article/737), [architecture diagram](https://socketd.noear.org/article/742), [start a Broker](https://socketd.noear.org/article/711), and [BroadcastBroker](https://socketd.noear.org/article/819).

The docs describe Multi-Broker as Players joining multiple Broker centers for availability. They do not require every application request to be sent to every center. Whether to multi-write registration or merge Broker-local state is an application consistency decision, not a protocol guarantee.

For WebSocket, Socket.D v2.5 and later added subprotocol validation. Mixed versions may require coordinated client/server upgrades. See [v2.5 compatibility note](https://socketd.noear.org/article/820) and [WebSocket subprotocol validation](https://socketd.noear.org/article/821).

## 7. Version-specific source evidence

For Maven version 2.6.0, inspect these files in `~/.m2/repository/org/noear/socketd/2.6.0/socketd-2.6.0-sources.jar`:

| Concern | Source | Key lines |
|---|---|---:|
| Open all cluster sessions and share executor | `org/noear/socketd/cluster/ClusterClient.java` | 80-124 |
| Select one session per operation | `org/noear/socketd/cluster/ClusterClientSession.java` | 45-58, 127-161 |
| Active-session filter and round-robin/hash | `org/noear/socketd/cluster/LoadBalancer.java` | 30-72 |
| Request wait timeout | `org/noear/socketd/transport/stream/impl/RequestStreamImpl.java` | 55-70 |
| Stream insurance removal | `org/noear/socketd/transport/stream/impl/StreamBase.java` | 69-84 |
| Stream map lifecycle | `org/noear/socketd/transport/stream/impl/StreamMangerDefault.java` | 42-82 |
| Request stream API has no cancel | `org/noear/socketd/transport/stream/RequestStream.java` | 12-21 |
| Session active definition | `org/noear/socketd/transport/core/impl/SessionBase.java` | 114-117 |
| Preclose and final close codes | `org/noear/socketd/transport/core/impl/SessionDefault.java` | 277-314 |
| Closing heartbeat behavior | `org/noear/socketd/transport/client/ClientChannel.java` | 61-118 |
| Send-time reconnect checks | `org/noear/socketd/transport/client/ClientChannel.java` | 188-214, 257-297 |
| Reply removes stream | `org/noear/socketd/transport/core/impl/ProcessorDefault.java` | 364-393 |

Re-check source for the exact resolved version instead of applying these line numbers blindly to a different release.
