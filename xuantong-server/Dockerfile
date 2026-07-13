# 构建阶段
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build
COPY . .
RUN mvn clean package -pl xuantong-server -am -DskipTests -q

# 运行阶段
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

COPY --from=builder /build/xuantong-server/target/xuantong-server.jar /app/

EXPOSE 8088

ENV DB_HOST=127.0.0.1 \
    DB_PORT=3306 \
    DB_USER=root \
    DB_PASSWORD= \
    XUANTONG_CLIENT_AUTH_REQUIRED=false \
    XUANTONG_PRODUCTION=false

ENTRYPOINT ["java", "-jar", "/app/xuantong-server.jar"]
