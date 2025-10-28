# Changelog

All notable changes to the Cajun actor system will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.2] - 2025-10-28

### Fixed
- **JVM Lifecycle Management**: Ensured that the actor system's scheduler threads are non-daemon threads, keeping the JVM alive after the main method completes until `system.shutdown()` is explicitly called. This is the correct behavior for a production actor system.

### Changed
- **Enhanced Documentation**: 
  - Added "Actor System Lifecycle" section explaining JVM lifecycle behavior
  - Clarified that explicit `system.shutdown()` is required to exit the JVM
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

[0.1.2]: https://github.com/cajunsystems/cajun/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/cajunsystems/cajun/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/cajunsystems/cajun/releases/tag/v0.1.0
