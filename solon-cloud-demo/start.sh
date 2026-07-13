#!/bin/bash

# Solon服务启动脚本
APP_NAME="xuantong-server"
APP_JAR="$APP_NAME.jar"
LOG_FILE="logs/$APP_NAME.log"

echo "Starting $APP_NAME..."
#!/bin/bash

# 配置参数
APP_NAME="xuantong-server"
APP_JAR="xuantong-server.jar"
LOG_FILE="./logs/xuantong-server.log"
PORT=${1:-8088}

# 检查是否已运行（基于端口检查）
PID=$(lsof -ti :$PORT)
if [ -n "$PID" ]; then
    echo "$APP_NAME is already running on port $PORT (PID: $PID)"
    exit 1
fi

# JVM参数
JVM_OPTS="-server -Xms512m -Xmx1g -XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=60m"
JVM_OPTS="$JVM_OPTS -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:ParallelGCThreads=2"
JVM_OPTS="$JVM_OPTS -XX:+TieredCompilation -XX:CICompilerCount=2"
JVM_OPTS="$JVM_OPTS -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./logs/heapdump.hprof"
JVM_OPTS="$JVM_OPTS -XX:+PrintGC -XX:+PrintGCDetails -XX:+PrintGCTimeStamps"
JVM_OPTS="$JVM_OPTS -Xloggc:./logs/gc-$PORT.log -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=5 -XX:GCLogFileSize=10M"

# 添加端口参数
JVM_OPTS="$JVM_OPTS -Dserver.port=$PORT"

echo "Starting $APP_NAME on port $PORT with JVM Options: $JVM_OPTS"

# 启动应用
nohup java $JVM_OPTS -jar $APP_JAR > "${LOG_FILE%.*}_$PORT.log" 2>&1 &
echo "$APP_NAME started successfully on port $PORT (PID: $!)"
