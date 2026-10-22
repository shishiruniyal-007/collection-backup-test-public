# syntax=docker/dockerfile:1
# ---------------------------------------------------------------------------
# Test image for RateLimiter
#
# Build:  docker build -t rate-limiter-test .
# Run:    docker run --rm rate-limiter-test
# ---------------------------------------------------------------------------

FROM eclipse-temurin:21-jdk-alpine AS deps

WORKDIR /build

# Download JUnit 5 Platform Launcher + Engine + API and their transitive deps.
RUN apk add --no-cache curl && \
    mkdir -p lib && \
    BASE="https://repo1.maven.org/maven2" && \
    curl -fsSL -o lib/junit-platform-console-standalone.jar \
      "${BASE}/org/junit/platform/junit-platform-console-standalone/1.11.3/junit-platform-console-standalone-1.11.3.jar"

# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build
COPY --from=deps /build/lib lib/
COPY RateLimiter.java RateLimiterTest.java ./

# Compile production class then test class.
RUN javac -cp lib/junit-platform-console-standalone.jar \
          -d out \
          RateLimiter.java RateLimiterTest.java

# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS test

WORKDIR /app
COPY --from=deps  /build/lib lib/
COPY --from=builder /build/out out/

# Run all tests; non-zero exit on any failure.
ENTRYPOINT ["java", \
            "-jar", "lib/junit-platform-console-standalone.jar", \
            "--class-path", "out", \
            "--scan-class-path"]
