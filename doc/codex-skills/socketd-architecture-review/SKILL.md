---
name: socketd-architecture-review
description: Diagnose and review Socket.D architectures, especially Solon/SmartHTTP WebSocket bridges, Socket.D request timeouts, reconnect and preclose failures, Broker or Multi-Broker designs, ClusterClient semantics, gray releases and rollbacks, and control-plane long connections. Use for Java projects that depend on org.noear Socket.D/Solon, contain ToSocketdWebSocketListener, sendAndRequest, BrokerListener, createClusterClient, custom multi-broker transports, or repeatedly log timeouts after rolling deployment or rollback.
---

# Socket.D Architecture Review

## Goal

Determine whether a failure belongs to the application protocol, transport adapter, connection state machine, request-stream lifecycle, deployment model, or data-consistency design. Produce an evidence-backed architecture decision and a rollout-safe remediation plan.

## Workflow

1. Establish the exact dependency graph and runtime path.
   - Record Socket.D, Solon, HTTP server, WebSocket transport, and JDK versions.
   - Distinguish a native Socket.D server/transport from `ToSocketdWebSocketListener` running through a general WebSocket server.
   - Confirm which `@ServerEndpoint` router actually owns each endpoint.

2. Trace one failed request end to end.
   - Follow request creation, stream registration, frame write, server dispatch, reply write, stream removal, timeout, close, heartbeat, and reconnect.
   - Inspect asynchronous boundaries and whether a reported write completion means queued, flushed, or delivered.
   - Treat `isActive()` as connection state only; verify request/reply health separately.

3. Reconstruct the deployment state machine.
   - Model old version, canary version, rollback, preclose, final close, reconnect, and periodic reconciliation.
   - Distinguish infrastructure canary deployment from application configuration gray release; they have different selectors and rollback state.
   - Check whether a session can remain valid-but-unusable or closing indefinitely.
   - Identify which repeated timer, heartbeat, retry, or reconciliation task explains persistent logs.

4. Audit multi-endpoint semantics.
   - Separate connection redundancy from per-request fan-out.
   - Compare custom code with Socket.D `ClusterClientSession`, which normally selects one active session per operation.
   - Verify whether cancelling an application `Future` cancels the underlying Socket.D stream.
   - Evaluate config reads, push subscriptions, discovery registration, heartbeats, and view merging independently.

5. Audit release semantics.
   - Verify that gray rules contain selectors, stable identity, deterministic matching, and an applicable-release query.
   - Verify that push and pull paths choose the same release for the same client.
   - Treat a release-type label without selection logic as metadata, not gray delivery.

6. Recommend the smallest safe target architecture.
   - Prefer native Socket.D transport for correctness-critical control-plane traffic.
   - Send each attempt to one healthy Broker, then fail over sequentially within one total deadline; never use concurrent fan-out as failover.
   - Keep infrastructure cohort routing outside Broker failover so automatic retry cannot cross old/new or native/bridge compatibility pools.
   - Require operation idempotency or fencing whenever a retry could repeat a write.
   - Move shared state and event propagation into the Broker tier instead of making every client federate every Broker.
   - Separate per-config decision revisions from event-stream cursors.
   - Require authoritative lease ownership and server-assigned epochs for discovery writes; replicated views alone are insufficient.
   - Preserve multi-write only where the business state is intentionally Broker-local and document its consistency contract.
   - Plan a dual-port or dual-endpoint migration when replacing a live bridge with native transport.

7. Verify with a rollout matrix.
   - Test native and bridged transports separately.
   - Cover old→new, mixed old/new, rollback, preclose without final close, half-open connection, dropped reply, delayed reply, reconnect storm, and multi-Broker partial failure.
   - Assert bounded warning rates, stream cleanup, session replacement, and deterministic client-visible revisions.

## Guardrails

- Do not call every request on every Broker merely because multiple connections exist.
- Do not assume `Future.cancel(true)` removes a Socket.D `RequestStream`.
- Do not use protocol pings as a permanent substitute for correct reply delivery or connection failure detection.
- Do not depend on `preclose()` alone; require a bounded final close and client-side closing timeout.
- Do not claim one business effect merely because retries are sequential; require `operationId`, idempotency, or fencing for writes.
- Do not use a config decision revision as the global Outbox or event-stream cursor.
- Do not let automatic failover cross an infrastructure gray-release compatibility pool.
- Do not recommend a gray release until both push and pull enforce the same targeting rule.
- Do not claim an integration test covers production transport unless it starts the same server adapter and endpoint path.

## References

- Read [references/socketd-semantics.md](references/socketd-semantics.md) for protocol, stream, timeout, close, reconnect, Broker, and ClusterClient semantics.
- Read [references/solon-smart-http-bridge.md](references/solon-smart-http-bridge.md) whenever Solon, SmartHTTP, `ToSocketdWebSocketListener`, or `solon-server-socketd` is involved.
- Read [references/multi-broker-rollout-review.md](references/multi-broker-rollout-review.md) for architecture decisions, gray-release checks, failure chains, and test matrices.
- Read [references/target-architecture.md](references/target-architecture.md) when producing a target architecture, migration plan, config event design, discovery lease model, or architecture diagram.
- Read [references/socketd-docs-digest.md](references/socketd-docs-digest.md) when a question depends on coverage or details from the full official documentation corpus.
- Consult [references/socketd-docs-index.md](references/socketd-docs-index.md) for the complete crawled official documentation index and content hashes.
- Run `scripts/crawl_socketd_docs.py --output <directory>` when current official documentation must be re-enumerated or verified. Compare the generated index and hashes before updating conclusions.
