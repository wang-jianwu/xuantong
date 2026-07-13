#!/bin/bash

# Solon服务停止脚本
APP_NAME="xuantong-server"
APP_JAR="$APP_NAME.jar"

echo "Stopping $APP_NAME..."

# 查找进程ID
PID=$(ps -ef | grep $APP_JAR | grep -v grep | awk '{print $2}')

if [ -z "$PID" ]; then
    echo "$APP_NAME is not running"
    exit 0
fi

# 停止应用
kill $PID
sleep 3

# 检查是否成功停止
if ps -p $PID > /dev/null 2>&1; then
    echo "Failed to stop $APP_NAME, trying force kill..."
    kill -9 $PID
    sleep 2
fi

# 最终检查
if ps -p $PID > /dev/null 2>&1; then
    echo "Error: Could not stop $APP_NAME (PID: $PID)"
    exit 1
else
    echo "$APP_NAME stopped successfully"
fi
