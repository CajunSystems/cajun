# Architecture

## System Design

**Type**: Hybrid Actor Model with Layered/Modular Architecture

Cajun is an Erlang OTP-inspired distributed actor system for Java 21+. It combines:
- **Actor Model**: Message-passing concurrency, no shared mutable state
- **Event-Driven**: Message-driven execution via mailboxes
- **Layered**: Clear handler/actor/system separation
- **Pluggable**: Persistence, mailbox, and thread-pool strategies are all injectable
- **Optional Clustering**: Transparent local/remote message routing via Etcd + gRPC

## Core Execution Model

```
Virtual Threads (default)     ← Java 21+ near-zero overhead
    ↓
MailboxProcessor              ← Polling loop per actor
    ↓
MPSC Mailbox                  ← Lock-free JCTools queue
    ↓
Actor.receive(message)        ← Single-threaded per actor
    ↓
Handler.receive(msg, ctx)     ← User business logic
```

## Key Abstractions

### Programming Models (two styles)

**Interface-Based (Preferred)**
- `Handler<Message>` — stateless message processing
- `StatefulHandler<State, Message>` — returns new immutable state after each message
- Created via `ActorBuilder` / `StatefulActorBuilder` fluent APIs

**Inheritance-Based (Legacy)**
- `Actor<Message>` — base class with lifecycle hooks
- `StatefulActor<State, Message>` — adds persistence and state management
- Direct subclassing, more control but more coupling

### Core Classes

| Class | Role | Path |
|-------|------|------|
| `ActorSystem` | Main entry point, actor registry | `lib/.../ActorSystem.java` (~1200 LOC) |
| `Actor<M>` | Base actor, lifecycle management | `lib/.../Actor.java` (~800 LOC) |
| `StatefulActor<S,M>` | Persistence + state management | `lib/.../StatefulActor.java` (~1200 LOC) |
| `Pid` | Process ID, message sending | `lib/.../Pid.java` |
| `ActorContext` | Restricted actor API for handlers | `lib/.../ActorContext.java` |
| `HandlerActor<M>` | Adapter: Handler → Actor | `lib/.../internal/HandlerActor.java` |
| `StatefulHandlerActor<S,M>` | Adapter: StatefulHandler → StatefulActor | `lib/.../internal/StatefulHandlerActor.java` |
| `MailboxProcessor<M>` | Mailbox polling loop | `lib/.../MailboxProcessor.java` |

## Message Flow

### Fire-and-Forget
```
pid.tell(message)
    → ActorSystem.routeMessage()
    → Actor.mailbox.offer(message)
    → MailboxProcessor (polling)
    → Actor.receive(message)
    → Handler.receive(message, context)
```

### Ask Pattern (Request-Response)
```
system.ask(pid, message, timeout)
    → Create temporary reply actor
    → Wrap in AskPayload(message, replyRequestId)
    → Target handler calls ctx.reply() or sender.tell()
    → Reply actor resolves CompletableFuture
    → Caller's future completes
```

### Persistence Flow (StatefulActor)
```
Message arrives
    → Persist to MessageJournal
    → Handler.receive() → new immutable state
    → Update currentState (AtomicReference)
    → Check snapshot trigger (time or change count)
    → Snapshot to SnapshotStore
    → Cleanup old journals/snapshots
```

### Cluster Message Flow
```
pid.tell(message)
    → ClusterActorSystem.routeMessage()
    → RendezvousHashing.hash(actorId) → node assignment
    → Local: direct mailbox delivery
    → Remote: ReliableMessagingSystem → Etcd/Network → Remote node
              → Remote ClusterActorSystem.handleRemoteMessage()
              → Local mailbox delivery
```

## Design Patterns

| Pattern | Usage |
|---------|-------|
| **Builder** | `ActorBuilder`, `StatefulActorBuilder`, `BackpressureBuilder` |
| **Adapter** | `HandlerActor`, `StatefulHandlerActor`, `FunctionalHandlerAdapter` |
| **Strategy** | `BackpressureStrategy`, `DeliveryGuarantee`, `WorkloadType`, `PersistenceProvider` |
| **Factory** | `PersistenceProvider`, `MailboxProvider`, `ThreadPoolFactory` |
| **Observer** | `BackpressureEvent` callbacks, Reply callbacks |
| **Registry** | `ActorSystem.actors` (ConcurrentHashMap), `PersistenceProviderRegistry` |
| **Monad** | `Effect<S,E,R>`, `Result<T>`, `Reply<T>` |
| **Trampoline** | `Trampoline<A>` for stack-safe recursion in Effect |
| **Supervision** | Hierarchical parent-child with RESUME/RESTART/STOP/ESCALATE |

## Layered Architecture

```
┌─────────────────────────────────────────────────────┐
│ Handler Layer  — Business logic (what to do)         │
│   Handler<M>, StatefulHandler<S,M>                  │
├─────────────────────────────────────────────────────┤
│ Actor Layer    — Infrastructure (how to do it)       │
│   Actor<M>, StatefulActor<S,M>, HandlerActor        │
├─────────────────────────────────────────────────────┤
│ System Layer   — Orchestration (actor lifecycle)     │
│   ActorSystem, ClusterActorSystem                   │
├─────────────────────────────────────────────────────┤
│ Infrastructure — Pluggable backends                  │
│   Mailbox, PersistenceProvider, ThreadPoolFactory   │
└─────────────────────────────────────────────────────┘
```

## Persistence Architecture

Two backends available:

**FileSystem** (dev/testing)
- `FileMessageJournal`, `FileSnapshotStore`
- 10K–50K msg/sec sequential writes
- `FileSystemCleanupDaemon` + `FileSystemTruncationDaemon` for housekeeping

**LMDB** (production)
- `LmdbPersistenceProvider`, `LmdbMessageJournal`, `LmdbSnapshotStore`
- Memory-mapped key-value store, very fast
- Requires `liblmdb0` native library
- Risk: `MDB_MAP_FULL` if mapSize undersized

Recovery strategy: latest snapshot + message journal replay (hybrid).

## Backpressure Architecture

State machine: `NORMAL → WARNING → CRITICAL → RECOVERY`

Strategies: `BLOCK`, `DROP_NEW`, `DROP_OLDEST`, `CUSTOM`

Per-actor `BackpressureManager` + system-wide `SystemBackpressureMonitor`.

## Functional/Effect System

Optional advanced layer for functional actor programming:
- `Effect<State, Error, Result>` — stack-safe effect monad
- `Trampoline<A>` — prevents StackOverflow in recursive effects
- `EffectGenerator` — generator-style effects (experimental)
- `capabilities/` — pluggable capability system (experimental)

## Clustering

Requires external Etcd instance. Components:
- `EtcdMetadataStore` — leader election, actor placement metadata
- `RendezvousHashing` — consistent actor-to-node assignment
- `ReliableMessagingSystem` + `MessageTracker` — delivery guarantees
- `DeliveryGuarantee`: AT_MOST_ONCE, AT_LEAST_ONCE, EXACTLY_ONCE
