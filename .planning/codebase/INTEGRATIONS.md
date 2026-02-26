# Integrations

## Etcd (Cluster Coordination)

- **Purpose**: Distributed coordination, leader election, actor placement metadata
- **Library**: `jetcd-core:0.8.4`
- **Implementation**: `EtcdMetadataStore` in `cajun-cluster/`
- **Usage**: Required only for cluster mode (`ClusterActorSystem`)
- **CI**: Tests tagged `requires-etcd` are excluded by default
- **Transport**: gRPC over Netty

## gRPC (Inter-Node Communication)

- **Purpose**: Remote procedure calls between cluster nodes
- **Version**: 1.68.1 (transitive from jetcd)
- **Modules**: grpc-stub, grpc-core, grpc-netty, grpc-protobuf, grpc-util
- **Serialization**: Protocol Buffers (`protobuf-java:3.25.5`)
- **Transport**: Netty-based

## Netty (Networking Layer)

- **Purpose**: High-performance async networking foundation
- **Version**: 4.1.116.Final (security-patched)
- **Modules**: netty-common, netty-buffer, netty-transport, netty-handler, netty-codec, netty-codec-http, netty-codec-http2, netty-resolver, netty-resolver-dns, netty-codec-dns, netty-handler-proxy
- **Used by**: gRPC transport, Vert.x

## Vert.x (Async Framework)

- **Purpose**: Reactive async toolkit, event-driven processing
- **Version**: 4.5.11
- **Modules**: vertx-core, vertx-grpc
- **CVE patch**: CVE-2024-1300 pinned at 4.5.11

## LMDB (Persistence)

- **Purpose**: High-performance actor state persistence
- **Library**: `lmdbjava:0.8.3`
- **Type**: Memory-mapped key-value store (Lightning Memory-Mapped Database)
- **Native requirement**: `liblmdb0` system library must be installed
  - Ubuntu/Debian: `apt-get install -y liblmdb0`
  - Included in benchmarks Docker image
- **Implementation**: `LmdbPersistenceProvider`, `LmdbMessageJournal`, `LmdbSnapshotStore`
- **Known limitations**:
  - `MDB_MAP_FULL` error if mapSize is under-configured
  - `MDB_READERS_FULL` if too many concurrent readers
  - Slower on Windows than Linux/macOS

## SLF4J + Logback (Logging)

- **Purpose**: Structured logging throughout the system
- **SLF4J**: `2.0.16` (API)
- **Logback**: `1.5.16` (implementation, CVE-patched)
- **Usage**: All production logging should go through SLF4J (see CONCERNS.md for violations)

## JCTools (Lock-Free Concurrency)

- **Purpose**: Lock-free data structures for mailbox implementation
- **Version**: `4.0.1`
- **Used in**: `MpscMailbox` (Multi-Producer, Single-Consumer queue)

## Failsafe (Resilience)

- **Purpose**: Retry logic and resilience patterns
- **Version**: `3.3.2`
- **Used in**: Retry strategies for persistence operations

## Jackson (JSON)

- **Purpose**: JSON serialization utility
- **Library**: `jackson-core:2.16.1`
- **Usage**: Utility serialization, likely for cluster message payloads

## Guava

- **Purpose**: General utilities
- **Version**: `33.3.1-jre`

## Apache Commons Math3

- **Purpose**: Mathematical operations
- **Version**: `3.6.1`
- **Used in**: Likely backpressure threshold calculations

## Maven Central (Distribution)

- **Purpose**: Artifact publishing
- **Mechanism**: Sonatype OSSRH (Central Portal)
- **Credentials**: Environment variables (`OSSRH_USERNAME`, `OSSRH_PASSWORD`)
- **Signing**: GPG (`SIGNING_KEY_ID`, `SIGNING_PASSWORD`, `SIGNING_SECRET_KEY_RING_FILE`)
- **Artifacts**: Signed JARs + source JARs + Javadoc JARs

## GitHub Actions (CI/CD)

- **File**: `.github/workflows/gradle.yml`
- **Triggers**: Push and PR to `main`
- **Build**: `gradle build -x test` (compile only)
- **Test**: `gradle test` (unit/integration, no performance/etcd)
- **Dependency graph**: Submitted for Dependabot vulnerability scanning

## Docker

- **benchmarks/Dockerfile**: Multi-stage build for JMH benchmarks; includes `liblmdb0`
- **persistence/build/resources/test/Dockerfile.lmdb**: Test environment with LMDB
- **Base image**: `eclipse-temurin:21-jdk`
