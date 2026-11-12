FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

COPY gradlew settings.gradle.kts ./
COPY gradle gradle
COPY engine/build.gradle.kts engine/
COPY web/build.gradle.kts web/

RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY engine/src engine/src
COPY web/src web/src

RUN ./gradlew :web:installDist --no-daemon -x test


FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /workspace/web/build/install/web /app

RUN mkdir -p /app/data && chown -R appuser:appgroup /app

USER appuser

EXPOSE 7070
ENV PORT=7070

ENTRYPOINT ["/app/bin/web"]