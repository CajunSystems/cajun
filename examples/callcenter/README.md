# Cajun Call Center Example

This example demonstrates how to build a highly scalable, non-blocking Call Center / Contact Center architecture using the **Cajun** Actor framework. 

Call centers are considered a **textbook use case for the Actor Model**. Routing, queuing, and state management in highly concurrent environments are incredibly difficult (and error-prone) when using traditional thread-and-lock database models (hello, thundering herd problem!), but are incredibly natural and lock-free using actors.

## Architecture

The system represents the core entities of a call center as independent Actors, ensuring that state mutations are thread-confined and race-condition free.

### The Actors

* **`WorkItemHandler` (The Call/Chat)**
  Represents the lifecycle of an incoming interaction. It manages its own state (`WAITING`, `CONNECTED`, `ENDED`) and sends an `Enqueue` event to the `QueueHandler` when it starts. It naturally terminates itself using native `Cajun` delayed messages.

* **`QueueHandler` (The Matchmaker)**
  Represents a routing queue (e.g., "Support", "Sales"). It maintains the state of incoming caller ids and the current status of all subscribed agents. Because an Actor processes exactly one message at a time sequentially, the `QueueHandler` perfectly assigns new calls to `AVAILABLE` agents one-at-a-time, without ever needing distributed locks or dealing with race-conditions. 

* **`AgentHandler` (The Human / AI)**
  Represents the worker. It transitions between `AVAILABLE`, `BUSY`, and `WRAP_UP`. It can initiate **Cold Transfers** (re-queuing) or **Warm Transfers** (direct agent-to-agent) by notifying the `WorkItem` to swap its context. It uses native actor `tellSelf(delay)` messages to simulate call handle time and wrap-up time without blocking underlying JVM threads. 

### Message Flow (The Happy Path)

1. **Inbound**: A simulated call arrives. `WorkItem` is spawned and sends `Enqueue` to the `Queue`.
2. **Registration**: Agents send `RegisterAgent` to the `Queue`, making themselves `AVAILABLE`.
3. **Matching**: The `Queue` receives an `Enqueue` message and instantly pairs the call with an `AVAILABLE` agent by firing an `AssignCall` message.
4. **Processing**: The Agent goes `BUSY` and handles the call. 
5. **Wrap Up**: The Agent finishes, goes into `WRAP_UP` (delay), and finally sends an `AgentAvailable` message back to the queue, becoming ready for the next call!

### Enhanced Handoffs (The Transfer Flow)

Cajun makes complex telephony handoffs simple by keeping the state in the `WorkItem` (the call):

*   **Cold Transfer**: An agent sends the call back to the `support-queue`. Cajun handles this as a **Priority Enqueue**, jumping the customer to the front of the line so they aren't forced to wait behind new callers.
*   **Warm Transfer**: Agent Alice "hands off" the `WorkItem` directly to Agent Bob's mailbox. Because the `WorkItem` is the coordinator, it atomically switches its `assignedAgent` pointer, ensuring no two agents are bridged to the same audio stream at once.

## How to Run

To execute the simulation, use the provided Gradle task. Ensure your `JAVA_HOME` points to JDK 21.

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.8/libexec/openjdk.jdk/Contents/Home
./gradlew :examples:callcenter:run -q
```

*(Note: `-q` removes the standard Gradle console output to clearly show the actor system's application logs)*

### Telephony Integration (Twilio Webhooks)

The system includes a live **Javalin** HTTP server (default port `7001`) that bridges real Twilio events into the actor system. You can interact with the live system using `curl`:

- **Incoming Call**: `curl -X POST http://localhost:7001/twilio/incoming -d 'CallSid=C123&From=+1-555-CAJUN'`
- **Status Change (Hangup)**: `curl -X POST http://localhost:7001/twilio/status -d 'CallSid=C123&CallStatus=completed'`
- **Trigger Cold Transfer**: `curl -X POST http://localhost:7001/twilio/transfer/cold?AgentId=agent-alice`
- **Trigger Warm Transfer**: `curl -X POST http://localhost:7001/twilio/transfer/warm?AgentId=agent-alice&TargetAgentId=agent-bob`

## Extending It

Because this is built on top of the Cajun actor system, you can easily tie this into the `cjent` framework. By simply spawning a `Cjent` AI Actor instead of our simulated `AgentHandler`, you could have an LLM automatically start answering queued `WorkItem` actors natively!

## Why Actors over Spring + JPA?

If you were building this using a traditional Web Framework (like Spring Boot) backed by a relational database, you would immediately run into severe concurrency bottlenecks:

1. **The "Thundering Herd" Pattern**: When 50 agents are `AVAILABLE` and exactly 1 call enters the queue, 50 database connections might try to execute `SELECT * FROM calls FOR UPDATE LIMIT 1` simultaneously. Only one succeeds, and 49 connections waste resources and throw lock contention exceptions.
2. **Distributed Locks**: To route a call from a queue, you usually have to lock the `Call` row and the `Agent` row simultaneously. Deadlocks become rampant without complex `Redis` or `ZooKeeper` distributed locking mechanisms.
3. **Lost Updates & Stale Read**: Two requests (`/webhook/call-end` and `/webhook/agent-pickup`) occur at the exact same millisecond. Traditional REST relies on optimistic locking (e.g., `@Version` in Hibernate), meaning one request crashes with an `OptimisticLockException` and requires manual retries.

### The Actor Advantage
With an Actor model, **State is kept in memory** and **thread-safety is guaranteed at the mail-box layer**. 
- When 1 call enters the system, exactly 1 message is appended to the `QueueActor`'s mailbox. 
- The `QueueActor` processes exactly one message at a time. It pops from its internal sequential list of available agents, assigns the call, and moves on. 
- **Zero locks. Zero database contention. Zero race conditions.** All state is naturally serialized without blocking the JVM's threads!
