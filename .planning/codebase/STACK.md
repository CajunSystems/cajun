# Stack

## Language & Runtime

| Component | Details |
|-----------|---------|
| **Language** | Java 21+ |
| **Preview Features** | Required (`--enable-preview` for all compile/test/run) |
| **JDK Distribution** | Eclipse Temurin 21-jdk (CI/Docker) |
| **Key JEPs** | Virtual Threads (444), Records (395), Sealed Classes (405), Pattern Matching (427) |

## Build System

| Component | Details |
|-----------|---------|
| **Build Tool** | Gradle 8.7 (wrapper) / 8.12.1 (CI) |
| **Build Language** | Groovy `.gradle` files |
| **Version Catalog** | `gradle/libs.versions.toml` (TOML format) |
| **Config Cache** | Enabled (`org.gradle.configuration-cache=true`) |
| **Project Version** | `cajunVersion=0.4.0` (in gradle.properties) |

## Module Structure

```
cajun (root)
├── cajun-core          — Core abstractions and interfaces
├── cajun-mailbox       — High-performance MPSC mailbox implementations
├── cajun-persistence   — Persistence implementations (File + LMDB)
├── cajun-cluster       — Cluster runtime (Etcd + gRPC)
├── cajun-system        — Main actor system (composes all, WIP)
├── lib                 — Legacy monolithic module (deprecated v0.2.0, removed v0.5.0)
├── test-utils          — Reusable testing utilities (TestKit, TestProbe, etc.)
└── benchmarks          — JMH microbenchmarks
```

## Core Dependencies

### Logging
| Library | Version |
|---------|---------|
| SLF4J API | 2.0.9 → 2.0.16 |
| Logback Classic | 1.5.16 |
| Logback Core | 1.5.16 |

### Clustering & Networking
| Library | Version |
|---------|---------|
| jetcd-core (Etcd) | 0.8.4 |
| grpc-stub / core / netty / protobuf / util | 1.68.1 |
| Netty (all modules) | 4.1.116.Final |
| Vert.x Core | 4.5.11 |
| vertx-grpc | 4.5.11 |

### Persistence
| Library | Version |
|---------|---------|
| lmdbjava (LMDB) | 0.8.3 |

### Performance & Utilities
| Library | Version |
|---------|---------|
| JCTools (lock-free data structures) | 4.0.1 |
| Guava | 33.3.1-jre |
| Apache Commons Math3 | 3.6.1 |
| protobuf-java | 3.25.5 |
| jackson-core | 2.16.1 |
| Failsafe | 3.3.2 |

### Testing
| Library | Version |
|---------|---------|
| JUnit Jupiter API | 5.11.1 |
| junit-jupiter-engine | 5.10.0 |
| Mockito Core | 5.7.0 |
| mockito-junit-jupiter | 5.7.0 |
| Awaitility | 4.2.0 |

### Benchmarking
| Library | Version |
|---------|---------|
| JMH Core | 1.37 |
| JMH Annotation Processor | 1.37 |
| Gradle JMH Plugin | 0.7.2 (me.champeau.jmh) |

## System Requirements

- **JDK**: 21+ (hard requirement)
- **LMDB native lib**: `liblmdb0` (Ubuntu/Debian: `apt-get install -y liblmdb0`)
- **Etcd**: External instance required for cluster mode tests

## Publishing

- **Maven GroupId**: `com.cajunsystems`
- **Artifact IDs**: `cajun`, `cajun-core`, `cajun-mailbox`, `cajun-persistence`, `cajun-cluster`, `cajun-system`, `cajun-test`
- **Repository**: Maven Central via Sonatype OSSRH
- **License**: MIT
- **Artifacts**: Signed JARs + source + Javadoc

## CI/CD

- **Platform**: GitHub Actions (`.github/workflows/gradle.yml`)
- **Triggers**: Push/PR to `main`
- **Jobs**: Build (no tests), Test, Dependency Submission (Dependabot graph)
- **Dependency scanning**: GitHub Dependabot automated

## Security — Active CVE Constraints

| Dependency | Version | CVEs Fixed |
|-----------|---------|-----------|
| logback-core | 1.5.16 | CVE-2025-11226, CVE-2024-12798, CVE-2024-12801 |
| netty-codec-http2 | 4.1.116.Final | CVE-2023-44487 |
| netty-codec-http | 4.1.116.Final | CVE-2024-29025 |
| netty-handler | 4.1.116.Final | CVE-2025-24970 |
| netty-common | 4.1.116.Final | CVE-2024-47535, CVE-2025-25193 |
| vertx-core | 4.5.11 | CVE-2024-1300 |
