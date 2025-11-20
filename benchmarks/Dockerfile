# Multi-stage Dockerfile for running Cajun JMH benchmarks
# Usage example:
#   docker build -t cajun-benchmarks -f benchmarks/Dockerfile .
#   docker run --rm cajun-benchmarks MailboxBenchmark "-wi 3 -i 5 -f 1 -bm thrpt"
#
# The container entrypoint expects:
#   $1  = benchmark class name (simple or full, used as JMH regex)
#   $2+ = optional extra JMH options string

FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

# Copy Gradle wrapper and settings first to maximize layer cache
# (This project uses per-module build.gradle files, so there is no root build.gradle)
COPY gradlew gradlew.bat settings.gradle /app/
COPY gradle ./gradle

# Copy project sources
COPY cajun-core ./cajun-core
COPY cajun-mailbox ./cajun-mailbox
COPY cajun-persistence ./cajun-persistence
COPY cajun-cluster ./cajun-cluster
COPY lib ./lib
COPY test-utils ./test-utils
COPY benchmarks ./benchmarks

# Build the JMH fat JAR for benchmarks module
RUN ./gradlew :benchmarks:jmhJar --no-daemon --stacktrace

# Runtime image
FROM eclipse-temurin:21-jdk

# Install native LMDB library required by lmdbjava (liblmdb.so)
RUN apt-get update \
    && apt-get install -y --no-install-recommends liblmdb0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy JMH fat jar only
COPY --from=build /app/benchmarks/build/libs/benchmarks-jmh.jar ./benchmarks-jmh.jar

# Simple entrypoint wrapper
COPY benchmarks/run-benchmark.sh ./run-benchmark.sh
RUN chmod +x ./run-benchmark.sh

ENTRYPOINT ["./run-benchmark.sh"]
