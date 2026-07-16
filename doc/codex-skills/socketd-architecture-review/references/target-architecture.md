# Target architecture blueprint for Socket.D control planes

## Contents

1. Architecture decision
2. Reference topology
3. Client routing and health
4. Config state and events
5. Discovery leases and views
6. Gray release boundaries
7. Shutdown
8. Migration
9. Required invariants

## 1. Architecture decision

Use Multi-Broker as address-level availability, not concurrent request fan-out.

Recommended shape:

- Native Socket.D on a dedicated control-plane listener.
- SmartHTTP retained for administration, REST, health, metrics, and audit.
- Brokers act as mostly stateless protocol gateways.
- Config truth, event propagation, and discovery lease ownership live in shared or authoritative server-side systems.
- A client selects an infrastructure-compatible Broker pool, then sends each attempt to one Broker.
- A failed call may use the remaining total deadline for one sequential failover.
- Writes reuse one operationId and require idempotency or fencing because the first Broker may have executed before its reply was lost.

## 2. Reference topology

~~~mermaid
flowchart TB
    subgraph CLIENT["Client SDK"]
        ID["Stable client identity and tags"]
        POOL["Infrastructure cohort router<br/>select compatible Broker pool"]
        SELECT["RPC-health Broker selector<br/>one Broker per attempt<br/>at most two sequential attempts"]
        STATE["ACTIVE / SUSPECT / DRAINING / CLOSED"]

        ID --> POOL --> SELECT
        SELECT --- STATE
    end

    subgraph CONTROL["Selected compatible Broker pool"]
        A["Native Socket.D Broker A"]
        B["Native Socket.D Broker B"]
    end

    SELECT <-->|"request / reply / active subscription"| A
    SELECT -.->|"bounded sequential failover"| B

    subgraph STATEFUL["Server-side state"]
        CFG[("Immutable config content<br/>rollout rules<br/>per-key decisionRevision")]
        OUT["Transactional outbox<br/>eventId / eventSequence"]
        BUS["Durable event stream<br/>independent Broker cursors"]
        LEASE[("Authoritative discovery leases<br/>linearizable or single-writer<br/>leaseId / epoch / TTL")]
        VIEW[("Replicated registry view<br/>registryRevision / Watch")]
    end

    A --> CFG
    B --> CFG
    CFG --> OUT --> BUS
    BUS --> A
    BUS --> B
    A <--> LEASE
    B <--> LEASE
    LEASE --> VIEW
    A --> VIEW
    B --> VIEW

    ADMIN["SmartHTTP administration plane"] --> CFG
~~~

Automatic failover must stay inside the selected compatibility pool. Crossing old/new Broker pools or native/bridge transports requires an explicit infrastructure rollout or rollback decision and a new connection.

## 3. Client routing and health

Keep connection state and request health separate.

Per-Broker state should include:

- physical connection status;
- closing status and closing deadline;
- consecutive RPC failures;
- last successful request/reply time;
- circuit or cooldown deadline;
- in-flight request/subscribe stream counts;
- selected infrastructure pool and transport generation.

Rules:

1. Socket.D ClusterClient connection activity is not sufficient RPC-health selection; add an outer selector.
2. Each attempt targets one Broker. Never race normal requests across all Brokers.
3. A single timeout may retry one standby sequentially if the total deadline has enough budget.
4. A consecutive-failure threshold decides when the Broker becomes SUSPECT and is removed from new routing.
5. SUSPECT and DRAINING Brokers never receive new work.
6. Recover to ACTIVE only after a real request/reply or validated application health exchange.
7. Preserve the stable session shell returned by Client.open(); do not store a transient onOpen real-channel session.

Sequential retry is not exactly-once execution. Every retried write needs:

- stable operationId across attempts;
- a retained operationId-to-result record, or an equivalent idempotency mechanism;
- lease epoch or another fencing token where ownership changes;
- a total deadline shared by all attempts.

## 4. Config state and events

Separate these identifiers:

| Identifier | Scope | Purpose |
|---|---|---|
| contentRevision | immutable content | Identify payload content |
| decisionRevision | one configKey | Identify targeting or rollback decision changes |
| eventId/eventSequence | Outbox partition or stream | Deduplicate and order event delivery |
| Broker cursor | one Broker consumer | Recover Broker event consumption |
| snapshot token | client reconciliation scope | Recover client state after missed pushes |

A configKey includes namespace, group, and dataId.

The administration transaction writes:

- immutable config content;
- release decision;
- rollout rule;
- a higher per-key decisionRevision;
- an Outbox row with eventId/eventSequence.

Event requirements:

- at-least-once delivery;
- ordering per configKey;
- Relay idempotency;
- Broker consumer idempotency by eventId;
- each Broker receives relevant events through its own cursor or broadcast identity.

Push only an invalidation:

~~~text
{
  namespace,
  group,
  dataId,
  decisionRevision,
  eventId
}
~~~

Clients deduplicate by configKey and decisionRevision, then pull:

~~~text
findApplicableRelease(clientIdentity, namespace, group, dataId)
~~~

On reconnect, reconcile per-key revisions or use a server-issued snapshot token. Do not use one config decisionRevision as the global event cursor.

## 5. Discovery leases and views

An eventually consistent replicated map is insufficient for lease ownership.

The authoritative path must provide linearizable or single-writer semantics for:

- atomic lease creation;
- server-assigned epoch;
- renew conditioned on leaseId and epoch;
- deregister conditioned on leaseId and epoch;
- expiration based on authoritative server time;
- explicit takeover with a higher epoch.

Query and Watch views may be replicated asynchronously and carry a monotonic registryRevision.

Register retry rules:

1. The first register creates leaseId and epoch.
2. The same operationId replay returns the original leaseId and epoch.
3. Switching Brokers renews the existing lease when it remains valid.
4. Only expiration or explicit takeover creates a higher epoch.
5. Old heartbeat and deregister operations are rejected by conditional updates.
6. Idempotency records retain operationId-to-result long enough to cover retry and late-message windows.

## 6. Gray release boundaries

Treat infrastructure canary and configuration gray release as independent state machines.

Infrastructure canary:

- selects a compatible old or new Broker pool;
- constrains normal failover to that pool;
- changes transport or pool only through an explicit rollout/rollback action;
- rebuilds the connection when the selected pool changes.

Configuration gray release:

- stores immutable content separately from rollout rules;
- uses stable client identity and trusted tags;
- uses a stable rolloutKey and seed for deterministic percentage selection;
- evaluates the same applicable-release function on every Broker;
- increments decisionRevision on rollback even when pointing to old content.

Push-triggered pull, direct fetch, periodic repair, and any healthy Broker must select the same content for the same client identity.

## 7. Shutdown

For Broker binary deployment, rollback, or shutdown:

1. Remove the Broker from new infrastructure routing.
2. Send preclose.
3. Mark the Session DRAINING and stop new work.
4. Return a recognizable retryable draining error for racing new requests.
5. Wait for a bounded drain period.
6. Send final close and close the physical channel.
7. If final close is lost, the client closing deadline forces close and reconnect.

Configuration publish or rollback does not preclose transport Sessions.

## 8. Migration

Recommended order:

1. Disable incomplete gray APIs and add request/session observability.
2. Fix stable session-shell storage, remove config request fan-out, add RPC-health selection and closing deadlines, introduce operation IDs, and do not auto-retry non-idempotent writes until server-side idempotency or fencing exists.
3. Add the native Socket.D listener beside the bridge. Select native or bridge by explicit infrastructure cohort; never send one attempt to both.
4. Add transactional Outbox and durable Broker event consumption; then reduce config push to one active subscription.
5. Move discovery ownership to the authoritative lease registry and remove client-side multi-write/union.
6. Implement real gray rules, deterministic selection, decisionRevision rollback, and push/pull agreement.
7. Remove bridge endpoints, ping workarounds, request fan-out, and client-side registry federation after the rollback window closes.

During the interval between steps 3 and 4, legacy push subscription coverage may remain temporarily, but requests must not return to fan-out and notifications must be deduplicated per configKey.

## 9. Required invariants

1. Each attempt targets one Broker; concurrent fan-out is not failover.
2. Sequential retry uses one total deadline and at most one standby.
3. Retried writes produce one business effect through idempotency or fencing.
4. Automatic failover never crosses an infrastructure compatibility pool.
5. Physical liveness and RPC health are measured independently.
6. Config decisionRevision, event sequence, Broker cursor, and client snapshot token are distinct concepts.
7. Every Broker can recover the complete config event stream relevant to its clients.
8. Discovery lease ownership and epoch changes use authoritative conditional writes.
9. DRAINING has a bounded path to CLOSED even when final close is lost.
10. Infrastructure rollback and configuration rollback remain independent.
