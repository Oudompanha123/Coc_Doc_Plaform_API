# Coc Docs Platform backend — standalone image for hosts that build from the
# backend directory alone (Render, or the split Coc_Doc_Plaform_API repo).
#
# Same build as infra/backend.Dockerfile, but with this directory as the build
# context, so paths have no `backend/` prefix:
#
#   docker build -t cocdocs-backend backend/
#
# The port comes from $PORT (Render sets it; application.yml reads it).

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

ENV MAVEN_OPTS="-Dmaven.wagon.http.retryHandler.count=3 \
    -Dmaven.wagon.httpconnectionManager.ttlSeconds=120 \
    -Dmaven.wagon.rto=120000"

# pom first, so dependencies are cached until the pom changes.
COPY pom.xml ./
RUN mvn -B dependency:go-offline

COPY src ./src

# Tests run in CI (they need Docker for Testcontainers), not in the image build.
RUN mvn -B -DskipTests package

# ---------------------------------------------------------------------------
# Run
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS run

RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY --from=build /src/target/*.jar app.jar

# Logback writes to /app/logs; the non-root user must own it or startup fails.
RUN mkdir -p /app/logs && chown -R app:app /app

USER app

EXPOSE 8080

# Heap sized from the container's memory limit. Override JAVA_OPTS in the
# host's environment settings if the instance is very small (e.g. 512 MB).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

# exec so the JVM is pid 1 and receives SIGTERM directly.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
