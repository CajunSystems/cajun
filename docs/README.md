# Cajun Documentation Index

Welcome to the Cajun Actor Framework documentation! This index helps you find the right documentation for your needs.

---

## Getting Started

📖 **[../README.md](../README.md)** - Start here!
- Quick start guide
- Core concepts
- Basic examples
- Performance overview

---

## Performance & Benchmarks

📊 **[BENCHMARKS.md](BENCHMARKS.md)** - Complete performance guide
- Benchmark results (I/O, CPU, mixed workloads)
- When to use actors vs threads
- Performance optimization tips
- Running benchmarks yourself

🔧 **[actor_batching_optimization.md](actor_batching_optimization.md)** - Message batching details
- How batching works
- Configuration options
- When batching helps
- Performance tuning

---

## Features & Configuration

### Mailboxes
📬 **[mailbox_guide.md](mailbox_guide.md)** - Mailbox types and configuration
- LinkedMailbox vs MpscMailbox
- Capacity configuration
- Backpressure behavior

### Backpressure
⚡ **[backpressure_system.md](backpressure_system.md)** - Backpressure system overview
- How backpressure works
- Configuration options
- Monitoring and tuning

📝 **[backpressure_readme.md](backpressure_readme.md)** - Quick backpressure guide

🔬 **[backpressure_system_enhancements.md](backpressure_system_enhancements.md)** - Recent improvements

### Persistence
💾 **[persistence_guide.md](persistence_guide.md)** - Actor persistence guide
- Filesystem vs LMDB backends
- State recovery
- Performance characteristics
- Best practices

### Clustering
🌐 **[cluster_mode.md](cluster_mode.md)** - Distributed actors
- Cluster mode overview
- Remote actor communication
- Fault tolerance

📈 **[cluster_mode_improvements.md](cluster_mode_improvements.md)** - Recent enhancements

---

## Advanced Topics

### Performance
⚡ **[performance_improvements.md](performance_improvements.md)** - Performance enhancements
- Optimization techniques
- Performance tuning guide

📊 **[performance_recommendation.md](performance_recommendation.md)** - Performance best practices

### Message Patterns
📨 **[sender_propagation.md](sender_propagation.md)** - Sender context and reply patterns
- Request-reply patterns
- Message forwarding
- Sender tracking

### Testing
🧪 **[test_utils_readme.md](test_utils_readme.md)** - Testing utilities
- Test harness usage
- Actor testing strategies
- Mock actors

---

## Quick Reference by Use Case

### "I'm building a microservice"
1. Read [../README.md](../README.md) - Quick start
2. Check [BENCHMARKS.md](BENCHMARKS.md) - Performance validation (0.02% overhead for I/O!)
3. Review [persistence_guide.md](persistence_guide.md) - State persistence
4. See [backpressure_system.md](backpressure_system.md) - Load handling

### "I need to handle high message volumes"
1. Review [BENCHMARKS.md](BENCHMARKS.md) - Performance characteristics
2. Read [actor_batching_optimization.md](actor_batching_optimization.md) - Batching configuration
3. Check [mailbox_guide.md](mailbox_guide.md) - Mailbox selection
4. See [backpressure_system.md](backpressure_system.md) - Backpressure configuration

### "I'm building a distributed system"
1. Read [cluster_mode.md](cluster_mode.md) - Cluster basics
2. Check [cluster_mode_improvements.md](cluster_mode_improvements.md) - Latest features
3. Review [persistence_guide.md](persistence_guide.md) - State recovery

### "I want to optimize performance"
1. Check [BENCHMARKS.md](BENCHMARKS.md) - Understand baseline performance
2. Read [performance_improvements.md](performance_improvements.md) - Optimization techniques
3. Review [actor_batching_optimization.md](actor_batching_optimization.md) - Batching tuning
4. See [performance_recommendation.md](performance_recommendation.md) - Best practices

### "I'm writing tests"
1. Read [test_utils_readme.md](test_utils_readme.md) - Testing guide
2. Check [../README.md](../README.md) - Basic testing examples

---

## Documentation by Category

### Core Features
- [../README.md](../README.md) - Main documentation
- [mailbox_guide.md](mailbox_guide.md) - Mailbox system
- [backpressure_system.md](backpressure_system.md) - Backpressure handling
- [persistence_guide.md](persistence_guide.md) - State persistence

### Performance
- [BENCHMARKS.md](BENCHMARKS.md) - Complete benchmark results
- [actor_batching_optimization.md](actor_batching_optimization.md) - Batching optimization
- [performance_improvements.md](performance_improvements.md) - Performance tuning
- [performance_recommendation.md](performance_recommendation.md) - Best practices

### Advanced Features
- [cluster_mode.md](cluster_mode.md) - Distributed actors
- [sender_propagation.md](sender_propagation.md) - Message patterns
- [test_utils_readme.md](test_utils_readme.md) - Testing utilities

### Updates & Enhancements
- [backpressure_system_enhancements.md](backpressure_system_enhancements.md)
- [cluster_mode_improvements.md](cluster_mode_improvements.md)

---

## External Resources

- **GitHub Repository**: [cajun](https://github.com/yourusername/cajun)
- **Maven Central**: [com.cajunsystems:cajun](https://search.maven.org/artifact/com.cajunsystems/cajun)
- **Issue Tracker**: [GitHub Issues](https://github.com/yourusername/cajun/issues)

---

## Contributing

Want to improve the documentation? See the main README for contribution guidelines.

---

**Last Updated:** November 19, 2025

