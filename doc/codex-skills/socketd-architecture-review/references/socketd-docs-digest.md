# Socket.D official documentation digest

This digest follows the record order in [socketd-docs-index.md](socketd-docs-index.md). It preserves a one-line takeaway for every page in the complete 119-page crawl without copying the original documentation.

## Records 1-40

1. [现状与规划](https://socketd.noear.org/article/691) — Java is the mature reference implementation; other languages have partial or planned transport coverage.
2. [快速入门 - Helloworld](https://socketd.noear.org/article/692) — A transport package plus Session/Listener provides send, one-reply request, and multi-reply subscription.
3. [Socket.D - Java 开发](https://socketd.noear.org/article/693) — Java offers TCP, UDP, WS, KCP, Netty, and SmartSocket adapters with different I/O models.
4. [Socket.D - JavaScript 开发](https://socketd.noear.org/article/694) — JavaScript exposes the same model over WS/WSS for browsers and Node-like environments.
5. [Socket.D - Python 开发](https://socketd.noear.org/article/700) — Python documents asynchronous WS/WSS clients and servers; TCP remains unfinished.
6. [Listener 的增强应用](https://socketd.noear.org/article/702) — Simple, event, pipeline, and path listeners form the routing and middleware layer.
7. [了解三个基础发送接口](https://socketd.noear.org/article/704) — Interaction modes are fire-and-forget, single reply, and reply stream with different defaults.
8. [应用 - 配置接口](https://socketd.noear.org/article/706) — Client/server builders configure fragmentation, port, TLS, and related transport properties.
9. [实战 - 简单的消息队列实现](https://socketd.noear.org/article/707) — A minimal in-memory Qos0 pub/sub design uses session sets, attributes, and broadcasts.
10. [实战 - 简单的聊天实现](https://socketd.noear.org/article/708) — Path and event routing plus session attributes implement rooms, presence, and moderation.
11. [Broker - 开发](https://socketd.noear.org/article/709) — Short introduction to named Players communicating through a Broker. **Stub.**
12. [开始启动 Broker 服务](https://socketd.noear.org/article/711) — A Broker is a Socket.D server with `BrokerListener`; listener extension adds authorization.
13. [然后加入 Broker 集群，参与会话](https://socketd.noear.org/article/712) — Players register a name with `@` and use unicast/group/broadcast addressing across one or more Brokers.
14. [实战 - FolkMQ Broker 服务定制](https://socketd.noear.org/article/713) — A custom Broker adds AK/SK auth, topic/group registration, routing, and acknowledgements.
15. [基于事件的语义消息流](https://socketd.noear.org/article/714) — Defines the compact frame fields and size limits.
16. [基于事件的（或路径、指令）](https://socketd.noear.org/article/715) — Event is the optional visible-string application routing key.
17. [语义消息（或元信息标注）](https://socketd.noear.org/article/716) — Metadata uses query-string-style portable key/value encoding.
18. [流（或消息关联性）](https://socketd.noear.org/article/717) — `sid` correlates handshakes, fragments, requests, subscriptions, and replies.
19. [监听与会话与实体](https://socketd.noear.org/article/718) — Listener lifecycle and bidirectional Session operations are the core API; server `onOpen` precedes handshake completion.
20. [会话](https://socketd.noear.org/article/719) — Full Session API covers addresses, handshake, attributes, reconnect, ping, requests, replies, and close.
21. [监听器](https://socketd.noear.org/article/720) — Full Listener API and built-in event/path/pipeline/Broker variants.
22. [Entity 与 Message 接口及区别](https://socketd.noear.org/article/721) — Entity is outbound metadata/data; Message adds stream, event, interaction, and framing context.
23. [与其它协议的简单对比](https://socketd.noear.org/article/723) — Marketing comparison with HTTP, WebSocket, RSocket, and Socket.IO.
24. [技术支持与开源社区](https://socketd.noear.org/article/727) — Community contacts and source repositories. **Non-architectural.**
25. [Listener 的定制应用](https://socketd.noear.org/article/728) — Demonstrates adapting Socket.D messages into Solon handler/MVC abstractions.
26. [配置类](https://socketd.noear.org/article/729) — Navigation-only configuration page. **Placeholder.**
27. [客户端配置类](https://socketd.noear.org/article/730) — Client source documents URL parsing, connect timeout, heartbeat, and automatic reconnect defaults.
28. [公共配置类](https://socketd.noear.org/article/731) — Common source centralizes streams, codecs, executors, fragmentation, buffers, and timeouts.
29. [服务端配置类](https://socketd.noear.org/article/732) — Server configuration supplies provider lookup, host binding, and default port 8602.
30. [与 RSocket 的关键区别](https://socketd.noear.org/article/735) — Emphasizes events, query metadata, and callbacks over RSocket conventions.
31. [Broker 模式](https://socketd.noear.org/article/737) — Named outbound Players connect to one or more Brokers that route by round-robin, IP hash, group, or wildcard.
32. [创建客户端会话](https://socketd.noear.org/article/738) — Clients may open one session or a cluster session across several URLs.
33. [启动服务端](https://socketd.noear.org/article/739) — Server lifecycle is configure, listen, start, then application-managed stop.
34. [FolkMQ 集群架构应用示例](https://socketd.noear.org/article/740) — Contains only an image. **Image-only placeholder.**
35. [架构示意图](https://socketd.noear.org/article/742) — Diagrams show single/multi-Broker center-and-spoke request forwarding.
36. [协议特点](https://socketd.noear.org/article/743) — Summarizes events, metadata, streams, reconnect, multiplexing, duplex, and fragmentation.
37. [功能结构图](https://socketd.noear.org/article/744) — Contains only an image. **Image-only placeholder.**
38. [反向服务架构应用](https://socketd.noear.org/article/745) — Full duplex lets a conventional server dial outward directly or through a Broker.
39. [Socket.D - Android 开发](https://socketd.noear.org/article/746) — Android reuses Java/Kotlin TCP, UDP, WS, and SmartSocket adapters.
40. [Socket.D - Android 开发: 快速入门](https://socketd.noear.org/article/747) — Kotlin/Android uses the Java single-session API.

## Records 41-80

41. [协议特点](https://socketd.noear.org/article/748) — Another feature overview covering routing, metadata, streams, reconnect, multiplexing, and fragmentation.
42. [Socket.D - Kotlin 开发](https://socketd.noear.org/article/749) — Kotlin reuses Java transports and APIs; exact duplicate of `/article/kotlin`.
43. [Socket.D - Kotlin 开发: 快速入门](https://socketd.noear.org/article/750) — Kotlin demonstrates send, request, and subscription through one session.
44. [多语言命名规则](https://socketd.noear.org/article/751) — Java is the reference naming scheme; other languages adapt overloads and accessors consistently.
45. [UDP - 双向通讯](https://socketd.noear.org/article/752) — Client and server can both initiate and answer messages over UDP.
46. [TCP - 大文件上传](https://socketd.noear.org/article/753) — Large uploads use smaller fragments, temporary-file assembly, and explicit release.
47. [连接鉴权](https://socketd.noear.org/article/754) — URL/query and connection metadata become handshake parameters for authentication.
48. [告警与错误](https://socketd.noear.org/article/755) — `sendAlarm` reports invalid modes/parameters through the peer error path.
49. [问答](https://socketd.noear.org/article/756) — Invites questions and contains no technical content. **Placeholder.**
50. [客户端会话](https://socketd.noear.org/article/757) — `ClientSession` is the cluster-compatible subset covering lifecycle and interactions.
51. [JavaScript 快速入门](https://socketd.noear.org/article/758) — JavaScript mirrors listener, routing, request, and subscription semantics.
52. [JavaScript 接口字典](https://socketd.noear.org/article/759) — Documents facade factories and common entity/message contracts.
53. [Go 开发](https://socketd.noear.org/article/760) — Only “under development.” **Placeholder; duplicate of #54.**
54. [iOS 开发](https://socketd.noear.org/article/761) — Only “under development.” **Placeholder; duplicate of #53.**
55. [H5 配合 Java MVC](https://socketd.noear.org/article/763) — Maps event, data, and metadata to MVC path, body, and headers.
56. [H5 聊天/广播示例](https://socketd.noear.org/article/764) — EventListener handles presence while retained sessions enable broadcast.
57. [H5 WebSocket 文件上传](https://socketd.noear.org/article/765) — Browser upload demonstrates fragmentation, assembly, reply, progress, and release.
58. [H5 告警/错误示例](https://socketd.noear.org/article/766) — Protocol alarms reach JavaScript stream error callbacks.
59. [协议指令流详解](https://socketd.noear.org/article/768) — Details connect, heartbeat, message, request, subscription, and two-stage close flows.
60. [协议帧码详解](https://socketd.noear.org/article/769) — Defines logical flags and frame layout for connect through reply-end.
61. [Stream 接口](https://socketd.noear.org/article/772) — Send, request, and subscription streams share SID/progress/error contracts.
62. [v2.3 Stream API 更新](https://socketd.noear.org/article/773) — Sending APIs were refactored to return uniform stream objects.
63. [H5 综合演示](https://socketd.noear.org/article/774) — Java/Node examples cover request, subscription, upload, download, push, and lifecycle.
64. [超时概念](https://socketd.noear.org/article/775) — Coordinates idle, request, stream, connect, and heartbeat timing defaults.
65. [JavaScript 配置](https://socketd.noear.org/article/777) — Thin snippet for fragment and WebSocket subprotocol settings.
66. [H5 双向通讯](https://socketd.noear.org/article/778) — Browser and Java server can independently initiate messages and replies.
67. [H5 连接鉴权](https://socketd.noear.org/article/779) — JavaScript supplies URL/connection metadata; Java validates and returns handshake metadata.
68. [JavaScript 单服务端与集群客户端](https://socketd.noear.org/article/780) — Same listener opens one or multiple server connections.
69. [JavaScript 三种发送接口](https://socketd.noear.org/article/781) — Distinguishes send, one-reply request, and multi-reply subscription.
70. [JavaScript Listener 增强](https://socketd.noear.org/article/782) — Simple, event, and pipeline listeners compose handlers and interception.
71. [Solon WebSocket 转 Socket.D](https://socketd.noear.org/article/783) — Official bridge reuses Solon's HTTP/WebSocket port and injects Broker or normal listeners.
72. [Spring WebSocket 转 Socket.D](https://socketd.noear.org/article/784) — Spring adapter converts WebSocket handlers into Socket.D sessions.
73. [Reactor 适配](https://socketd.noear.org/article/785) — User-space wrapper maps request/subscription callbacks to Mono and Flux.
74. [单连与多连](https://socketd.noear.org/article/786) — Single sessions expose transport details; cluster sessions expose all sessions and one selected session.
75. [空白页](https://socketd.noear.org/article/787) — Contains only a newline. **Empty placeholder.**
76. [Entity 类型](https://socketd.noear.org/article/788) — Built-ins are binary, file, and string entities; custom types implement Entity.
77. [一个业务，多种传输服务](https://socketd.noear.org/article/789) — Separate TCP and WebSocket servers can share one listener, fragment handler, and executor.
78. [Python 启动服务端](https://socketd.noear.org/article/790) — Python uses configurable asynchronous start, prestop, and stop.
79. [Python 单服务端与集群客户端](https://socketd.noear.org/article/791) — Python exposes equivalent single and multi-address client factories.
80. [Node H5 演示服务端](https://socketd.noear.org/article/795) — Node example implements request, subscription, upload, download, and push.

## Records 81-119

81. [Node.js 依赖说明](https://socketd.noear.org/article/796) — Node reuses the JavaScript API and points server readers to Java examples.
82. [Node.js 启动服务端](https://socketd.noear.org/article/797) — A WebSocket server can bind its own port or attach to an existing HTTP server.
83. [更新日志](https://socketd.noear.org/article/798) — Links to GitHub/Gitee release pages. **Link-only index.**
84. [路径/通道路由](https://socketd.noear.org/article/799) — PathListener dispatches connections by URL path and supports MVC-style separation.
85. [Broker 集群高可用](https://socketd.noear.org/article/802) — Multiple Brokers plus prestop/preclose, drain delay, and final close provide node removal.
86. [v2.4 更新](https://socketd.noear.org/article/803) — Adds two-stage shutdown, IP-hash routing, load balancing, and executor/send-order controls.
87. [协议元信息](https://socketd.noear.org/article/804) — Defines standard protocol, real-IP, type, fragment, filename, and range metadata.
88. [未说明图片](https://socketd.noear.org/article/805) — Contains only an image reference. **Image-only placeholder.**
89. [握手接口](https://socketd.noear.org/article/806) — URI/query and connection metadata become session parameters and response metadata.
90. [会话关闭与安全关闭](https://socketd.noear.org/article/807) — Preclose drains before a delayed final close; normal close aborts in-flight work.
91. [自动重连](https://socketd.noear.org/article/808) — Heartbeat/send reconnect accidental loss; explicit/protocol close needs manual reconnect.
92. [Python 三种发送接口](https://socketd.noear.org/article/809) — Python exposes send, single-response request, and multi-response subscription.
93. [Python 配置](https://socketd.noear.org/article/810) — Thin client/server examples configure fragment size, port, and SSL.
94. [Python 客户端与监听器](https://socketd.noear.org/article/811) — Mirrors Java APIs with snake_case and class/lambda listeners.
95. [Python Listener 增强](https://socketd.noear.org/article/812) — Simple, event, pipeline, and path listeners support routing and adapters.
96. [Python H5 演示服务端](https://socketd.noear.org/article/813) — Demonstrates request, subscription, upload/download, push, and handshake metadata.
97. [Python 双向通讯](https://socketd.noear.org/article/814) — Either endpoint can independently send and reply after connection.
98. [Python 文件上传](https://socketd.noear.org/article/815) — Large uploads use fragments and explicit message release.
99. [Python 连接鉴权](https://socketd.noear.org/article/816) — Server validates URL/connection metadata during `onOpen`.
100. [Python 告警/错误](https://socketd.noear.org/article/817) — `sendAlarm` becomes an error on the caller's request/subscription stream.
101. [Java 消息串行处理](https://socketd.noear.org/article/818) — Fair-lock sending and single-thread settings approximate ordering while serializing receive work.
102. [BroadcastBroker](https://socketd.noear.org/article/819) — BrokerListener implements injectable named and wildcard broadcasting.
103. [v2.5 更新与兼容](https://socketd.noear.org/article/820) — Adds WebSocket subprotocol checks, broadcast, activity filtering, and shutdown fixes.
104. [WebSocket 子协议验证](https://socketd.noear.org/article/821) — `Socket.D` subprotocol validation is a coordinated compatibility boundary.
105. [心跳](https://socketd.noear.org/article/822) — Client Ping/server Pong prevents the default 60-second idle timeout.
106. [Java TLS/mTLS](https://socketd.noear.org/article/823) — Java TCP and WebSocket adapters support server TLS and mutual TLS.
107. [Python TLS/mTLS](https://socketd.noear.org/article/824) — Python presents one-way and mutual TLS examples, with some internal inconsistencies.
108. [Node TLS/mTLS](https://socketd.noear.org/article/825) — HTTPS options and client certificates provide WSS TLS/mTLS.
109. [开发缘由](https://socketd.noear.org/article/about-qa) — Rationale combines routable events, metadata, and correlated streams.
110. [Socket.D - Android 开发](https://socketd.noear.org/article/android) — Exact duplicate of record 39.
111. [生产案例](https://socketd.noear.org/article/cases) — Reports messaging, telemetry, file transfer, alerting, control, and scheduling use cases.
112. [Socket.D - Java 开发](https://socketd.noear.org/article/java) — Exact duplicate of record 3.
113. [Socket.D - JavaScript 开发](https://socketd.noear.org/article/javascript) — Exact duplicate of record 4.
114. [Socket.D - Kotlin 开发](https://socketd.noear.org/article/kotlin) — Exact duplicate of record 42.
115. [学习路径](https://socketd.noear.org/article/learn-start) — Thin navigation page from concepts to examples.
116. [Socket.D - Node.js 开发](https://socketd.noear.org/article/nodejs) — Node reuses the JavaScript WebSocket package for clients and servers.
117. [协议概要](https://socketd.noear.org/article/protocol) — Defines URL addressing, transports, frame fields, limits, fragmentation, and flag flows.
118. [Socket.D - Python 开发](https://socketd.noear.org/article/python) — Exact duplicate of record 5.
119. [赞助](https://socketd.noear.org/article/sponsor) — Apache-2.0 licensing and sponsorship channel. **Non-architectural.**

## Cross-corpus cautions

- Multi-Broker pages describe clients connecting to independent centers but do not specify Broker replication, consensus, stream failover, retries, deduplication, or split-brain behavior.
- “Qos1” in the docs means a reply is expected; it is not a durable at-least-once guarantee.
- Request/subscription streams expose no cancellation or receiver-driven backpressure API.
- Graceful shutdown uses preclose, a fixed delay, and final close rather than an acknowledgement or tracked in-flight count.
- WebSocket subprotocol changes show that mixed-version rollout is a real compatibility boundary.
- Examples and duplicate pages contain version drift and occasional inconsistencies; treat source code and executable conformance tests as authoritative for the resolved version.
