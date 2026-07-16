# Multi-Broker, rollout, and gray-release architecture review

## Contents

1. Decision summary
2. Failure-chain template
3. Config-plane design
4. Discovery-plane design
5. Gray release and rollback
6. Session and request health
7. Rollout test matrix
8. Xuantong case study

## 1. Decision summary

Keep the goal of multiple Broker availability, but do not equate it with “every client sends every operation to every Broker.” Review these dimensions independently:

| Dimension | Usually appropriate | Usually inappropriate |
|---|---|---|
| Connection redundancy | Multiple known addresses, one active plus standby, bounded failover | Treating every open socket as equally healthy forever |
| Config reads | One Broker per read against a shared source of truth | Racing every read across every Broker |
| Config push | Broker-tier event propagation; one active subscription or deduplicated subscriptions | Client must connect all Brokers because events are node-local |
| Discovery registration | One replicated/shared registry, or an explicit quorum contract | Unspecified multi-write to independent in-memory registries |
| Discovery reads | One consistent replicated view | Client-side union without staleness/removal semantics |
| Lifecycle | Preclose, bounded drain, final close, closing deadline | Preclose with no guaranteed final state transition |

Socket.D's built-in ClusterClient supports multiple sessions but selects one active session for each normal send/request/subscribe. Use broad fan-out only as an explicit business operation with idempotency, cancellation, and aggregation semantics.

## 2. Failure-chain template

For “rolling deployment or rollback causes permanent timeout logs,” test this chain:

1. A release or rollback causes binary push/reply traffic while old and new server versions coexist.
2. A transport bridge queues or flushes a reply but reports completion before transport completion or peer receipt.
3. The physical connection remains valid/open, so Socket.D still reports it active.
4. `sendAndRequest` times out, but the timeout removes only its stream and does not invalidate the channel.
5. Heartbeat continues to succeed, or application pings keep generating I/O, so automatic reconnect never replaces the half-working connection.
6. A periodic reconciliation, registration heartbeat, or reconnect callback issues the same RPC again.
7. Multi-Broker fan-out repeats the RPC on every active session and logs one warning per failed Broker.

Also test the distinct preclose path:

1. Server sends protocol close code 1000.
2. Client enters closing state and heartbeat stops advancing reconnect.
3. Final close code 1001 or physical close is lost.
4. Session remains closing until an explicit deadline forces close/reconnect.

These are separate failure states: active-but-RPC-dead versus valid-but-closing. Instrument them separately.

## 3. Config-plane design

When all Brokers share the same durable configuration database, choose one Broker per read. Recommended shape:

```text
client
  ├─ active native Socket.D session ── Broker A ─┐
  └─ standby address/session          Broker B ─┼─ shared release DB
                                                └─ shared event log/outbox
```

Requirements:

- Route each `get` to one healthy Broker.
- A single failed call may retry one standby Broker sequentially within the same total deadline. Consecutive-failure thresholds separately decide when to mark a Broker suspect, stop new routing, and close/reconnect it.
- Socket.D ClusterClient selects by connection activity; add an outer selector for RPC health, circuit state, and infrastructure cohort boundaries.
- Use a durable shared event mechanism—transactional outbox plus a database event log, Redis Streams, NATS JetStream, Kafka, or equivalent—so every Broker can notify its connected clients and recover after restart.
- Prefer pushing a full config key plus its decision revision; let the client pull the release applicable to its identity.
- Keep per-config decision revisions separate from the Outbox/event-stream event id and Broker consumption cursor.
- Use at-least-once event delivery, order events per config key, and make Relay and Broker consumers idempotent by event id.
- Coalesce reconnect-triggered reconciliation so several session transitions do not launch several full reloads.
- Apply jitter and exponential backoff to periodic repair.

Do not keep request fan-out merely to compensate for node-local event delivery. Fix event propagation in the Broker tier.

If a fan-out implementation treats “not found” as an error-free response, the first Broker to return absence can win over a slower Broker that has the value. This is nondeterministic whenever Broker stores are not actually identical.

## 4. Discovery-plane design

First determine where service-instance truth lives.

### Shared or replicated registry

Prefer an authoritative registry for the long-term design. Lease ownership, epoch allocation, renew, and conditional delete require linearizable or single-writer semantics. Query and Watch views may be replicated asynchronously. Then clients use one active Broker, and any Broker can serve a revisioned view without accepting stale lease mutations.

### Intentionally Broker-local registry

If every Broker intentionally owns an independent in-memory registry, client multi-write and union are not transport failover; they are application-level federation. Document and implement:

- stable instance and lease identity;
- per-Broker registration/heartbeat success state;
- quorum or minimum-success policy;
- retry and re-registration policy;
- per-Broker revisions and view staleness bounds;
- removal behavior when one Broker disconnects;
- deduplication of the same logical instance;
- capacity model of `clients × Brokers` connections and heartbeats.

Avoid hiding this contract inside a generic “multi-broker transport.” It is a discovery consistency subsystem.

## 5. Gray release and rollback

First distinguish two independent rollouts:

- **Infrastructure canary:** old and new Broker/server binaries coexist; routing selects server versions and transport compatibility matters.
- **Configuration gray release:** one immutable configuration revision applies only to a selected client cohort.

Rolling back server binaries does not automatically roll back configuration selection, and rolling back a configuration does not repair a half-working transport session. Test and observe both state machines separately.

A gray release needs more than a release-type enum. Minimum model:

- stable client identity, such as `clientInstanceId`;
- selector type and selector data: IP/CIDR, tags, application cohort, or percentage;
- deterministic percentage function, for example `hash(rolloutKey, clientInstanceId, seed) mod 100`, where rolloutKey remains stable for the rollout policy and does not change merely because content is republished;
- stable release fallback;
- rule activation/deactivation and validity window;
- an applicable-release query evaluated identically by every Broker.

Both paths must agree:

```text
push decision(client, release) == pull selection(client, dataId)
```

Safe approach:

1. Store immutable release content and a separate rollout rule.
2. Push only an invalidation/revision notification, optionally to all clients.
3. On fetch, evaluate `findApplicableLatest(clientIdentity, dataId)`.
4. On rollback, atomically deactivate the gray rule and create a new stable rollback revision or restore the prior stable selection.
5. Ensure every Broker reads the same rule and produces the same answer.

If the data model contains only `FULL`, `GRAY_IP`, `GRAY_PERCENTAGE`, and `ROLLBACK` labels, but no selector fields or applicable-release query, gray delivery is not implemented. Disable the gray API until the contract exists.

## 6. Session and request health

Maintain separate state:

```text
DISCONNECTED → CONNECTING → ACTIVE → SUSPECT → DRAINING → CLOSED
                                  ↘ timeout threshold ↗
```

- `ACTIVE` requires successful request/reply health, not just an open socket.
- Move to `SUSPECT` after a bounded number of consecutive request timeouts.
- A single call timeout may use the remaining total deadline for one sequential failover even before the consecutive-failure threshold is reached.
- Stop routing new work to `SUSPECT` or `DRAINING` sessions.
- Force close/reconnect after a closing deadline if final close is absent.
- Reset failure counters only after a real successful request/reply or validated application health exchange.
- Rate-limit repeated timeout logs and expose counters by Broker and state.

Preserve the stable client session shell returned by `Client.open()`. Do not replace it with a transient real-channel session received in an early `onOpen` callback; doing so can bypass the client wrapper's send-time checks and reconnect behavior.

For infrastructure canaries, select a compatible Broker pool first. Normal failover must remain inside that pool. Crossing old/new Broker pools or native/bridge transports requires an explicit rollout or rollback decision and a new connection.

Sequential retry does not guarantee one execution. If the first Broker executes a write but its reply is lost, the standby can receive the same write. Reuse one `operationId` and store `operationId → result`, or use lease epochs/fencing to guarantee one business effect.

## 7. Rollout test matrix

Run every case against the production transport adapter, not only a native in-memory or direct Socket.D server.

| Scenario | Required assertion |
|---|---|
| Old client → old server | Baseline request, push, close, reconnect |
| Old client → new server | Explicit compatibility result; no silent half-open state |
| New client → old server | Same |
| Mixed old/new Brokers | One request reaches one compatible Broker; failures trigger bounded failover |
| Canary publish | Only deterministic target cohort observes gray content on push and pull |
| Gray rollback | All target clients converge to rollback revision; non-target clients never saw gray content |
| Preclose then final close | No new work routed after preclose; reconnect occurs after final close |
| Preclose without final close | Client closing deadline forces replacement |
| Dropped reply on open socket | Timeout marks session suspect and bounded failover succeeds |
| Delayed reply after timeout | Late reply is discarded; stream map remains bounded |
| Reconnect storm | Reconciliation is coalesced and jittered |
| One of N Brokers broken | Warning rate remains bounded; normal request count is not multiplied by N |
| Duplicate push | Monotonic revision deduplication prevents duplicate application update |

Collect: session state, close code, consecutive RPC timeouts, stream-map size, selected Broker, retry count, push revision, applicable release, and warning rate.

## 8. Xuantong case study

The following findings are specific to the Xuantong repository at the inspected revision. Resolve file references relative to the repository root on the current machine.

### Transport path

- `xuantong-server` resolves `solon-web → solon-server-smarthttp 4.0.3 → smart-http 2.5.19`.
- It includes `solon-net 4.0.3` and `socketd-transport-java-websocket 2.6.0`, but not `solon-server-socketd`.
- `ConfigBrokerV2Endpoint` and `DiscoveryBrokerV2Endpoint` extend `ToSocketdWebSocketListener`, so `/config-v2` and `/discovery-v2` use the SmartHTTP bridge.
- `SocketDRpcSupport` sends pings at several checkpoints because its own comment records that SmartHTTP 2.5.x can leave an isolated binary reply pending until later WebSocket I/O. This is a workaround, not a transport guarantee.
- Server shutdown already attempts `preclose → bounded wait → final close`; the remaining risk is final-close delivery through the bridge and the absence of a client-side closing deadline.

### Config Multi-Broker

- `SocketDTransport.fetch()` submits the same `/get` request to every active Broker, returns the first success, and cancels executor Futures for the rest.
- Socket.D `RequestStream` has no cancel API, so the underlying losing streams are not cancelled by those Futures.
- Each Broker is opened through a separate `SocketD.createClient(...)`; unlike the built-in `ClusterClient`, these clients do not share Socket.D's work executor.
- `ConfigCore` reconciles every cached key every 30 seconds and also reloads on session open/close callbacks, explaining repeated traffic and warnings.
- Config releases are stored in a shared database, while push events use process-local Solon `EventBus`; connecting clients to all Brokers currently compensates for missing Broker-tier event propagation.
- `fetch()` treats a Broker's `found=false` as a successful result, so first-success fan-out can return nondeterministic absence if Broker databases differ.
- The default datasource is a node-local H2 file. Multi-Broker consistency therefore requires an explicitly configured shared external database; it is not true under defaults.

### Session identity bug

- Socket.D's initial `onOpen` listener runs before `ClientChannel.connect()` replaces the real channel's internal session with the stable session shell.
- `SocketDTransport.handleOpened()` and `SocketDDiscoveryTransport.handleOpened()` store that callback session.
- `openAllSessions()` later calls `putIfAbsent` with the stable session returned by `.open()`, so the transient real-channel session can remain stored.
- Requests then bypass `ClientChannel.send()` and its send-time reconnect checks. Store the returned stable shell; use callbacks only to update readiness/recovery state.

### Gray release

- `ReleaseType` declares `GRAY_IP` and `GRAY_PERCENTAGE`.
- `ConfigRelease` stores only the label; it has no target IP, percentage, selector, seed, cohort, or rule fields.
- `ConfigBrokerV2Listener` broadcasts every release to `subscriber*`.
- `/get` calls `findLatest(...)` without client identity or rule evaluation.
- The 30-second reconciliation fetch therefore makes any “gray” revision visible to every client.

The current gray types are audit labels, not gray-delivery semantics.

### Discovery Multi-Broker

- Providers register and heartbeat on every active Broker.
- Each Broker keeps service instances in local memory and emits local events.
- The client merges independent Broker views in `MultiBrokerServiceState`.

This explains why multi-connection exists, but it is client-side federation with `clients × Brokers` scaling. Treat it as temporary until registry state and events are shared or replicated.

### Recommended migration order

1. Disable or hide `GRAY_IP` and `GRAY_PERCENTAGE` until selectors and applicable-release queries exist.
2. Remove the session-shell overwrite bug and add request-health state plus bounded failover.
3. Stop config read fan-out; choose one active Broker per request.
4. Move control-plane endpoints to native Socket.D WebSocket transport on a dedicated port; keep SmartHTTP for administration and HTTP APIs.
5. Add Broker-tier config event propagation so a client does not need every Broker connection for push.
6. Move discovery instance state to a shared/replicated registry, then collapse discovery clients to one active Broker.
7. Run the full rollout matrix before re-enabling gray release.

### Dual-stack transport migration

1. Add a native Socket.D endpoint/port while keeping the existing bridge available.
2. Add client capability/configuration to prefer native transport and fall back to the bridge only during migration.
3. Canary a small client cohort and compare request success, timeout rate, stream cleanup, close/reconnect, and push revision parity.
4. Move cohorts gradually; do not fan out one logical request to both transports.
5. Keep a bounded rollback window in which clients can select the old endpoint explicitly.
6. After all supported clients have moved and the rollback window closes, remove the bridge and its ping workaround.
