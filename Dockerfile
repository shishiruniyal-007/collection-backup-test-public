# ── Stage 1: compile ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS compile

WORKDIR /app

# Copy sources
COPY RateLimiter.java RateLimiterTest.java ./

# Compile both classes in one pass
RUN javac RateLimiter.java RateLimiterTest.java

# ── Stage 2: test image ───────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS test

WORKDIR /app

# Bring in the compiled classes only (no JDK overhead in the test image)
COPY --from=compile /app/*.class ./

# Running the container executes the full test suite.
# Exit code 0 = all tests passed; non-zero = failure.
CMD ["java", "RateLimiterTest"]
