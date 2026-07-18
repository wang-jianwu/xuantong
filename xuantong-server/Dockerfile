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

EXPOSE 8088 8090 9101

ENTRYPOINT ["java", "-jar", "/app/xuantong-server.jar"]
