# 安装 socketd-architecture-review Codex Skill

## 包含内容

- `socketd-architecture-review/`：可直接安装的 Skill 目录。
- `socketd-architecture-review.zip`：跨机器传输包。
- `socketd-architecture-review.zip.sha256`：压缩包校验值。

Skill 目录名必须保持为 `socketd-architecture-review`。

## macOS / Linux

### 从项目目录复制

```bash
mkdir -p ~/.codex/skills
cp -R doc/codex-skills/socketd-architecture-review ~/.codex/skills/
```

### 从压缩包安装

```bash
mkdir -p ~/.codex/skills
unzip doc/codex-skills/socketd-architecture-review.zip -d ~/.codex/skills
```

### 校验压缩包

```bash
cd doc/codex-skills
shasum -a 256 -c socketd-architecture-review.zip.sha256
```

## Windows PowerShell

### 从项目目录复制

```powershell
New-Item -ItemType Directory -Force "$HOME\.codex\skills" | Out-Null
Copy-Item -Recurse -Force ".\doc\codex-skills\socketd-architecture-review" "$HOME\.codex\skills\"
```

### 从压缩包安装

```powershell
New-Item -ItemType Directory -Force "$HOME\.codex\skills" | Out-Null
Expand-Archive -Force ".\doc\codex-skills\socketd-architecture-review.zip" "$HOME\.codex\skills"
```

## 验证目录

安装完成后应存在：

```text
~/.codex/skills/socketd-architecture-review/SKILL.md
~/.codex/skills/socketd-architecture-review/agents/openai.yaml
~/.codex/skills/socketd-architecture-review/references/
~/.codex/skills/socketd-architecture-review/scripts/
```

如果 Codex 已经打开，建议新建一个任务或重启 Codex，让 Skill 列表重新加载。

## 使用方式

显式调用示例：

```text
使用 $socketd-architecture-review 分析当前项目的 Socket.D 超时、Solon WebSocket 桥接、灰度回滚和 Multi-Broker 架构。
```

Skill 的 description 也支持在以下问题中自动触发：

- Socket.D `sendAndRequest` 持续超时；
- Solon/SmartHTTP 与 Socket.D 桥接；
- `ToSocketdWebSocketListener`；
- Broker、ClusterClient、Multi-Broker；
- preclose、close、heartbeat、reconnect；
- 灰度发布、滚动发布和回滚；
- 配置中心或服务发现的控制面长连接。

## 刷新 Socket.D 官方文档索引

目标机器具备 Python 3 和网络访问时，可以重新抓取官网导航可达文档：

```bash
python3 ~/.codex/skills/socketd-architecture-review/scripts/crawl_socketd_docs.py \
  --output /tmp/socketd-docs-current
```

输出包含 Markdown 正文、`index.json` 和带 URL/标题/SHA-256 的 `index.md`。刷新结果不会自动覆盖 Skill 内的参考资料，应先比较文档变化再更新 Skill。
