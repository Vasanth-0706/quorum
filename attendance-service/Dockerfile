# syntax=docker/dockerfile:1
# Multi-stage build: compile with the JDK, run on the JRE as a non-root user.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
    && cp target/attendance-service-*.jar /app.jar

FROM eclipse-temurin:17-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /app.jar app.jar
USER app
# DB_HOST, DB_PORT, DB_USERNAME, DB_PASSWORD, KAFKA_BOOTSTRAP and TZ come from the environment
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8082
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=10 \
    CMD curl -fsS http://localhost:8082/outbox?limit=1 > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
