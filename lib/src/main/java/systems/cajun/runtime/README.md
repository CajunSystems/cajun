# Cajun Runtime Package

This package contains concrete implementations of the abstract interfaces defined in the core Cajun system. The separation of interfaces and implementations provides a clear boundary between abstract concepts and their concrete realizations.

## Package Structure

- `systems.cajun.runtime.persistence`: Contains implementations of persistence interfaces like `MessageJournal` and `SnapshotStore`
- `systems.cajun.runtime.cluster`: Contains implementations of cluster-related interfaces like `MessagingSystem` and `MetadataStore`

## Factory Classes

The runtime package provides factory classes to simplify the creation of concrete implementations:

- `systems.cajun.runtime.persistence.PersistenceFactory`: Creates persistence implementations
- `systems.cajun.runtime.cluster.ClusterFactory`: Creates cluster-related implementations

## Usage

### Persistence

```java
// Import the interfaces from the core package
import systems.cajun.persistence.MessageJournal;
import systems.cajun.persistence.SnapshotStore;

// Import the factory from the runtime package
import systems.cajun.runtime.persistence.PersistenceFactory;

// Create implementations using the factory
MessageJournal<MyMessage> journal = PersistenceFactory.createFileMessageJournal();
SnapshotStore<MyState> snapshotStore = PersistenceFactory.createFileSnapshotStore();
```

### Cluster

```java
// Import the interfaces from the core package
import systems.cajun.cluster.MessagingSystem;
import systems.cajun.cluster.MetadataStore;

// Import the factory from the runtime package
import systems.cajun.runtime.cluster.ClusterFactory;

// Create implementations using the factory
MessagingSystem messagingSystem = ClusterFactory.createDirectMessagingSystem("system1", 8080);
MetadataStore metadataStore = ClusterFactory.createEtcdMetadataStore("http://localhost:2379");
```

## Build Configuration

The runtime package is built as a separate JAR file (`cajun-runtime.jar`) that depends on the core JAR. This separation allows applications to depend only on the core interfaces without pulling in all the implementation dependencies.

To build the runtime JAR:

```bash
./gradlew runtimeJar
```

## Dependencies

The runtime package has additional dependencies beyond what the core package requires:

- `io.etcd:jetcd-core`: For the etcd-based metadata store implementation
- `ch.qos.logback:logback-classic`: For logging implementation

These dependencies are only required if you're using the specific implementations that need them.
