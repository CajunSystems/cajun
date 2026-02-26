# Structure

## Root Layout

```
cajun/
├── .github/workflows/          — CI/CD (GitHub Actions)
├── benchmarks/                 — JMH microbenchmarks + Dockerfile
├── cajun-cluster/              — Cluster runtime implementations
├── cajun-core/                 — Core abstractions and interfaces
├── cajun-mailbox/              — High-performance mailbox implementations
├── cajun-persistence/          — Persistence implementations (File + LMDB)
├── cajun-system/               — Main actor system (WIP modular replacement)
├── docs/                       — Architecture docs, guides, audit reports
├── gradle/                     — Gradle wrapper + version catalog
├── lib/                        — Legacy monolithic module (deprecated → v0.5.0)
├── test-utils/                 — Reusable test utilities module
├── build.gradle                — Root build config
├── settings.gradle             — Module declarations
├── gradle.properties           — Version + build properties
└── CLAUDE.md                   — Project guidance
```

## lib/ — Legacy Main Module (Deprecated)

```
lib/src/main/java/com/cajunsystems/
├── Actor.java                  — Base actor class (~800 LOC)
├── ActorSystem.java            — Main entry point (~1200 LOC)
├── ActorContext.java           — Handler-facing API
├── Pid.java                    — Process identifier (record)
├── StatefulActor.java          — Actor + persistence (~1200 LOC)
├── MailboxProcessor.java       — Mailbox polling loop (~250 LOC)
├── FunctionalActor.java        — Lambda-based actors (deprecated)
├── FunctionalStatefulActor.java
├── Reply.java / PendingReply.java  — Ask pattern support
├── Result.java                 — Error handling (sealed interface)
│
├── backpressure/               — Backpressure management
│   ├── BackpressureManager.java    (~967 LOC)
│   ├── BackpressureBuilder.java
│   ├── BackpressureEvent.java
│   ├── BackpressureState.java  — NORMAL/WARNING/CRITICAL/RECOVERY
│   ├── BackpressureStrategy.java   — BLOCK/DROP_NEW/DROP_OLDEST/CUSTOM
│   ├── BackpressureStatus.java
│   └── SystemBackpressureMonitor.java
│
├── builder/                    — Fluent builder APIs
│   ├── ActorBuilder.java       — Create stateless actors (~300 LOC)
│   ├── StatefulActorBuilder.java   — Create stateful actors
│   ├── IdStrategy.java
│   └── IdTemplateProcessor.java
│
├── cluster/                    — Distributed actor support
│   ├── ClusterActorSystem.java (~400 LOC)
│   ├── DeliveryGuarantee.java  — AT_MOST_ONCE/AT_LEAST_ONCE/EXACTLY_ONCE
│   ├── MessagingSystem.java
│   ├── MetadataStore.java
│   ├── ReliableMessagingSystem.java
│   ├── MessageTracker.java
│   └── RendezvousHashing.java
│
├── config/                     — Configuration classes
│   ├── BackpressureConfig.java
│   ├── ThreadPoolFactory.java  — IO_BOUND/CPU_BOUND/MIXED workload types
│   ├── MailboxConfig.java      — (deprecated)
│   ├── ResizableMailboxConfig.java — (deprecated)
│   └── DefaultMailboxProvider.java — (deprecated)
│
├── functional/                 — Effect monad and generator system
│   ├── Effect.java             — Core monad (~931 LOC)
│   ├── EffectResult.java
│   ├── ThrowableEffect.java
│   ├── EffectMatchBuilder.java
│   ├── EffectConversions.java
│   ├── EffectActorBuilder.java
│   ├── EffectGenerator.java    — Generator support (experimental)
│   ├── GeneratorContext.java
│   ├── ActorSystemEffectExtensions.java
│   ├── capabilities/           — Pluggable capability system (experimental)
│   │   ├── Capability.java
│   │   ├── CapabilityHandler.java
│   │   ├── LogCapability.java
│   │   └── ConsoleLogHandler.java
│   └── internal/
│       ├── Trampoline.java     — Stack-safe recursion
│       └── EffectGenerator*.java
│
├── handler/                    — Handler interfaces
│   ├── Handler.java            — Stateless message processing
│   ├── StatefulHandler.java    — Stateful message processing
│   ├── FunctionalHandlerAdapter.java
│   └── StatefulFunctionalHandlerAdapter.java
│
├── internal/                   — Internal adapter implementations
│   ├── HandlerActor.java       — Handler → Actor adapter
│   ├── StatefulHandlerActor.java   — StatefulHandler → StatefulActor adapter
│   └── StatefulActorContext.java
│
├── metrics/                    — Actor metrics
│   ├── ActorMetrics.java
│   └── MetricsRegistry.java
│
├── persistence/                — Persistence abstractions
│   ├── PersistenceProvider.java
│   ├── PersistenceProviderRegistry.java
│   ├── MessageJournal.java
│   ├── BatchedMessageJournal.java
│   ├── SnapshotStore.java
│   ├── JournalEntry.java / SnapshotEntry.java
│   ├── MessageAdapter.java     — Read-only message wrapping
│   ├── MessageUnwrapper.java
│   ├── PidRehydrator.java
│   ├── RetryStrategy.java
│   ├── OperationAwareMessage.java
│   └── impl/
│       └── FileSystemPersistenceProvider.java
│
└── runtime/                    — Runtime daemons
    ├── cluster/
    └── persistence/
```

## cajun-mailbox/ — Mailbox Module

```
cajun-mailbox/src/main/java/com/cajunsystems/mailbox/
├── Mailbox.java                — Interface
├── MpscMailbox.java            — Multi-Producer Single-Consumer (JCTools)
├── LinkedMailbox.java          — Linked-list based
└── config/
    ├── MailboxProvider.java
    ├── MailboxCreationStrategy.java
    ├── DefaultMailboxProvider.java
    ├── IoOptimizedStrategy.java
    ├── CpuOptimizedStrategy.java
    ├── MixedWorkloadStrategy.java
    └── MailboxConfig.java
```

## cajun-persistence/ — Persistence Module

```
cajun-persistence/src/main/java/com/cajunsystems/persistence/
├── filesystem/                 — File-based (dev/testing)
│   ├── FileMessageJournal.java
│   ├── BatchedFileMessageJournal.java
│   ├── FileSnapshotStore.java
│   ├── PersistenceFactory.java
│   ├── JournalCleanup.java
│   ├── FileSystemCleanupDaemon.java
│   └── FileSystemTruncationDaemon.java
└── lmdb/                       — LMDB-based (production)
    ├── LmdbPersistenceProvider.java
    ├── LmdbMessageJournal.java
    ├── LmdbBatchedMessageJournal.java
    ├── SimpleBatchedMessageJournal.java
    └── LmdbSnapshotStore.java
```

## cajun-cluster/ — Cluster Module

```
cajun-cluster/src/main/java/com/cajunsystems/cluster/impl/
├── ClusterFactory.java
├── EtcdMetadataStore.java
└── DirectMessagingSystem.java
```

## test-utils/ — Test Utilities Module

```
test-utils/src/main/java/com/cajunsystems/test/
├── TestKit.java                — Main entry point (AutoCloseable, fluent)
├── TestProbe.java              — Message capture probe
├── TestPid.java                — PID wrapper for testing
├── MailboxInspector.java       — Inspect mailbox contents
├── StateInspector.java         — Access actor state
├── MessageCapture.java         — Capture and inspect messages
├── AskTestHelper.java          — Ask pattern testing
├── AsyncAssertion.java         — Async condition waiting
├── PerformanceAssertion.java   — Performance measurement
├── MessageMatcher.java         — Message matching utilities
└── TempPersistenceExtension.java   — JUnit extension for temp persistence
```

## docs/ — Documentation

```
docs/
├── persistence_guide.md
├── supervision_audit.md        — Known issues, fixed bugs
├── functional_actor_evolution.md
├── algebraic_effects_implementation.md
├── generator_effect_evaluation.md
├── layer_based_dependency_injection.md
└── pure_layer_system_design.md
```
