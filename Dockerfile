# syntax=docker/dockerfile:1

# Build stage: compiles and packages the executable jar. Tests are not run here because the
# integration tests need Docker (Testcontainers); CI runs the full `./mvnw verify` instead.
FROM eclipse-temurin:21-jdk AS build
# Without unzip the wrapper fetches the .tar.gz distribution, which fails the pinned .zip SHA-256.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -ntp -q package -DskipTests -Djacoco.skip=true \
    && cp target/ledger-*.jar /workspace/app.jar

# Runtime stage: JRE only, non-root, configuration exclusively from environment variables.
FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 ledger \
    && useradd --system --uid 10001 --gid ledger --no-create-home --shell /usr/sbin/nologin ledger
WORKDIR /app
COPY --from=build --chown=root:root --chmod=0444 /workspace/app.jar /app/app.jar
USER 10001:10001

ENV LEDGER_HTTP_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
EXPOSE 8080

# Readiness includes the database, so the container only turns healthy once migrations ran.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 \
    CMD curl -fsS "http://127.0.0.1:${LEDGER_HTTP_PORT}/actuator/health/readiness" > /dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
