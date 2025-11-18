# Cajun Modular Structure (v0.2.0+)

## Overview

Starting with v0.2.0, Cajun adopts a modular architecture using Java Platform Module System (JPMS) and Gradle multi-project builds. This provides better separation of concerns, clearer dependencies, and allows users to depend only on the modules they need.

## Module Structure

```
cajun/
├── cajun-core/          # Core abstractions and configuration
├── cajun-mailbox/       # High-performance mailbox implementations
├── cajun-persistence/   # Persistence backends (planned)
├── cajun-cluster/       # Clustering support (planned)
├── lib/                 # Main actor system (depends on all modules)
├── test-utils/          # Testing utilities
└── benchmarks/          # Performance benchmarks
```

---

## Modules

### 1. cajun-core

**Purpose**: Core abstractions and configuration classes

**Dependencies**:
- `org.slf4j:slf4j-api` (logging)

**Exports**:
- `com.cajunsystems.config` - Configuration classes (ThreadPoolFactory, etc.)

**Usage**:
```gradle
dependencies {
    implementation 'com.cajunsystems:cajun-core:0.2.0'
}
```

**Status**: ✅ Created (v0.2.0)

---

### 2. cajun-mailbox

**Purpose**: High-performance mailbox implementations

**Dependencies**:
- `cajun-core` (configuration)
- `org.jctools:jctools-core:4.0.1` (MPSC queues)

**Exports**:
- `com.cajunsystems.mailbox` - Mailbox interface and implementations
- `com.cajunsystems.mailbox.config` - Mailbox configuration

**Implementations**:
- `LinkedMailbox` - General-purpose (2-3x faster than legacy)
- `MpscMailbox` - High-throughput MPSC queue (5-10x faster)

**Usage**:
```gradle
dependencies {
    implementation 'com.cajunsystems:cajun-mailbox:0.2.0'
}
```

```java
import com.cajunsystems.mailbox.Mailbox;
import com.cajunsystems.mailbox.MpscMailbox;

Mailbox<MyMessage> mailbox = new MpscMailbox<>(256);
```

**Status**: ✅ Created (v0.2.0)

---

### 3. cajun-persistence

**Purpose**: Persistence backend implementations

**Dependencies**:
- `cajun-core` (abstractions)

**Exports** (planned):
- `com.cajunsystems.persistence.file` - File-based persistence
- `com.cajunsystems.persistence.memory` - In-memory persistence
- `com.cajunsystems.persistence.db` - Database backends (future)

**Status**: 📋 Planned (v0.3.0)

---

### 4. cajun-cluster

**Purpose**: Clustering and distribution support

**Dependencies**:
- `cajun-core` (abstractions)
- `io.etcd:jetcd-core` (leader election)

**Exports** (planned):
- `com.cajunsystems.cluster` - Cluster implementations
- `com.cajunsystems.cluster.election` - Leader election
- `com.cajunsystems.cluster.routing` - Message routing

**Status**: 📋 Planned (v0.3.0)

---

### 5. lib (Main Module)

**Purpose**: Complete actor system with all features

**Dependencies**:
- `cajun-core`
- `cajun-mailbox`
- All other runtime dependencies

**Exports**: All Cajun APIs

**Usage**:
```gradle
dependencies {
    // Get everything
    implementation 'com.cajunsystems:cajun:0.2.0'
}
```

**Status**: ✅ Updated to use modular dependencies (v0.2.0)

---

## Migration Guide

### For Existing Users (v0.1.x → v0.2.0)

**Good News**: Your code continues to work without changes!

The `lib` module now depends on `cajun-mailbox`, but provides backward compatibility wrappers:

```java
// OLD (still works, deprecated)
import com.cajunsystems.config.MailboxProvider;
import com.cajunsystems.config.DefaultMailboxProvider;

// NEW (recommended)
import com.cajunsystems.mailbox.config.MailboxProvider;
import com.cajunsystems.mailbox.config.DefaultMailboxProvider;
```

**Deprecation Warnings**:
- `com.cajunsystems.config.MailboxProvider` → Use `com.cajunsystems.mailbox.config.MailboxProvider`
- `com.cajunsystems.config.DefaultMailboxProvider` → Use `com.cajunsystems.mailbox.config.DefaultMailboxProvider`
- `com.cajunsystems.config.MailboxConfig` → Use `com.cajunsystems.mailbox.config.MailboxConfig`
- `com.cajunsystems.config.ResizableMailboxConfig` → Use `com.cajunsystems.mailbox.config.ResizableMailboxConfig`

These will be removed in **v0.3.0**.

---

## Dependency Graph

```
cajun-core (slf4j)
    ↑
    ├── cajun-mailbox (jctools)
    ├── cajun-persistence (planned)
    └── cajun-cluster (etcd, planned)
           ↑
           └── lib (guava, commons-math3, logback, etc.)
```

---

## Benefits of Modularization

### 1. **Minimal Dependencies**
Use only what you need:
```gradle
// Just need mailboxes for custom integration?
implementation 'com.cajunsystems:cajun-mailbox:0.2.0'

// Full actor system
implementation 'com.cajunsystems:cajun:0.2.0'
```

### 2. **Clear Boundaries**
- Module system enforces encapsulation
- Internal packages cannot be accessed
- API surface is explicit

### 3. **Faster Builds**
- Modules can be built in parallel
- Incremental compilation per module
- Smaller artifacts

### 4. **Better Testing**
- Each module can be tested independently
- Clearer test dependencies
- Easier to mock

### 5. **Pluggable Implementations**
- Swap mailbox implementations
- Choose persistence backend
- Optional clustering

---

## Development

### Building All Modules
```bash
./gradlew build
```

### Building Specific Module
```bash
./gradlew cajun-mailbox:build
./gradlew cajun-core:test
```

### Running Tests
```bash
# All tests
./gradlew test

# Module-specific
./gradlew cajun-mailbox:test
```

### Publishing
```bash
# Publish all modules
./gradlew publish

# Publish specific module
./gradlew cajun-mailbox:publish
```

---

## Future Roadmap

### v0.3.0 (Planned)
- Complete `cajun-persistence` module
  - File-based persistence
  - In-memory persistence
  - Pluggable backends
- Complete `cajun-cluster` module
  - Full cluster implementation
  - Leader election
  - Distributed actor placement
- Remove deprecated backward compatibility classes from `lib`

### v0.4.0 (Planned)
- `cajun-observability` module
  - Metrics integration (Micrometer)
  - Distributed tracing (OpenTelemetry)
  - Health checks
- `cajun-integrations` module
  - Spring Boot starter
  - Micronaut integration
  - Quarkus extension

### v1.0.0 (Future)
- Stabilized modular API
- Full JPMS support
- Comprehensive documentation
- Production-ready for all modules

---

## FAQ

**Q: Do I need to change my code for v0.2.0?**
A: No! Backward compatibility is maintained. You'll see deprecation warnings, but everything works.

**Q: When should I migrate to the new package names?**
A: Before v0.3.0 (next release). The old packages will be removed then.

**Q: Can I use just cajun-mailbox without the full actor system?**
A: Yes! That's the benefit of modularization. Just depend on `cajun-mailbox`.

**Q: Will module-info.java affect my non-modular project?**
A: No. The modules work fine on the classpath (non-modular mode). JPMS is optional.

**Q: How do I know which module to depend on?**
A: For most users, just use `cajun` (the `lib` module). Advanced users can pick specific modules.

---

## Resources

- **Source**: https://github.com/CajunSystems/cajun
- **Issues**: https://github.com/CajunSystems/cajun/issues
- **Discussions**: https://github.com/CajunSystems/cajun/discussions

---

## Contributors

Modularization implemented in v0.2.0 as part of the performance improvement initiative.

For questions or feedback, please open an issue on GitHub.
