#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <BenchmarkClassName> [JMH options...]" >&2
  echo "Example: $0 MailboxBenchmark -wi 3 -i 5 -f 1 -bm thrpt" >&2
  exit 1
fi

CLASS_NAME="$1"
shift || true

# Build a simple regex to match the benchmark class
PATTERN=".*${CLASS_NAME}.*"

# Run JMH with explicit JVM args so LMDB (lmdbjava) can access
# java.nio.Buffer.address and sun.nio.ch.DirectBuffer under the module system
exec java \
  --enable-preview \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-exports=java.base/sun.nio.ch=ALL-UNNAMED \
  -jar /app/benchmarks-jmh.jar \
  "$@" "${PATTERN}"
