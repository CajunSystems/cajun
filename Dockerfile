# Multi-stage Dockerfile for Cajun LMDB Benchmarks
# Solves ARM64 native library issues by using x86_64 architecture

# Stage 1: Build stage - using Eclipse Temurin JDK 21 for compilation
FROM --platform=linux/amd64 eclipse-temurin:21-jdk-alpine AS builder

# Set working directory
WORKDIR /app

# Install build dependencies
RUN apk add --no-cache \
    build-base \
    cmake \
    bash

# Copy Gradle wrapper and project files
COPY gradlew .
COPY gradle/ gradle/
COPY settings.gradle .
COPY gradle.properties .
COPY lib/ lib/
COPY persistence/ persistence/
COPY benchmarks/ benchmarks/
COPY test-utils/ test-utils/
COPY lib/build.gradle lib/
COPY persistence/build.gradle persistence/
COPY benchmarks/build.gradle benchmarks/
COPY test-utils/build.gradle test-utils/

# Make gradlew executable
RUN chmod +x gradlew

# Build the project
RUN ./gradlew :benchmarks:jmhJar -x test

# Stage 2: Runtime stage - using Eclipse Temurin JRE 21 for execution
FROM --platform=linux/amd64 eclipse-temurin:21-jre-alpine

# Set working directory
WORKDIR /app

# Install runtime dependencies for LMDB
RUN apk add --no-cache \
    lmdb \
    lmdb-dev

# Create benchmark directory
RUN mkdir -p /app/benchmarks

# Copy the built JAR from builder stage
COPY --from=builder /app/benchmarks/build/libs/benchmarks-jmh.jar /app/benchmarks/

# Create results directory
RUN mkdir -p /app/results

# Set environment variables for LMDB
ENV TMPDIR=/tmp/lmdb-benchmark
ENV JAVA_OPTS="--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --enable-preview"

# Expose results directory as volume
VOLUME /app/results

# Default command to run LMDB benchmarks (can be overridden)
CMD ["java", "--enable-preview", "--add-opens=java.base/java.nio=ALL-UNNAMED", "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED", "-jar", "benchmarks/benchmarks-jmh.jar"]
