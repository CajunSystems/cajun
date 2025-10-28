# Changelog

All notable changes to the Cajun actor system will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
