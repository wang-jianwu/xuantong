#!/bin/bash
# ============================================================
# 玄同 - 启动脚本 v2.0.0
# 部署路径: /home/xuantong
# ============================================================

set -e

# -------------------- 路径配置 --------------------
APP_HOME="/home/xuantong"
APP_NAME="xuantong-server"
JAR_FILE="${APP_HOME}/${APP_NAME}.jar"
PID_FILE="${APP_HOME}/bin/${APP_NAME}.pid"
LOG_FILE="${APP_HOME}/logs/${APP_NAME}.log"

# JVM 参数（可通过环境变量覆盖）
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx512m -XX:+UseG1GC}"
# 启动端口（默认 8088）
SERVER_PORT="${SERVER_PORT:-8088}"
# 激活的配置文件（默认 default，即 H2 内置模式；生产环境设为 prod）
SOLON_ENV="${SOLON_ENV:-default}"
# -------------------------------------------------

if [ ! -f "$JAR_FILE" ]; then
    echo "❌ JAR not found: ${JAR_FILE}"
    exit 1
fi

if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
        echo "❌ ${APP_NAME} is already running (PID: $PID)"
        exit 1
    fi
    rm -f "$PID_FILE"
fi

# 创建所需目录
mkdir -p "${APP_HOME}/logs" "${APP_HOME}/bin"

echo "🚀 Starting ${APP_NAME} v2.0.0 on port ${SERVER_PORT} (env: ${SOLON_ENV})..."

nohup java ${JAVA_OPTS} \
    -Dserver.port=${SERVER_PORT} \
    -Dsolon.env=${SOLON_ENV} \
    -jar ${JAR_FILE} \
    > ${LOG_FILE} 2>&1 &

PID=$!
echo $PID > "$PID_FILE"

sleep 2
if kill -0 "$PID" 2>/dev/null; then
    echo "✅ ${APP_NAME} started successfully (PID: $PID)"
    echo "   Logs: tail -f ${LOG_FILE}"
else
    echo "❌ ${APP_NAME} failed to start, check logs: ${LOG_FILE}"
    rm -f "$PID_FILE"
    exit 1
fi
