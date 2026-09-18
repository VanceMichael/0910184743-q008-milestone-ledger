FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/milestone-ledger-0.1.0.jar app.jar
ENV PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=15s --retries=12 \
  CMD ["java", "-cp", "app.jar", "com.example.milestone.Healthcheck"]
CMD ["java", "-jar", "app.jar"]
