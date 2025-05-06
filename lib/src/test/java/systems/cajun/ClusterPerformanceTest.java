package systems.cajun;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.cajun.cluster.*;
import systems.cajun.runtime.cluster.ClusterFactory;
import systems.cajun.runtime.cluster.DirectMessagingSystem;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Performance tests for the Cluster Actor system.
 * These tests measure throughput when using multiple nodes in a cluster.
 * 
 * These tests are tagged with both "performance" and "requires-etcd" as they:
 * 1. Measure performance metrics (throughput)
 * 2. Require etcd to be running (for cluster coordination)
 * 
 * To run these tests specifically, use:
 * ./gradlew performanceTest --tests "systems.cajun.ClusterPerformanceTest"
 */
@Tag("performance")
@Tag("requires-etcd")
public class ClusterPerformanceTest {

    private static final Logger logger = LoggerFactory.getLogger(ClusterPerformanceTest.class);

    // Store system references for direct access
    private ClusterActorSystem node1System;
    private ClusterActorSystem node2System;

    // Performance metrics
    private final Map<String, Long> workflowStartTimes = new ConcurrentHashMap<>();
    private final Map<String, Long> workflowEndTimes = new ConcurrentHashMap<>();
    private final AtomicInteger totalMessagesProcessed = new AtomicInteger(0);
    private final AtomicInteger completedWorkflows = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        // Setup will be done in the test methods as they require specific configurations
    }

    @AfterEach
    void tearDown() {
        // Clean up resources
        if (node1System != null) {
            try {
                node1System.stop().get(10, TimeUnit.SECONDS);
                logger.info("Node 1 stopped successfully");
            } catch (Exception e) {
                logger.error("Error stopping node 1: {}", e.getMessage());
            }
        }

        if (node2System != null) {
            try {
                node2System.stop().get(10, TimeUnit.SECONDS);
                logger.info("Node 2 stopped successfully");
            } catch (Exception e) {
                logger.error("Error stopping node 2: {}", e.getMessage());
            }
        }
    }

    /**
     * Tests the throughput of a workflow distributed across multiple nodes.
     * This test creates a workflow similar to ClusterWorkflowExample but focused on
     * measuring throughput with multiple parallel paths.
     */
    @Test
    void testClusterWorkflowThroughput() throws Exception {
        logger.info("Starting cluster workflow throughput test");

        // Setup etcd metadata store for both nodes
        MetadataStore metadataStore1 = null;
        MetadataStore metadataStore2 = null;

        try {
            logger.info("Connecting to etcd at http://localhost:2379");
            metadataStore1 = ClusterFactory.createEtcdMetadataStore("http://localhost:2379");
            metadataStore2 = ClusterFactory.createEtcdMetadataStore("http://localhost:2379");

            // Setup messaging systems for both nodes
            logger.info("Setting up messaging systems");
            MessagingSystem messagingSystem1 = ClusterFactory.createDirectMessagingSystem("node1", 8080);
            MessagingSystem messagingSystem2 = ClusterFactory.createDirectMessagingSystem("node2", 8081);

            // Add node information to each messaging system
            ((DirectMessagingSystem)messagingSystem1).addNode("node2", "localhost", 8081);
            ((DirectMessagingSystem)messagingSystem2).addNode("node1", "localhost", 8080);

            // Create cluster actor systems for both nodes
            logger.info("Creating cluster actor systems");
            node1System = new ClusterActorSystem("node1", metadataStore1, messagingSystem1);
            node2System = new ClusterActorSystem("node2", metadataStore2, messagingSystem2);

            // Set delivery guarantee for reliable messaging
            node1System.withDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE);
            node2System.withDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE);

            // Start both actor systems
            logger.info("Starting cluster actor systems...");
            CompletableFuture<Void> startFuture1 = node1System.start();
            CompletableFuture<Void> startFuture2 = node2System.start();

            // Wait for both systems to start
            CompletableFuture.allOf(startFuture1, startFuture2).get(30, TimeUnit.SECONDS);
            logger.info("Cluster actor systems started");

            // Wait for systems to stabilize
            logger.info("Waiting for systems to stabilize...");
            Thread.sleep(3000);

            // Configuration for the workflow
            final int WORKFLOW_COUNT = 100;
            final int PATH_COUNT = 5;
            final int PROCESSORS_PER_PATH = 3;

            // Create a completion latch for the test
            CountDownLatch completionLatch = new CountDownLatch(WORKFLOW_COUNT);

            // Setup the workflow
            setupWorkflow(node1System, node2System, PATH_COUNT, PROCESSORS_PER_PATH, completionLatch);

            // Warm up with a few workflows
            logger.info("Warming up the workflow with 10 executions");
            for (int i = 0; i < 10; i++) {
                startWorkflow(node1System, "warmup-" + i);
                Thread.sleep(100); // Small delay between warmup workflows
            }

            // Wait a bit for warmup to complete
            Thread.sleep(5000);

            // Reset metrics for the actual test
            totalMessagesProcessed.set(0);
            completedWorkflows.set(0);
            workflowStartTimes.clear();
            workflowEndTimes.clear();

            // Create a new completion latch for the actual test
            completionLatch = new CountDownLatch(WORKFLOW_COUNT);
            Pid sinkPid = new Pid("workflow-sink", node2System);
            WorkflowSinkActor sink = (WorkflowSinkActor) node2System.getActor(sinkPid);
            sink.setCompletionLatch(completionLatch);

            // Measure throughput
            logger.info("Starting workflow throughput test with {} workflows", WORKFLOW_COUNT);
            long startTime = System.nanoTime();

            // Start workflows
            for (int i = 0; i < WORKFLOW_COUNT; i++) {
                String workflowId = "workflow-" + i;
                workflowStartTimes.put(workflowId, System.nanoTime());
                startWorkflow(node1System, workflowId);

                // Small delay to avoid overwhelming the system
                if (i % 10 == 0) {
                    Thread.sleep(50);
                }
            }

            // Wait for all workflows to complete
            boolean completed = completionLatch.await(120, TimeUnit.SECONDS);
            long endTime = System.nanoTime();

            if (!completed) {
                logger.warn("Not all workflows were completed within the timeout period");
                logger.warn("Completed workflows: {}/{}", completedWorkflows.get(), WORKFLOW_COUNT);
            }

            // Calculate and log results
            double elapsedSeconds = (endTime - startTime) / 1_000_000_000.0;
            double workflowsPerSecond = completedWorkflows.get() / elapsedSeconds;
            double messagesPerSecond = totalMessagesProcessed.get() / elapsedSeconds;

            // Calculate average workflow completion time
            long totalCompletionTime = 0;
            int completionCount = 0;

            for (Map.Entry<String, Long> entry : workflowEndTimes.entrySet()) {
                String workflowId = entry.getKey();
                Long startNanoTime = workflowStartTimes.get(workflowId);

                if (startNanoTime != null) {
                    totalCompletionTime += (entry.getValue() - startNanoTime);
                    completionCount++;
                }
            }

            double avgCompletionTimeMs = completionCount > 0 ? 
                    (totalCompletionTime / completionCount) / 1_000_000.0 : 0;

            // Log results
            logger.info("Cluster Workflow Throughput Test Results:");
            logger.info("Workflows Started: {}", WORKFLOW_COUNT);
            logger.info("Workflows Completed: {}", completedWorkflows.get());
            logger.info("Total Messages Processed: {}", totalMessagesProcessed.get());
            logger.info("Elapsed Time: {} seconds", elapsedSeconds);
            logger.info("Throughput: {} workflows/second", workflowsPerSecond);
            logger.info("Message Throughput: {} messages/second", messagesPerSecond);
            logger.info("Average Workflow Completion Time: {} ms", avgCompletionTimeMs);

        } catch (Exception e) {
            logger.error("Test failed with exception", e);
            throw e;
        }
    }

    /**
     * Sets up a workflow with multiple paths distributed across two nodes
     */
    private void setupWorkflow(ClusterActorSystem system1, ClusterActorSystem system2, 
                              int pathCount, int processorsPerPath, CountDownLatch latch) {

        logger.info("Setting up workflow with {} paths, each with {} processors", 
                pathCount, processorsPerPath);

        // Create sink actor on node2
        Pid sinkPid = system2.register(WorkflowSinkActor.class, "workflow-sink");
        WorkflowSinkActor sink = (WorkflowSinkActor) system2.getActor(sinkPid);
        sink.setCompletionLatch(latch);
        sink.setTest(this);
        logger.info("Created sink actor on node2: {}", sinkPid);

        // Create aggregator on node1
        Pid aggregatorPid = system1.register(WorkflowAggregatorActor.class, "workflow-aggregator");
        WorkflowAggregatorActor aggregator = (WorkflowAggregatorActor) system1.getActor(aggregatorPid);
        aggregator.setSinkPid(sinkPid);
        aggregator.setExpectedPathCount(pathCount);
        aggregator.setMessageCounter(totalMessagesProcessed);
        logger.info("Created aggregator actor on node1: {}", aggregatorPid);

        // Create source actor on node1
        Pid sourcePid = system1.register(WorkflowSourceActor.class, "workflow-source");
        WorkflowSourceActor source = (WorkflowSourceActor) system1.getActor(sourcePid);
        source.setMessageCounter(totalMessagesProcessed);
        logger.info("Created source actor on node1: {}", sourcePid);

        // Create processors for each path
        for (int path = 1; path <= pathCount; path++) {
            String pathId = String.valueOf(path);

            // Create processors for this path
            Pid[] pathProcessors = new Pid[processorsPerPath];
            for (int i = processorsPerPath; i >= 1; i--) {
                String processorId = "workflow-processor-" + i + "-path" + pathId;

                // Distribute processors: odd-numbered on node1, even-numbered on node2
                if (i % 2 == 1) {
                    pathProcessors[i - 1] = system1.register(WorkflowProcessorActor.class, processorId);
                    WorkflowProcessorActor processor = (WorkflowProcessorActor) system1.getActor(pathProcessors[i - 1]);
                    processor.setMessageCounter(totalMessagesProcessed);
                } else {
                    pathProcessors[i - 1] = system2.register(WorkflowProcessorActor.class, processorId);
                    WorkflowProcessorActor processor = (WorkflowProcessorActor) system2.getActor(pathProcessors[i - 1]);
                    processor.setMessageCounter(totalMessagesProcessed);
                }
            }

            // Connect processors in sequence
            for (int i = 0; i < processorsPerPath - 1; i++) {
                // Get the actor reference from the appropriate system
                ClusterActorSystem system = (i % 2 == 0) ? system1 : system2;
                WorkflowProcessorActor processor = (WorkflowProcessorActor) system.getActor(pathProcessors[i]);
                if (processor != null) {
                    processor.setNextProcessor(pathProcessors[i + 1]);
                }
            }

            // Connect the last processor to the aggregator
            ClusterActorSystem lastSystem = ((processorsPerPath - 1) % 2 == 0) ? system1 : system2;
            WorkflowProcessorActor lastProcessor = (WorkflowProcessorActor) lastSystem.getActor(pathProcessors[processorsPerPath - 1]);
            if (lastProcessor != null) {
                lastProcessor.setNextProcessor(aggregatorPid);
            }

            // Connect source to the first processor of this path
            if (source != null) {
                source.addPathProcessor(pathId, pathProcessors[0]);
            }
        }

        logger.info("Workflow setup complete");
    }

    /**
     * Start a workflow with the given ID
     */
    private void startWorkflow(ClusterActorSystem system, String workflowId) {
        Pid sourcePid = new Pid("workflow-source", system);
        sourcePid.tell(new WorkflowMessage.StartWorkflow(workflowId, "Data"));
    }

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
         * Message containing the final result
         */
        record WorkflowResult(String workflowId, String result) implements WorkflowMessage {
        }
    }

    /**
     * Source Actor that initiates the workflow and distributes work to multiple paths
     */
    public static class WorkflowSourceActor extends Actor<WorkflowMessage> {
        private final Map<String, Pid> pathProcessors = new HashMap<>();
        private AtomicInteger messageCounter;

        public WorkflowSourceActor(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        public void addPathProcessor(String path, Pid processor) {
            pathProcessors.put(path, processor);
        }

        public void setMessageCounter(AtomicInteger counter) {
            this.messageCounter = counter;
        }

        @Override
        protected void receive(WorkflowMessage message) {
            if (messageCounter != null) {
                messageCounter.incrementAndGet();
            }

            switch (message) {
                case WorkflowMessage.StartWorkflow startMsg -> {
                    // Distribute work to all paths
                    for (Map.Entry<String, Pid> entry : pathProcessors.entrySet()) {
                        String path = entry.getKey();
                        Pid processor = entry.getValue();

                        processor.tell(new WorkflowMessage.ProcessData(
                                startMsg.workflowId(),
                                startMsg.data() + "-path" + path,
                                1,
                                path
                        ));
                    }
                }
                default -> { /* Ignore other message types */ }
            }
        }
    }

    /**
     * Processor Actor that performs a step in the workflow
     */
    public static class WorkflowProcessorActor extends Actor<WorkflowMessage> {
        private final int processorNumber;
        private Pid nextPid;
        private AtomicInteger messageCounter;

        public WorkflowProcessorActor(ActorSystem system, String actorId) {
            super(system, actorId);
            // Extract processor number from the actor ID
            String[] parts = actorId.split("-");
            this.processorNumber = Integer.parseInt(parts[2]);
        }

        public void setNextProcessor(Pid nextPid) {
            this.nextPid = nextPid;
        }

        public void setMessageCounter(AtomicInteger counter) {
            this.messageCounter = counter;
        }

        @Override
        protected void receive(WorkflowMessage message) {
            if (messageCounter != null) {
                messageCounter.incrementAndGet();
            }

            switch (message) {
                case WorkflowMessage.ProcessData processMsg -> {
                    // Check if this is the right step for this processor
                    if (processMsg.step() == processorNumber) {
                        // Process data (minimal processing for performance test)
                        String processedData = processMsg.data() + "-P" + processorNumber;

                        // Forward to next processor or aggregator
                        if (nextPid != null) {
                            nextPid.tell(new WorkflowMessage.ProcessData(
                                    processMsg.workflowId(),
                                    processedData,
                                    processMsg.step() + 1,
                                    processMsg.path()
                            ));
                        }
                    }
                }
                default -> { /* Ignore other message types */ }
            }
        }
    }

    /**
     * Aggregator Actor that combines results from multiple paths
     */
    public static class WorkflowAggregatorActor extends Actor<WorkflowMessage> {
        private Pid sinkPid;
        private final Map<String, List<String>> pendingResults = new ConcurrentHashMap<>();
        private int expectedPathCount;
        private AtomicInteger messageCounter;

        public WorkflowAggregatorActor(ActorSystem system, String actorId) {
            super(system, actorId);
            this.expectedPathCount = 3; // Default
        }

        public void setSinkPid(Pid sinkPid) {
            this.sinkPid = sinkPid;
        }

        public void setExpectedPathCount(int expectedPathCount) {
            this.expectedPathCount = expectedPathCount;
        }

        public void setMessageCounter(AtomicInteger counter) {
            this.messageCounter = counter;
        }

        @Override
        protected void receive(WorkflowMessage message) {
            if (messageCounter != null) {
                messageCounter.incrementAndGet();
            }

            switch (message) {
                case WorkflowMessage.ProcessData processMsg -> {
                    // Store the result for this path
                    pendingResults.computeIfAbsent(processMsg.workflowId(), k -> new ArrayList<>())
                            .add(processMsg.data());

                    // Check if we have results from all paths
                    List<String> results = pendingResults.get(processMsg.workflowId());
                    if (results.size() >= expectedPathCount) {
                        // Combine all results (minimal processing for performance test)
                        String aggregatedResult = String.join("+", results);

                        // Send to sink
                        if (sinkPid != null) {
                            sinkPid.tell(new WorkflowMessage.WorkflowResult(
                                    processMsg.workflowId(),
                                    aggregatedResult
                            ));
                        }

                        // Clean up
                        pendingResults.remove(processMsg.workflowId());
                    }
                }
                default -> { /* Ignore other message types */ }
            }
        }
    }

    /**
     * Sink Actor that receives the final result
     */
    public static class WorkflowSinkActor extends Actor<WorkflowMessage> {
        private CountDownLatch completionLatch;
        private ClusterPerformanceTest test;

        public WorkflowSinkActor(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        public void setCompletionLatch(CountDownLatch latch) {
            this.completionLatch = latch;
        }

        public void setTest(ClusterPerformanceTest test) {
            this.test = test;
        }

        @Override
        protected void receive(WorkflowMessage message) {
            switch (message) {
                case WorkflowMessage.WorkflowResult resultMsg -> {
                    // Record end time for workflow completion time calculation
                    if (test != null && !resultMsg.workflowId().startsWith("warmup")) {
                        test.workflowEndTimes.put(resultMsg.workflowId(), System.nanoTime());
                        test.completedWorkflows.incrementAndGet();
                    }

                    if (completionLatch != null) {
                        completionLatch.countDown();
                    }
                }
                default -> { /* Ignore other message types */ }
            }
        }
    }
}
