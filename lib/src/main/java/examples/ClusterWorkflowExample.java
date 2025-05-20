package examples;

import systems.cajun.Actor;
import systems.cajun.ActorSystem;
import systems.cajun.Pid;
import systems.cajun.SupervisionStrategy;
import systems.cajun.cluster.*;
import systems.cajun.runtime.cluster.ClusterFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example demonstrating a complex workflow execution system using ClusterActorSystem with two nodes.
 * The workflow consists of:
 * - A Source Actor that initiates the workflow and distributes work to multiple paths
 * - Multiple Processor Actors that perform operations in parallel and sequential paths (distributed across Node 1 and Node 2)
 * - Aggregator Actors that combine results from parallel paths
 * - A Sink Actor that receives the final result (on Node 2)
 */
public class ClusterWorkflowExample {

    // Shared instance of the completion latch for all actors
    private static CountDownLatch completionLatch;

    // Shared list to store results
    private static final List<String> results = new ArrayList<>();

    // Store system references for direct access
    private static ClusterActorSystem node1System;
    private static ClusterActorSystem node2System;

    // Performance metrics
    private static final Map<String, Long> workflowStartTimes = new ConcurrentHashMap<>();
    private static final Map<String, Long> workflowEndTimes = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Long>> actorProcessingTimes = new ConcurrentHashMap<>();

    // Message counters
    private static final AtomicInteger totalMessagesProcessed = new AtomicInteger(0);
    private static final Map<String, AtomicInteger> actorMessageCounts = new ConcurrentHashMap<>();

    /**
     * Protocol for workflow messages
     */
    public sealed interface WorkflowMessage {
        /**
         * Initial message to start workflow
         */
        record StartWorkflow(String workflowId, String data) implements WorkflowMessage {
        }

        /**
         * Message containing data to be processed
         */
        record ProcessData(String workflowId, String data, int step, String path) implements WorkflowMessage {
        }

        /**
         * Message containing results to be aggregated
         */
        record AggregateResults(String workflowId, List<String> results) implements WorkflowMessage {
        }

        /**
         * Message containing the final result
         */
        record WorkflowResult(String workflowId, String result) implements WorkflowMessage {
        }
    }

    /**
     * Source Actor that initiates the workflow and distributes work to multiple paths
     */
    public static class SourceActor extends Actor<WorkflowMessage> {
        private final Map<String, Pid> pathProcessors = new HashMap<>();
        private String nodeId;

        public SourceActor(ActorSystem system, String actorId) {
            super(system, actorId);
            withSupervisionStrategy(SupervisionStrategy.RESTART);
            if (system instanceof ClusterActorSystem) {
                this.nodeId = ((ClusterActorSystem) system).getSystemId();
            } else {
                this.nodeId = "local";
            }
        }

        public void addPathProcessor(String path, Pid processor) {
            pathProcessors.put(path, processor);
        }

        @Override
        protected void receive(WorkflowMessage message) {
            switch (message) {
                case WorkflowMessage.StartWorkflow startMsg -> {
                    System.out.println("[" + nodeId + "] Source: Starting workflow " + startMsg.workflowId());

                    // Record start time
                    workflowStartTimes.put(startMsg.workflowId(), System.currentTimeMillis());

                    // Distribute work to all paths
                    for (Map.Entry<String, Pid> entry : pathProcessors.entrySet()) {
                        String path = entry.getKey();
                        Pid processor = entry.getValue();

                        System.out.println("[" + nodeId + "] Source: Sending data to path " + path);
                        processor.tell(new WorkflowMessage.ProcessData(
                                startMsg.workflowId(),
                                startMsg.data() + "-path" + path,
                                1,
                                path
                        ));
                    }

                    incrementMessageCount(getActorId());
                }
                default -> System.out.println("[" + nodeId + "] Source: Unexpected message type: " + message);
            }
        }

        @Override
        protected void preStart() {
            System.out.println("[" + nodeId + "] Source actor started: " + getActorId());
        }
    }

    /**
     * Processor Actor that performs a step in the workflow
     */
    public static class ProcessorActor extends Actor<WorkflowMessage> {
        private final int processorNumber;
        private Pid nextPid; // Next processor or aggregator
        private final String nodeId;
        private final int processingTime; // Simulated processing time in ms

        public ProcessorActor(ActorSystem system, String actorId) {
            super(system, actorId);
            // Extract processor number from the actor ID
            String[] parts = actorId.split("-");
            this.processorNumber = Integer.parseInt(parts[1]);

            // Simulate different processing times for different processors
            this.processingTime = 100 + (processorNumber * 50);

            if (system instanceof ClusterActorSystem) {
                this.nodeId = ((ClusterActorSystem) system).getSystemId();
            } else {
                this.nodeId = "local";
            }
            withSupervisionStrategy(SupervisionStrategy.RESUME);
        }

        public void setNextProcessor(Pid nextPid) {
            this.nextPid = nextPid;
        }

        @Override
        protected void receive(WorkflowMessage message) {
            switch (message) {
                case WorkflowMessage.ProcessData processMsg -> {
                    // Check if this is the right step for this processor
                    if (processMsg.step() == processorNumber) {
                        long startTime = System.currentTimeMillis();

                        System.out.println("[" + nodeId + "] Processor " + processorNumber +
                                " (Path " + processMsg.path() + "): Processing workflow " +
                                processMsg.workflowId());

                        // Simulate processing by transforming the data and sleeping
                        String processedData = processData(processMsg.data());
                        simulateProcessing();

                        // Record processing time
                        long endTime = System.currentTimeMillis();
                        recordProcessingTime(processMsg.workflowId(), getActorId(), endTime - startTime);

                        // Forward to next processor or aggregator
                        if (nextPid != null) {
                            nextPid.tell(new WorkflowMessage.ProcessData(
                                    processMsg.workflowId(),
                                    processedData,
                                    processMsg.step() + 1,
                                    processMsg.path()
                            ));
                        } else {
                            System.out.println("[" + nodeId + "] Processor " + processorNumber +
                                    " (Path " + processMsg.path() + "): ERROR - nextPid is null, cannot forward message");
                        }

                        incrementMessageCount(getActorId());
                    } else {
                        System.out.println("[" + nodeId + "] Processor " + processorNumber +
                                " (Path " + processMsg.path() + "): Skipping message for step " + processMsg.step());
                    }
                }
                default -> System.out.println("[" + nodeId + "] Processor " + processorNumber +
                        ": Unexpected message type: " + message);
            }
        }

        private String processData(String data) {
            // Simulate processing by appending processor number and node ID
            return data + "-P" + processorNumber + "[" + nodeId + "]";
        }

        private void simulateProcessing() {
            try {
                // Simulate processing time with some variation
                Thread.sleep(processingTime + (int) (Math.random() * 50));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        protected void preStart() {
            System.out.println("[" + nodeId + "] Processor " + processorNumber + " started: " + getActorId());
        }

        @Override
        protected boolean onError(WorkflowMessage message, Throwable exception) {
            System.out.println("[" + nodeId + "] Processor " + processorNumber + " error: " + exception.getMessage());
            // Return true to reprocess the message after recovery
            return true;
        }
    }

    /**
     * Aggregator Actor that combines results from multiple paths
     */
    public static class AggregatorActor extends Actor<WorkflowMessage> {
        private final String nodeId;
        private Pid sinkPid;
        private final Map<String, List<String>> pendingResults = new ConcurrentHashMap<>();
        private int expectedPathCount;

        public AggregatorActor(ActorSystem system, String actorId) {
            super(system, actorId);
            // Default to 3 paths, will be set properly later
            this.expectedPathCount = 3;
            if (system instanceof ClusterActorSystem) {
                this.nodeId = ((ClusterActorSystem) system).getSystemId();
            } else {
                this.nodeId = "local";
            }
            withSupervisionStrategy(SupervisionStrategy.RESTART);
        }

        public void setSinkPid(Pid sinkPid) {
            this.sinkPid = sinkPid;
        }

        public void setExpectedPathCount(int expectedPathCount) {
            this.expectedPathCount = expectedPathCount;
        }

        @Override
        protected void receive(WorkflowMessage message) {
            switch (message) {
                case WorkflowMessage.ProcessData processMsg -> {
                    long startTime = System.currentTimeMillis();

                    System.out.println("[" + nodeId + "] Aggregator: Received result from path " +
                            processMsg.path() + " for workflow " + processMsg.workflowId());

                    // Store the result for this path
                    pendingResults.computeIfAbsent(processMsg.workflowId(), k -> new ArrayList<>())
                            .add(processMsg.data());

                    // Check if we have results from all paths
                    List<String> results = pendingResults.get(processMsg.workflowId());
                    if (results.size() >= expectedPathCount) {
                        System.out.println("[" + nodeId + "] Aggregator: All paths completed for workflow " +
                                processMsg.workflowId() + ", aggregating results");

                        // Combine all results
                        String aggregatedResult = aggregateResults(results);

                        // Send to sink
                        if (sinkPid != null) {
                            sinkPid.tell(new WorkflowMessage.ProcessData(
                                    processMsg.workflowId(),
                                    aggregatedResult,
                                    -1, // Special marker for aggregated result
                                    "aggregated"
                            ));
                        }

                        // Clean up
                        pendingResults.remove(processMsg.workflowId());
                    }

                    // Record processing time
                    long endTime = System.currentTimeMillis();
                    recordProcessingTime(processMsg.workflowId(), getActorId(), endTime - startTime);

                    incrementMessageCount(getActorId());
                }
                default -> System.out.println("[" + nodeId + "] Aggregator: Unexpected message type: " + message);
            }
        }

        private String aggregateResults(List<String> results) {
            // Simulate aggregation by joining all results
            return String.join(" + ", results);
        }

        @Override
        protected void preStart() {
            System.out.println("[" + nodeId + "] Aggregator actor started: " + getActorId());
        }
    }

    /**
     * Sink Actor that receives the final result
     */
    public static class SinkActor extends Actor<WorkflowMessage> {
        private final String nodeId;

        public SinkActor(ActorSystem system, String actorId) {
            super(system, actorId);
            if (system instanceof ClusterActorSystem) {
                this.nodeId = ((ClusterActorSystem) system).getSystemId();
            } else {
                this.nodeId = "local";
            }
            withSupervisionStrategy(SupervisionStrategy.ESCALATE);
        }

        @Override
        protected void receive(WorkflowMessage message) {
            switch (message) {
                case WorkflowMessage.ProcessData processMsg -> {
                    long startTime = System.currentTimeMillis();

                    System.out.println("[" + nodeId + "] Sink: Received final result for workflow " +
                            processMsg.workflowId() + ": " + processMsg.data());

                    // Store the result
                    synchronized (results) {
                        results.add(processMsg.data());
                    }

                    // Record end time
                    workflowEndTimes.put(processMsg.workflowId(), System.currentTimeMillis());

                    // Record processing time
                    recordProcessingTime(processMsg.workflowId(), getActorId(), System.currentTimeMillis() - startTime);

                    incrementMessageCount(getActorId());

                    // Signal workflow completion
                    completionLatch.countDown();
                }
                default -> System.out.println("[" + nodeId + "] Sink: Unexpected message type: " + message);
            }
        }

        @Override
        protected void preStart() {
            System.out.println("[" + nodeId + "] Sink actor started: " + getActorId());
        }
    }

    /**
     * Creates a complex workflow with multiple processing paths distributed across two nodes
     */
    private static void setupComplexWorkflow(ClusterActorSystem system1, ClusterActorSystem system2, int pathCount, int processorsPerPath) {
        System.out.println("Setting up complex workflow with " + pathCount + " paths, each with " + processorsPerPath + " processors");

        // Create sink actor on node2
        Pid sinkPid = system2.register(SinkActor.class, "sink");
        System.out.println("Created sink actor on node2: " + sinkPid);

        // Create aggregator on node1
        Pid aggregatorPid = system1.register(AggregatorActor.class, "aggregator");
        System.out.println("Created aggregator actor on node1: " + aggregatorPid);

        // Configure the aggregator
        AggregatorActor aggregator = (AggregatorActor) system1.getActor(aggregatorPid);
        if (aggregator != null) {
            aggregator.setSinkPid(sinkPid);
            aggregator.setExpectedPathCount(pathCount);
            System.out.println("Connected aggregator to sink and configured for " + pathCount + " paths");
        }

        // Create source actor on node1
        Pid sourcePid = system1.register(SourceActor.class, "source");
        System.out.println("Created source actor on node1: " + sourcePid);
        SourceActor source = (SourceActor) system1.getActor(sourcePid);

        // Create processors for each path
        for (int path = 1; path <= pathCount; path++) {
            String pathId = String.valueOf(path);

            // Create processors for this path
            Pid[] pathProcessors = new Pid[processorsPerPath];
            for (int i = processorsPerPath; i >= 1; i--) {
                String processorId = "processor-" + i + "-path" + pathId;

                // Distribute processors: odd-numbered on node1, even-numbered on node2
                if (i % 2 == 1) {
                    pathProcessors[i - 1] = system1.register(ProcessorActor.class, processorId);
                    System.out.println("Created processor " + i + " for path " + pathId + " on node1: " + pathProcessors[i - 1]);
                } else {
                    pathProcessors[i - 1] = system2.register(ProcessorActor.class, processorId);
                    System.out.println("Created processor " + i + " for path " + pathId + " on node2: " + pathProcessors[i - 1]);
                }
            }

            // Connect processors in sequence
            for (int i = 0; i < processorsPerPath - 1; i++) {
                // Get the actor reference from the appropriate system
                ClusterActorSystem system = (i % 2 == 0) ? system1 : system2;
                ProcessorActor processor = (ProcessorActor) system.getActor(pathProcessors[i]);
                if (processor != null) {
                    processor.setNextProcessor(pathProcessors[i + 1]);
                    System.out.println("Connected processor " + (i + 1) + " to processor " + (i + 2) + " in path " + pathId);
                }
            }

            // Connect the last processor to the aggregator
            ClusterActorSystem lastSystem = ((processorsPerPath - 1) % 2 == 0) ? system1 : system2;
            ProcessorActor lastProcessor = (ProcessorActor) lastSystem.getActor(pathProcessors[processorsPerPath - 1]);
            if (lastProcessor != null) {
                lastProcessor.setNextProcessor(aggregatorPid);
                System.out.println("Connected last processor in path " + pathId + " to aggregator");
            }

            // Connect source to the first processor of this path
            if (source != null) {
                source.addPathProcessor(pathId, pathProcessors[0]);
                System.out.println("Connected source to first processor in path " + pathId);
            }
        }

        System.out.println("Complex workflow setup complete");
    }

    /**
     * Start a workflow with the given data
     */
    private static void startWorkflow(ClusterActorSystem system, String data) {
        String workflowId = UUID.randomUUID().toString().substring(0, 8);
        Pid sourcePid = new Pid("source", system);
        sourcePid.tell(new WorkflowMessage.StartWorkflow(workflowId, data));
    }

    /**
     * Record processing time for an actor
     */
    private static void recordProcessingTime(String workflowId, String actorId, long time) {
        actorProcessingTimes.computeIfAbsent(workflowId, k -> new ConcurrentHashMap<>())
                .put(actorId, time);
    }

    /**
     * Increment message count for an actor
     */
    private static void incrementMessageCount(String actorId) {
        totalMessagesProcessed.incrementAndGet();
        actorMessageCounts.computeIfAbsent(actorId, k -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * Print performance metrics
     */
    private static void printPerformanceMetrics() {
        System.out.println("\n===== PERFORMANCE METRICS =====");

        // Print workflow completion times
        System.out.println("\nWorkflow Completion Times:");
        for (Map.Entry<String, Long> entry : workflowEndTimes.entrySet()) {
            String workflowId = entry.getKey();
            long startTime = workflowStartTimes.getOrDefault(workflowId, 0L);
            long endTime = entry.getValue();
            long duration = endTime - startTime;

            System.out.println("Workflow " + workflowId + ": " + duration + " ms");
        }

        // Print actor processing times
        System.out.println("\nActor Processing Times (Top 10):");
        actorProcessingTimes.values().stream()
                .flatMap(map -> map.entrySet().stream())
                .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
                .limit(10)
                .forEach(entry -> System.out.println(entry.getKey() + ": " + entry.getValue() + " ms"));

        // Print message counts
        System.out.println("\nTotal Messages Processed: " + totalMessagesProcessed.get());
        System.out.println("\nMessages Per Actor (Top 10):");
        actorMessageCounts.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().get(), e1.getValue().get()))
                .limit(10)
                .forEach(entry -> System.out.println(entry.getKey() + ": " + entry.getValue() + " messages"));
    }

    public static void main(String[] args) {
        // Setup etcd metadata store for both nodes
        MetadataStore metadataStore1 = null;
        MetadataStore metadataStore2 = null;

        try {
            System.out.println("Connecting to etcd at http://localhost:2379");
            metadataStore1 = ClusterFactory.createEtcdMetadataStore("http://localhost:2379");
            metadataStore2 = ClusterFactory.createEtcdMetadataStore("http://localhost:2379");

            // Setup messaging systems for both nodes
            System.out.println("Setting up messaging systems");
            MessagingSystem messagingSystem1 = ClusterFactory.createDirectMessagingSystem("node1", 8080);
            MessagingSystem messagingSystem2 = ClusterFactory.createDirectMessagingSystem("node2", 8081);

            // Add node information to each messaging system
            ((systems.cajun.runtime.cluster.DirectMessagingSystem)messagingSystem1).addNode("node2", "localhost", 8081);
            ((systems.cajun.runtime.cluster.DirectMessagingSystem)messagingSystem2).addNode("node1", "localhost", 8080);

            // Create cluster actor systems for both nodes
            System.out.println("Creating cluster actor systems");
            node1System = ClusterFactory.createClusterActorSystem("node1", metadataStore1, messagingSystem1);
            node2System = ClusterFactory.createClusterActorSystem("node2", metadataStore2, messagingSystem2);

            // Set delivery guarantee for reliable messaging
            node1System.withDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE);
            node2System.withDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE);

            try {
                // Start both actor systems
                System.out.println("Starting cluster actor systems...");
                CompletableFuture<Void> startFuture1 = node1System.start();
                CompletableFuture<Void> startFuture2 = node2System.start();

                // Wait for both systems to start
                CompletableFuture.allOf(startFuture1, startFuture2).get(30, TimeUnit.SECONDS);
                System.out.println("Cluster actor systems started");

                // Wait a bit for the systems to stabilize
                System.out.println("Waiting for systems to stabilize...");
                Thread.sleep(3000);

                // Number of workflow executions to run
                int workflowCount = 5;
                completionLatch = new CountDownLatch(workflowCount);

                // Create a complex workflow with 3 paths, each with 4 processors
                setupComplexWorkflow(node1System, node2System, 3, 4);

                // Start multiple workflow instances
                System.out.println("Starting " + workflowCount + " workflows...");
                for (int i = 1; i <= workflowCount; i++) {
                    startWorkflow(node1System, "Data-" + i);
                    // Add a small delay between workflow starts
                    Thread.sleep(500);
                }

                // Wait for all workflows to complete
                System.out.println("Waiting for workflows to complete...");
                boolean completed = completionLatch.await(60, TimeUnit.SECONDS);
                if (!completed) {
                    System.out.println("Warning: Not all workflows completed within the timeout period");
                }

                // Print results
                System.out.println("\nWorkflow Results:");
                for (String result : results) {
                    System.out.println("- " + result);
                }

                // Print performance metrics
                printPerformanceMetrics();

                // Shutdown both actor systems
                System.out.println("\nStopping cluster actor systems...");
                try {
                    node1System.stop().get(10, TimeUnit.SECONDS);
                    System.out.println("Node 1 stopped successfully");
                } catch (Exception e) {
                    System.out.println("Error stopping node 1: " + e.getMessage());
                }

                try {
                    node2System.stop().get(10, TimeUnit.SECONDS);
                    System.out.println("Node 2 stopped successfully");
                } catch (Exception e) {
                    System.out.println("Error stopping node 2: " + e.getMessage());
                }

                System.out.println("Cluster actor systems stopped");

            } catch (Exception e) {
                System.err.println("Workflow execution error: " + e.getMessage());
                e.printStackTrace();
            }
        } catch (Exception e) {
            System.err.println("Setup error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
