# Changelog

All notable changes to the Cajun actor system will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Versioned Persistence System**: Complete implementation of automatic schema evolution for stateful actors
  - `VersionedJournalEntry` and `VersionedSnapshotEntry`: Wrapper classes that add version metadata to persisted data
  - `MessageMigrator`: Central registry for migration functions with support for single-step and multi-hop migrations
  - `MigrationKey`: Composite key for efficient migration lookup
  - `MigrationMetrics`: Thread-safe performance tracking for migration operations
  - `VersionedPersistenceProvider`: Wraps any persistence provider to add versioning capabilities
  - `VersionedMessageJournal` and `VersionedBatchedMessageJournal`: Auto-migrate messages during recovery
  - `VersionedSnapshotStore`: Auto-migrate state snapshots during recovery
  - `StatefulActorBuilder.withVersionedPersistence()`: Fluent API for enabling versioned persistence
  - Comprehensive documentation in `docs/versioned_persistence_guide.md`
  - Real-world examples demonstrating schema evolution (OrderMessage V0→V1)

### Changed
- **StatefulActorBuilder**: Added two new convenience methods for versioned persistence configuration
  - `withVersionedPersistence(provider, migrator)`: Simple setup with defaults
  - `withVersionedPersistence(provider, migrator, version, autoMigrate)`: Advanced configuration
- **README**: Added comprehensive "Versioned Persistence (Schema Evolution)" section with:
  - Quick start guide
  - Comparison table (versioned vs unversioned)
  - Advanced usage examples (bidirectional migration, multi-hop, metrics)
  - Clear guidance on when to use versioned persistence

### Fixed
- **Javadoc Warnings**: Reduced Javadoc warnings from 100 to 53 by adding comprehensive documentation
  - All versioned persistence classes now have complete Javadoc
  - Fixed malformed HTML in Javadoc comments (escaped `<` characters)
  - Added missing `@param` tags for generic type parameters
  - Added field documentation for private fields in core classes

### Documentation
- **Versioned Persistence Guide** (`docs/versioned_persistence_guide.md`): Complete user guide covering:
  - Quick start and core concepts
  - Best practices and migration patterns
  - Troubleshooting guide
  - Performance considerations
  - Complete API reference
- **Implementation Plan** (`docs/versioned_persistence_implementation_plan.md`): Updated with all completed phases
- **Message Versioning Strategies** (`docs/message_versioning_strategies.md`): Design rationale and strategy comparison

### Testing
- **91 New Tests**: Comprehensive test coverage for versioned persistence
  - Phase 1: 34 tests for core versioning infrastructure
  - Phase 2: 33 tests for message migrator with multi-hop migrations
  - Phase 3: 18 tests for versioned persistence provider
  - Phase 4: 3 integration tests with real-world examples
  - All tests passing ✅

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
