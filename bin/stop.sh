#!/bin/bash
# ============================================================
# 玄同 - 停止脚本 v2.0.0
# 部署路径: /home/xuantong
# ============================================================

APP_HOME="/home/xuantong"
APP_NAME="xuantong-server"
PID_FILE="${APP_HOME}/bin/${APP_NAME}.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "❌ ${APP_NAME} is not running (no PID file)"
    exit 1
fi

PID=$(cat "$PID_FILE")

if ! kill -0 "$PID" 2>/dev/null; then
    echo "⚠️  ${APP_NAME} is not running (stale PID file)"
    rm -f "$PID_FILE"
    exit 0
fi

echo "🛑 Stopping ${APP_NAME} (PID: $PID)..."

# 优雅关闭
kill "$PID"

# 等待最多 30 秒
TIMEOUT=30
COUNT=0
while kill -0 "$PID" 2>/dev/null && [ $COUNT -lt $TIMEOUT ]; do
    sleep 1
    COUNT=$((COUNT + 1))
done

if kill -0 "$PID" 2>/dev/null; then
    echo "⚠️  Graceful shutdown timeout, force killing..."
    kill -9 "$PID" 2>/dev/null
fi

rm -f "$PID_FILE"
echo "✅ ${APP_NAME} stopped"
