# Cjent: Agentic AI Framework with Cajun

Cjent is a demonstration of how cleanly and powerfully the **Cajun Actor System** can model Agentic AI workflows. By mapping AI concepts (Agents, LLMs, Tools) to independent message-passing Actors, we eliminate state-sharing complications while scaling concurrently with ease.

## Why Actors for AI Agents?

When modeling an AI agent, you often need to manage:
1. **Conversation History** (Memory)
2. **LLM Inferences** (Reasoning)
3. **Tool Execution** (Interacting with external services)

Traditionally, doing this concurrently leads to thread locks and race conditions. The **Cajun Actor System** makes this trivial:
- **`AgentHandler`** is a *Stateful Actor*, keeping the memory isolated and safe.
- **`MockLLMHandler`** is a deductive *Worker Actor*, executing concurrently on Java 21 Virtual Threads.
- **`ToolExecutorHandler`** is an I/O *Worker Actor*, handling simulated blocking API calls over Virtual Threads.

Because everything is asynchronous message passing, the AI Agent never blocks waiting for a tool to execute—it can continue handling other messages in its mailbox.

## Architecture & Components

Cjent consists of four primary components:

1. **`AgentMessage` Protocol**: Robust Java 21 Sealed Interfaces mapped for the agent's cognition cycle (`Prompt`, `TryThink`, `LLMResponse`, `ToolResult`).
2. **`AgentHandler` (Coordinator)**: Receives a prompt from the user, forwards memory to the LLM, parses the Strategy, commands tools to execute, and eventually replies to the user.
3. **`MockLLMHandler` (Intelligence)**: An Actor simulating an LLM generating tool call parameters or final textual responses.
4. **`ToolExecutorHandler` (I/O)**: Given commands like `weather` or `calculator`, it executes I/O autonomously and returns a `ToolResult` to the originating Agent.

## How to Run the Example

The `ExampleRunner` seeds an actor system, spawns an AI Agent, and tests it with multiple concurrent prompts:

From the `cajun` project root, run:
```bash
./gradlew :examples:cjent:run
```

You will see output detailing the asynchronous intelligence loop:

1. Agent receives `Hello!` -> LLM translates into a final answer.
2. Agent receives `calculate the meaning of life` -> LLM triggers the `calculator` tool -> Tool returns `42` -> LLM provides final answer.
3. Agent receives `what is the weather today?` -> LLM triggers the `weather` tool -> Tool returns `Sunny, 75F` -> LLM provides final answer.

## Extending Cjent

To extend this into a real framework:
1. Swap `MockLLMHandler` with an actual LLM client (e.g. using an OpenAI SDK or LangChain4j).
2. Register real Java implementations inside `ToolExecutorHandler`.
3. Add multiple `AgentHandler` sub-agents that talk to each other to create a mixture-of-experts swarm!
