FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build
COPY . .
RUN mvn package -pl xuantong-probe -am -DskipTests -q

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
RUN apk add --no-cache tzdata wget && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

COPY --from=builder /build/xuantong-probe/target/xuantong-probe.jar /app/xuantong-probe.jar

EXPOSE 9118

ENTRYPOINT ["java", "-jar", "/app/xuantong-probe.jar", "--serve"]
