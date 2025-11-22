#!/bin/bash

# Solon服务启动脚本
APP_NAME="nimbus-admin"
APP_JAR="$APP_NAME.jar"
LOG_FILE="logs/$APP_NAME.log"

echo "Starting $APP_NAME..."

# 检查是否已运行
PID=$(ps -ef | grep $APP_JAR | grep -v grep | awk '{print $2}')
if [ -n "$PID" ]; then
    echo "$APP_NAME is already running (PID: $PID)"
    exit 1
fi

# JVM参数配置）
JVM_OPTS="-server -Xms512m -Xmx1g -XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=60m"
JVM_OPTS="$JVM_OPTS -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:ParallelGCThreads=2"
JVM_OPTS="$JVM_OPTS -XX:+TieredCompilation -XX:CICompilerCount=2"
JVM_OPTS="$JVM_OPTS -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./logs/heapdump.hprof"
JVM_OPTS="$JVM_OPTS -XX:+PrintGC -XX:+PrintGCDetails -XX:+PrintGCTimeStamps"
JVM_OPTS="$JVM_OPTS -Xloggc:./logs/gc.log -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=5 -XX:GCLogFileSize=10M"

echo "JVM Options: $JVM_OPTS"

# 启动应用
nohup java $JVM_OPTS -jar target/$APP_JAR > $LOG_FILE 2>&1 &
echo "$APP_NAME started successfully with optimized JVM parameters"