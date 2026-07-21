# 参与玄同开发

感谢你愿意参与。提交代码前请先看一下下面这些约定。

## 先看哪些文档

- [README](README.md)
- [快速入门](doc/quick-start.md)
- [架构设计](doc/architecture.md)
- [功能说明](doc/features.md)
- [开发计划](PLAN_2.0.md)

修 Bug 可以直接提 Pull Request。

如果要改协议、数据库结构、状态机或整体架构，请先开 Issue，把要解决的问题和准备怎么改说清楚。

玄同 2.0 不再增加 1.x 兼容代码。

## 几条重要规则

- 一个客户端同时只使用一个 Gateway Session。
- 切换 Gateway 时顺序尝试，不同时向多台机器发同一个请求。
- 可能重试的写请求要带同一个 operationId。
- 灰度推送、主动拉取和重连后拉取必须使用同一套选择规则。
- 数据库变更只能新增 Flyway Migration，不能修改已经发布的 Migration。
- 性能数据要写清机器、参数和测试方式。

## 提交前检查

需要 JDK 21、Maven、Bash 和 jq。

~~~bash
mvn test
./scripts/verify-ci-test-reports.sh
./scripts/verify-no-secrets.sh
./scripts/verify-release-metadata.sh
git diff --check
~~~

改了 Socket.D、Raft、备份恢复或发布脚本时，再运行对应的专项测试。

不要为了让测试变绿去删断言、吞异常或允许测试跳过。

## Pull Request 写什么

请说明：

- 改了什么；
- 为什么要改；
- 跑了哪些测试；
- 部署或回滚有没有影响。

不要提交 IDE 配置、target、日志、运行数据、密码、Token 或本地环境文件。
