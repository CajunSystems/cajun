# Cajun Cluster Mode

This package provides cluster mode capabilities for the Cajun actor system, allowing actors to be distributed across multiple nodes in a cluster.

## Features

- **Distributed Actor Assignment**: Actors are assigned to nodes in the cluster using rendezvous hashing for consistent distribution.
- **Leader Election**: A leader node is elected to manage actor assignments and handle node failures.
- **Remote Messaging**: Messages can be sent to actors regardless of which node they're running on.
- **Fault Tolerance**: When a node fails, its actors are automatically reassigned to other nodes in the cluster.

## Components

### MetadataStore

The `MetadataStore` interface provides an abstraction for a distributed key-value store used to maintain cluster metadata, such as actor assignments and leader election. The default implementation uses etcd as the backend.

### MessagingSystem

The `MessagingSystem` interface provides an abstraction for communication between nodes in the cluster. The default implementation uses direct TCP connections.

### ClusterActorSystem

The `ClusterActorSystem` class extends the standard `ActorSystem` to support cluster mode. It manages actor assignments, leader election, and remote messaging.

## Usage

### Basic Setup

```java
// Create a metadata store (using etcd)
MetadataStore metadataStore = new EtcdMetadataStore("http://localhost:2379");

// Create a messaging system (using direct TCP)
MessagingSystem messagingSystem = new DirectMessagingSystem("system1", 8080);

// Create a cluster actor system
ClusterActorSystem system = new ClusterActorSystem("system1", metadataStore, messagingSystem);

// Start the system
system.start().get();

// Create actors as usual
Pid actor = system.register(MyActor.class, "my-actor");

// Send messages as usual
actor.tell("Hello, actor!");

// Shut down the system when done
system.stop().get();
```

### Multiple Nodes

To create a cluster with multiple nodes, you need to:

1. Set up a shared etcd cluster for all nodes
2. Create a `ClusterActorSystem` on each node with a unique ID
3. Configure the messaging systems to communicate with each other

```java
// Node 1
MetadataStore metadataStore1 = new EtcdMetadataStore("http://etcd-host:2379");
DirectMessagingSystem messagingSystem1 = new DirectMessagingSystem("node1", 8080);
messagingSystem1.addNode("node2", "node2-host", 8080);
ClusterActorSystem system1 = new ClusterActorSystem("node1", metadataStore1, messagingSystem1);
system1.start().get();

// Node 2
MetadataStore metadataStore2 = new EtcdMetadataStore("http://etcd-host:2379");
DirectMessagingSystem messagingSystem2 = new DirectMessagingSystem("node2", 8080);
messagingSystem2.addNode("node1", "node1-host", 8080);
ClusterActorSystem system2 = new ClusterActorSystem("node2", metadataStore2, messagingSystem2);
system2.start().get();
```

## Implementation Details

### Actor Assignment

Actors are assigned to nodes using rendezvous hashing, which provides a consistent distribution even when nodes join or leave the cluster. When a node fails, its actors are automatically reassigned to other nodes.

### Leader Election

A leader node is elected using a distributed lock in the metadata store. The leader is responsible for:
- Reassigning actors when nodes join or leave the cluster
- Monitoring node health through heartbeats
- Managing cluster-wide operations

### Remote Messaging

When a message is sent to an actor, the system first checks if the actor is local. If not, it looks up the actor's location in the metadata store and forwards the message to the appropriate node.

## Extending the System

### Custom Metadata Store

You can implement your own metadata store by implementing the `MetadataStore` interface. This allows you to use different backends such as Redis, ZooKeeper, or a custom solution.

### Custom Messaging System

You can implement your own messaging system by implementing the `MessagingSystem` interface. This allows you to use different communication protocols or message brokers like RabbitMQ, Kafka, or gRPC.
