# 构建阶段：非 root 用户运行（内嵌 PostgreSQL 的集成测试拒绝 root）
FROM maven:3.9-eclipse-temurin-21 AS build
RUN useradd -m builder
USER builder
WORKDIR /app
COPY --chown=builder:builder pom.xml .
RUN mvn -q -B -DskipTests dependency:resolve
COPY --chown=builder:builder src ./src
RUN mvn -q -B verify

# 运行阶段
FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/milestone-ledger-0.1.0.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
