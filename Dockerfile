# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
# Copy build definition first so dependency resolution is cached until it changes.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY config config
COPY src src
# Tests run in CI (and locally); the image build only packages.
RUN ./gradlew --no-daemon bootJar -x test
# Boot's "tools" jar mode extracts an application jar + lib/ folder: the layout the JVM AOT cache needs.
RUN java -Djarmode=tools -jar build/libs/*.jar extract --destination /workspace/extracted

FROM eclipse-temurin:25-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/extracted/ ./
# Java 25 AOT cache (JEP 483/514/515): a training run starts the Spring context once, exits on refresh, and
# records loaded/linked classes and method profiles. Later starts reuse it, so startup is noticeably faster.
# Flyway is off and no DB is needed for the training run. If training fails, the image still works, just
# without the cache (the JVM ignores a missing cache with a warning).
# The training run needs *a* key hash to pass startup validation; this all-zero hash matches no real key.
RUN java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -Dspring.flyway.enabled=false \
        -Dshortener.api-keys[0].name=aot-training -Dshortener.api-keys[0].scopes[0]=stats:read \
        -Dshortener.api-keys[0].sha256=0000000000000000000000000000000000000000000000000000000000000000 \
        -jar app.jar > /dev/null 2>&1 || echo "AOT cache training skipped"
RUN chown -R app:app /app
USER app
EXPOSE 8080
# Container-aware heap; crash fast on OOM so the orchestrator restarts a clean process.
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
