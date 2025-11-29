# Changelog

All notable changes to the Cajun actor system will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Effect Monad for Functional Actors**: Complete functional programming API for actor behaviors
  - **Stack-Safe**: Uses Trampoline for unbounded effect composition without stack overflow
  - **Type-Safe Error Handling**: `Effect<State, Error, Result>` with explicit error channel
  - **Composable Operations**: `map`, `flatMap`, `andThen`, `filter`, `recover`, `zip`, `parZip`
  - **Request-Response Pattern**: `Effect.ask(pid, message, timeout)` for actor communication
  - **Checked Exception Support**: `Effect.attempt(() -> ...)` with `ThrowingSupplier` interface
  - **Pattern Matching**: `Effect.match()` with type-safe message routing at match level
  - **Parallel Execution**: `parSequence`, `parZip`, `race`, `withTimeout` for concurrent operations
  - **Conditional Logic**: `Effect.when(predicate, effect, fallback)` for conditional execution
  - **Virtual Thread Optimized**: Natural blocking code without CompletableFuture complexity
  - **Factory Methods**: `of`, `pure`, `state`, `modify`, `setState`, `identity`, `fail`, `attempt`
  - **Messaging**: `tell`, `tellSelf`, `ask` for actor communication
  - **Logging**: `log`, `logError`, `logState` for debugging
  - **Documentation**: Comprehensive guides in `/docs` with beginner-friendly examples

- **3-Tier Ask Pattern**: Flexible request-response API with multiple access patterns
  - **Tier 1 - Direct Future**: `CompletableFuture<Response> future = system.ask(pid, request, timeout)`
    - Raw CompletableFuture for maximum flexibility
    - Compose with other futures using standard Java API
  - **Tier 2 - Reply Wrapper**: `Reply<Response> reply = pid.ask(request, timeout)`
    - Convenience methods: `get()`, `getOrElse(default)`, `getOrThrow()`
    - Functional operations: `map()`, `flatMap()`, `filter()`, `recover()`
    - Wraps CompletableFuture with ergonomic API
  - **Tier 3 - Effect Integration**: `Effect.ask(pid, request, timeout)`
    - Seamless integration with Effect monad
    - Automatic error handling and state threading
    - Suspension point for virtual threads
  - All tiers share the same underlying promise-based implementation
  - Choose the right abstraction level for your use case

### Changed
- **Effect Type Signature**: Simplified from `Effect<State, Message, Result>` to `Effect<State, Error, Result>`
  - Message type moved to match level: `Effect.<S, E, R, Message>match()`
  - Cleaner type signatures for effect composition
  - Error type is now explicit (typically `Throwable` or custom exception types)

### Documentation
- Added **Effect Monad Guide** (`docs/effect_monad_guide.md`) - Beginner-friendly introduction
- Added **Effect API Reference** (`docs/effect_monad_api.md`) - Complete API documentation
- Added **Functional Actor Evolution** (`docs/functional_actor_evolution.md`) - Advanced patterns
- Updated **README.md** with Effect examples and Virtual Threads advantages
- Added Mermaid diagrams for effect pipeline visualization

## [0.3.1] - 2025-11-24

### Fixed
- **StatefulActor Ask Pattern Bug**: Fixed critical bug where `getSender()` returned empty Optional in direct `StatefulActor` subclasses when using the ask pattern
  - **Root Cause**: `StatefulActor` processes messages asynchronously via `CompletableFuture`, and the sender context was captured in `asyncSenderContext` ThreadLocal but `getSender()` was inherited from base `Actor` class which used a different ThreadLocal (`senderContext`)
  - **Solution**: Override `getSender()` in `StatefulActor` to use `asyncSenderContext` instead of parent's `senderContext`, ensuring sender context is preserved across async boundaries
  - **Impact**: Ask pattern now works correctly with direct `StatefulActor` subclasses (e.g., `class MyActor extends StatefulActor<State, Message>`)
  - **Note**: `StatefulHandler`-based actors were not affected as `StatefulHandlerActor` already correctly implemented `getSender()` using `asyncSenderContext`
  - **Files Modified**: 
    - `StatefulActor.java`: Added `getSender()` override and `Optional` import
  - **Tests Added**:
    - `ActorAskPatternTest.java`: New test suite for direct `Actor` subclasses with ask pattern
    - `StatefulActorAskPatternTest.testAskPatternWithDirectStatefulActorSubclass()`: Test for direct `StatefulActor` subclasses

## [0.3.0] - 2025-11-23

### Changed
- **Promise-Based Ask Pattern**: Completely refactored the ask pattern from an actor-based approach to a **pure promise-based implementation** using CompletableFuture registry
  - **Performance**: ~100x faster - eliminated temporary actor creation/destruction overhead (~100μs → ~1μs per request)
  - **Reliability**: Zero race conditions - no thread startup timing issues
  - **Simplicity**: Direct future completion instead of actor lifecycle management
  - **Architecture**: 
    - Removed temporary "reply actor" spawning for each ask request
    - Added `PendingAskRequest` record to hold futures, timeouts, and completion flags
    - Added `ConcurrentHashMap<String, PendingAskRequest<?>>` registry for pending requests
    - Request IDs now use `"ask-" + UUID` pattern for easy identification
    - `routeMessage()` intercepts ask responses and completes futures directly
  - **Cleanup**: Proper timeout handling and shutdown cleanup of pending requests
  - **API**: Public API remains unchanged - transparent improvement
  - **Documentation**: Updated README.md and all docs to reflect promise-based implementation

- **High-Performance Mailbox Implementations**: Refactored mailbox layer for 2-10x throughput improvement
  - **New Mailbox Abstraction**: Created `Mailbox<T>` interface to decouple core from specific queue implementations
  - **LinkedMailbox** (Default): Uses `LinkedBlockingQueue` - 2-3x faster than old `ResizableBlockingQueue`
    - Lock-free optimizations for common cases (CAS operations)
    - Bounded or unbounded capacity
    - Good general-purpose performance (~100ns per operation)
  - **MpscMailbox** (High-Performance): Uses JCTools `MpscUnboundedArrayQueue` - 5-10x faster
    - True lock-free multi-producer, single-consumer
    - Minimal allocation overhead (~20-30ns per operation)
    - Optimized for high-throughput CPU-bound workloads
  - **Workload-Specific Selection**: `DefaultMailboxProvider` automatically chooses optimal mailbox based on workload type
    - `IO_BOUND` → LinkedMailbox (10K capacity, large buffer for bursty I/O)
    - `CPU_BOUND` → MpscMailbox (unbounded, highest throughput)
    - `MIXED` → LinkedMailbox (user-defined capacity)

- **Polling Optimization**: Reduced polling timeout from 100ms → 1ms
  - 99% reduction in empty-queue latency
  - Faster actor responsiveness
  - Minimal CPU overhead (virtual threads park efficiently)
  - Removed unnecessary `Thread.yield()` calls (not needed with virtual threads)

- **Logging Dependencies**: Changed SLF4J from bundled dependency to API-only dependency
  - **Breaking Change**: Users must now provide their own logging implementation (e.g., Logback, Log4j2)
  - SLF4J API is still used for all internal logging
  - Users must add Logback (or preferred SLF4J implementation) to their project dependencies
  - Example configuration files available in documentation
  - Provides flexibility for users to configure logging according to their needs

### Added
- **MailboxProcessor CountDownLatch**: Added thread readiness synchronization to ensure actor threads are running before `start()` returns, reducing timing issues during actor initialization
- **Mailbox Interface**: New `com.cajunsystems.mailbox.Mailbox<T>` abstraction for pluggable mailbox strategies
- **Persistence Truncation Modes**: Added configurable journal truncation strategies for stateful actors
  - **OFF**: Disable automatic truncation (journals grow indefinitely)
  - **SYNC_ON_SNAPSHOT**: Truncate journals synchronously during snapshot lifecycle (default)
    - Keeps configurable number of messages behind latest snapshot (default: 500)
    - Maintains minimum number of recent messages per actor (default: 5,000)
  - **ASYNC_DAEMON**: Truncate journals asynchronously using background daemon
    - Non-blocking truncation with configurable interval (default: 5 minutes)
    - Reduces impact on actor message processing performance
  - Configuration via `PersistenceTruncationConfig.builder()` with fluent API
  - Helps manage disk space and improve recovery time for long-running stateful actors
- **Performance Benchmarks**: Added comprehensive JMH benchmarks comparing actors vs threads vs structured concurrency
  - Fair benchmark methodology (pre-created actors)
  - Workload-specific performance validation
  - Detailed performance analysis in `docs/performance_improvements.md`
- **Module Breakdown** (In Progress): Started modularization of the library into separate Maven artifacts
  - `cajun-core`: Core actor abstractions and interfaces
  - `cajun-mailbox`: Mailbox implementations (LinkedMailbox, MpscMailbox)
  - `cajun-persistence`: Persistence layer with journaling and snapshots
  - `cajun-cluster`: Cluster and remote actor support
  - `cajun-system`: Main ActorSystem implementation (combines all modules)
  - `test-utils`: Testing utilities for async actor testing
  - Note: Module separation is ongoing - all functionality currently available through main `cajun` artifact

### Fixed
- **Ask Pattern Race Condition**: Eliminated race condition where reply actors might not be ready to receive responses (no longer relevant with promise-based approach)
- **Lock Contention**: Eliminated synchronized lock bottleneck in old `ResizableBlockingQueue` causing 5.5x slowdown in batch processing

### Deprecated
- **ResizableBlockingQueue**: Deprecated in favor of `LinkedMailbox` and `MpscMailbox` - still works but logs deprecation warning
- **ResizableMailboxConfig**: Still supported but logs deprecation warning

### Performance
- **Ask Pattern**: ~100x faster (100μs → 1μs per request)
- **Batch Processing**: 2-5x throughput improvement with new mailbox implementations
- **Memory**: 50% reduction in per-message overhead with MpscMailbox
- **GC Pressure**: Significantly reduced with chunked array allocation
- **Overall**: Actor overhead now <2x baseline (threads) for pre-created actors, down from 5.5x-18x

## [0.1.4] - 2025-11-01

### Added
- **Test-Utils Module**: New dedicated testing utilities module for writing cleaner, more reliable async tests
  - `AsyncAssertion`: Utility for polling conditions with configurable timeouts and intervals, replacing manual `Thread.sleep()` and polling loops
  - `MessageCapture`: Helper for capturing and asserting on messages sent to actors
  - `PerformanceAssertion`: Utilities for performance testing and benchmarking
  - `AskTestHelper`: Simplified testing utilities for ask pattern interactions
  - `TestPid`: Mock Pid implementation for testing without a full ActorSystem
- **ActorContext Logger**: Added `getLogger()` method to `ActorContext` that provides a pre-configured logger with automatic actor ID context for consistent logging across all actors
- **ReplyingMessage Interface**: Added standardized interface for request-response patterns with strong type contracts. Includes `reply()` convenience method on `ActorContext` for cleaner code
- **Documentation**: Added "ActorContext Convenience Features" section to README documenting `tellSelf()`, `getLogger()`, and `ReplyingMessage` patterns

### Changed
- **Actor Logging**: Each actor now has a dedicated logger instance initialized with the actor's class name and ID for better traceability
- **Test Refactoring**: Refactored 5 core test files to use test-utils library
  - Replaced 20+ `Thread.sleep()` calls with `AsyncAssertion.eventually()`
  - Replaced 6 `CountDownLatch` patterns with `AtomicBoolean` + `AsyncAssertion`
  - Eliminated manual polling loops in favor of declarative async assertions
  - Tests are now faster, more reliable, and easier to understand

### Fixed
- **Test Reliability**: Improved test stability by removing arbitrary sleep delays and using condition-based waiting

## [0.1.3] - 2025-10-28

### Fixed
- **Virtual Thread Keep-Alive**: Fixed JVM premature exit issue when using virtual threads (default configuration). Virtual threads are always daemon threads in Java and cannot be made non-daemon, causing the JVM to exit immediately after main() completes. Implemented a non-daemon keep-alive platform thread that blocks on a CountDownLatch until `system.shutdown()` is called, ensuring the JVM stays alive while actors are running.

### Added
- **Keep-Alive Mechanism**: Added `actor-system-keepalive` thread in ActorSystem that keeps the JVM alive when using virtual threads
- **Test Examples**: Added diagnostic test examples to verify JVM lifecycle behavior
  - `ThreadDiagnosticTest.java`: Shows all active threads and their daemon status
  - `SimpleLivenessTest.java`: Minimal test demonstrating JVM stays alive after main() exits
  - `KeepAliveWithShutdownTest.java`: Demonstrates proper shutdown behavior with automatic cleanup
- **Gradle Task**: Added `runExample` task to easily run example classes from the test directory

### Changed
- **Enhanced Documentation**: 
  - Updated "Actor System Lifecycle" section to explain the keep-alive thread mechanism
  - Clarified that virtual threads are always daemon threads and require special handling
  - Updated all example documentation to reference the keep-alive thread
  - Removed misleading comments about platform thread daemon status keeping JVM alive

## [0.1.2] - 2025-10-28

### Deprecated
- This version contained an incomplete fix attempt using non-daemon platform threads, which was ineffective with virtual threads. Use version 0.1.3 or later.

### Fixed
- **Virtual Thread Keep-Alive**: Fixed JVM premature exit issue when using virtual threads (default configuration). Virtual threads are always daemon threads in Java, so the JVM would exit immediately after main() completes. Implemented a non-daemon keep-alive thread that blocks until `system.shutdown()` is called, ensuring the JVM stays alive while actors are running.

### Added
- **Keep-Alive Mechanism**: Added `actor-system-keepalive` thread that keeps the JVM alive when using virtual threads
- **Test Examples**: Added diagnostic test examples to verify JVM lifecycle behavior
  - `ThreadDiagnosticTest.java`: Shows all active threads and their daemon status
  - `SimpleLivenessTest.java`: Minimal test demonstrating JVM stays alive after main() exits
  - `KeepAliveWithShutdownTest.java`: Demonstrates proper shutdown behavior

### Changed
- **Enhanced Documentation**: 
  - Added "Actor System Lifecycle" section explaining JVM lifecycle behavior
  - Clarified that explicit `system.shutdown()` is required to exit the JVM
  - Documented virtual thread support and keep-alive mechanism
  - Added examples demonstrating lifecycle management

## [0.1.1] - 2025-10-28

### Fixed
- **Ask Pattern ClassCastException**: Fixed a critical bug where using `actorSystem.ask()` would cause a `ClassCastException` due to `AskPayload` wrapper type mismatch. The system now automatically unwraps `AskPayload` messages and provides sender context via `getSender()`, allowing actors to work with their natural message types.

### Changed
- **Improved Ask Pattern API**: Actors no longer need to handle `AskPayload<T>` wrapper types. Instead, they receive their natural message types (e.g., `String`) and use `context.getSender()` to reply to ask requests.
- **Enhanced Documentation**: 
  - Added comprehensive Quick Start example showing ActorSystem instantiation
  - Clarified ask pattern usage with clear examples
  - Updated all ask pattern documentation to reflect the automatic unwrapping behavior
  - Added complete working examples for request-response patterns

### Added
- `ActorContext.getSender()`: New method to retrieve the sender Pid for ask pattern replies
- `Actor.getSender()`: Public method to access sender context (returns `null` for regular `tell()` messages)
- Internal `MessageWithSender` wrapper for transparent sender context propagation

## [0.1.0] - 2025-10-XX

### Added
- **Core Actor System**: Complete implementation of the actor model with message passing
- **Handler-based API**: Clean interface-based approach with `Handler<Message>` and `StatefulHandler<State, Message>`
- **Stateful Actors**: Support for actors with persistent state
- **Ask Pattern**: Request-response pattern with `CompletableFuture` support
- **Backpressure Management**: Configurable backpressure strategies and monitoring
- **Thread Pool Configuration**: Flexible thread pool configuration with workload optimization presets
  - Virtual threads (default for I/O-bound)
  - Fixed thread pools (for CPU-bound)
  - Work-stealing pools (for mixed workloads)
- **Mailbox Configuration**: Pluggable mailbox providers with resizable queues
- **Supervision Strategies**: Error handling with RESUME, RESTART, STOP, and ESCALATE strategies
- **Actor Hierarchies**: Parent-child relationships with supervision
- **Persistence Support**: 
  - State snapshots
  - Message journaling
  - Recovery strategies
  - Pluggable storage backends
- **Remote Actors** (Partial): Initial implementation of remote actor communication
- **Performance Optimizations**:
  - Batched message processing
  - Configurable batch sizes
  - High-throughput message passing
- **Comprehensive Testing**: Unit tests and performance benchmarks

### Documentation
- Complete README with examples
- API documentation
- Performance tuning guide
- Configuration examples

[0.1.3]: https://github.com/cajunsystems/cajun/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/cajunsystems/cajun/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/cajunsystems/cajun/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/cajunsystems/cajun/releases/tag/v0.1.0
