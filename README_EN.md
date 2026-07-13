# Xuantong

<p align="center">
  <a href="./README.md">简体中文</a> ｜ <strong>English</strong>
</p>

<p align="center">Lightweight microservice control plane · Configuration releases · Service governance</p>

## 2.0

The main branch is now `2.0.0-SNAPSHOT`. It adopts the 2.0 architecture directly and provides no compatibility layer for 1.x APIs, schemas, pages, or client protocols.

Every configuration is addressed by one canonical tuple:

```text
namespace + group + dataId
```

```text
Namespace
└── Group
    ├── Config: dataId
    └── Service: serviceName
        └── Instance: instanceId
```

See [PLAN_2.0.md](PLAN_2.0.md) for the design and current progress, and [RELEASE_NOTES_2.0.0.md](RELEASE_NOTES_2.0.0.md) for the release summary.

## Design highlights

- Single-process deployment for the admin UI, persistence, and Socket.D endpoint.
- Embedded H2 by default, with no mandatory middleware dependency.
- Drafts are never exposed to clients; clients only read published releases.
- Every publish creates an immutable, monotonically increasing revision. Rollback creates a new release.
- Batch publishing validates the full batch before atomically writing releases and audit records.
- Clients subscribe to `config:{namespace}:{group}` for precise Group-level routing.
- Local snapshots are isolated by `namespace/group`.
- Service definitions are persisted, while heartbeat-driven service instances remain in memory.
- Socket.D multi-broker clients keep simultaneous connections to every Broker; Brokers remain independent and do not replicate events to each other.
- Providers register and heartbeat the same lease on every Broker, while consumers merge the reachable Broker views.
- The unified admin UI opens on a runtime dashboard with health, release, service-instance, token, security, and JVM metrics.
- Config operations include release/audit history, rollback from any immutable release, and deletion of unpublished drafts.
- Security operations include client-token issue/revoke, global audit logs, and visual Namespace/Group user-scope management.
- Connection observability separates Config and Discovery sessions, deduplicates logical clients by `clientId`, and retains the physical Socket.D connection view for the current Broker.
- The responsive UI provides one shared sidebar, persistent theme controls, custom confirmation dialogs, and table-contained scrolling on mobile screens.

## Start the server

JDK 21 is required:

```bash
mvn -pl xuantong-server -am package -DskipTests
java -jar xuantong-server/target/xuantong-server.jar
```

Open <http://localhost:8088> and sign in with `admin` / `admin123`.

Successful sign-in opens `/dashboard`. The admin UI includes runtime overview, configuration, namespaces and groups, services and instances, access tokens, audit logs, and user scopes. Token and global-audit pages are restricted to `SYSTEM_ADMIN`.

The default database is `./data/xuantong-2.mv.db`. Docker and an external database are not required. Version 2.0 neither reads nor migrates the legacy `./data/xuantong.mv.db`; an explicitly configured legacy schema is rejected before any write. Use the `XUANTONG_DB_*` environment variables for an external database.

Create client tokens through `/api/v2/tokens`. The raw token is returned once, while the database stores only its SHA-256 hash. Tokens may be scoped to one Namespace and Group. Production deployments should set `XUANTONG_PRODUCTION=true` and `XUANTONG_CLIENT_AUTH_REQUIRED=true`. The server refuses to start in production mode while the administrator still uses `admin123`.

The admin plane uses four roles: `SYSTEM_ADMIN`, `NAMESPACE_ADMIN`, `DEVELOPER`, and `VIEWER`. Non-system users receive explicit Namespace/Group scopes through the user page or `/api/user/{id}/scopes`. Developers can write only inside their assigned scopes, while viewers are read-only.

`/health` reports database and discovery-cleanup status. `/metrics` exposes Prometheus text metrics for configuration releases, logical Config clients and physical sessions, token authentication, service registration and heartbeats, JVM memory, and process uptime.

Config clients submit `applicationName`, `clientId`, and client version during the Socket.D handshake. The `/connection` admin page displays them separately from service-discovery instances, so applications using Config only are still visible. In Multi-Broker deployments, logical clients are deduplicated by `clientId`, while each Broker page reports the physical sessions held by that node.

If the Server logs `Rejecting Broker session without 2.0 client identity`, the running application is still loading the pre-refactor `xuantong-client` or `xuantong-config-spring-boot-starter` artifact. Version 2.0 requires `xuantong-client-core` / `xuantong-spring-boot-starter`. Reload all Maven projects in IntelliJ IDEA, stop the old process, and start it again. Use `mvn dependency:tree -Dincludes=cloud.xuantong` to verify that the runtime dependency tree no longer contains either legacy artifactId.

Multi-Broker deployments do not use a separate cluster transport. Every Broker shares one external database for durable configuration, service definitions, users, and authorization data. Service instances remain local in each Broker's memory. The node ID is only diagnostic metadata:

```bash
export XUANTONG_NODE_ID=config-node-1
```

Following the official Socket.D multi-broker architecture, every application Client must configure all Broker addresses and keep all connections active. Config clients read the latest Release from the shared database through any available Broker, deduplicate by configuration revision, and reconcile known configs both on connectivity changes and periodically. Discovery providers send the same `instanceId + leaseId` to every Broker; `leaseStartedAt` is assigned by the Broker and never depends on the client clock. Consumers track each Broker's local revision independently and expose the union of reachable instance views. A failed Broker does not interrupt the other connections, and Brokers never copy instance events between themselves.

To run real external-database tests, provide disposable empty databases and set the relevant `XUANTONG_TEST_MYSQL_*` and `XUANTONG_TEST_PGSQL_*` environment variables before `mvn test`. These tests are explicitly skipped when not configured.

Repository CI starts real MySQL and PostgreSQL services and runs the full Reactor test suite.

Version 2.0 requires a fresh 2.0 schema. Start it with an empty database instead of pointing it at a legacy database.

## Java client

The client remains compatible with Java 8:

```xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-client-core</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

```java
XuantongClient client = new XuantongClient(
    Arrays.asList("node1:8088", "node2:8088"),
    "public",
    "DEFAULT_GROUP",
    System.getenv("XUANTONG_ACCESS_TOKEN"),
    "order-service",
    System.getenv("XUANTONG_CLIENT_ID")
);

String timeout = client.get("payment.timeout", "5000");
client.addListener("payment.timeout", event ->
    System.out.println("revision=" + event.getRevision()
        + ", value=" + event.getNewValue()));
```

Use the dedicated discovery client for service registration and selection:

```java
XuantongDiscoveryClient discovery = new XuantongDiscoveryClient(
    Arrays.asList("node1:8088", "node2:8088"),
    "public",
    "DEFAULT_GROUP",
    "order-service",
    System.getenv("XUANTONG_ACCESS_TOKEN")
);

ServiceInstance local = new ServiceInstance();
local.setInstanceId("order-node-1");
local.setIp("10.0.0.8");
local.setPort(8080);
local.setWeight(2D);
discovery.register(local);

ServiceInstance target = discovery.selectInstance(LoadBalanceStrategy.WEIGHTED_RANDOM);
```

`RANDOM`, `ROUND_ROBIN`, and `WEIGHTED_RANDOM` run entirely in the application client. Xuantong never proxies application traffic.

## Spring Boot Starter

```yaml
xuantong:
  config:
    server-addresses: ["xuantong-server:8088"]
    namespace: public
    group: DEFAULT_GROUP
    access-token: ${XUANTONG_ACCESS_TOKEN:}
    application-name: ${spring.application.name}
    client-id: ${XUANTONG_CLIENT_ID:}
```

## Solon Plugin

```yaml
xuantong.config:
  serverAddresses:
    - xuantong-server:8088
  namespace: public
  group: DEFAULT_GROUP
  accessToken: ${XUANTONG_ACCESS_TOKEN:}
  applicationName: ${solon.app.name}
  clientId: ${XUANTONG_CLIENT_ID:}
```

## Solon Cloud Plugin

```yaml
solon.app:
  group: DEFAULT_GROUP

solon.cloud.xuantong:
  server: xuantong-server:8088
  namespace: public
  token: ${XUANTONG_ACCESS_TOKEN:}
  config:
    enable: true
    load: db.yml,logging.yml
```

The Solon Cloud `group` maps directly to a Xuantong Group; an empty group becomes `DEFAULT_GROUP`. The config name maps directly to `dataId`.

## 2.0 endpoints

- HTTP control-plane API: `/api/v2`
- Socket.D configuration endpoint: `/config-v2`
- Socket.D service-discovery endpoint: `/discovery-v2`

Discovery subscriptions use `discovery:{namespace}:{group}:{serviceName}`. Service instances are ephemeral and are removed after 30 seconds without a heartbeat by default. Configure the timeout with `XUANTONG_INSTANCE_TIMEOUT_MS`.

The release workflow is draft → publish → immutable release. A rollback creates another release with type `ROLLBACK` and a new revision. Multiple drafts can be published in one transaction; a validation failure leaves every revision unchanged. Publish and rollback operations also create audit records without storing configuration content in the audit detail.

## Technology

| Area | Choice |
|:---|:---|
| Server | Solon, JDK 21 |
| Long-lived transport | Socket.D |
| Data access | EasyQuery |
| Default database | H2 |
| Client | Java 8+ |

## License

[Apache License 2.0](LICENSE)
