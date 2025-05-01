package examples;

import systems.cajun.Actor;
import systems.cajun.ActorSystem;
import systems.cajun.Pid;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Improved version of the WorkflowExample demonstrating the more succinct actor system.
 * The workflow consists of:
 * - A Source Actor that initiates the workflow
 * - Multiple Processor Actors that perform sequential operations
 * - A Sink Actor that receives the final result
 */
public class WorkflowExampleImproved {

    // Shared instance of the completion latch for all actors
    private static CountDownLatch completionLatch;
    
    // Shared list to store results
    private static final List<String> results = new ArrayList<>();

    /**
     * Protocol for workflow messages
     */
    public sealed interface WorkflowMessage {
        /**
         * Initial message to start workflow
         */
        record StartWorkflow(String workflowId, String data) implements WorkflowMessage {}
        
        /**
         * Message containing data to be processed
         */
        record ProcessData(String workflowId, String data, int step) implements WorkflowMessage {}
        
        /**
         * Message containing the final result
         */
        record WorkflowResult(String workflowId, String result) implements WorkflowMessage {}
    }

    /**
     * Source Actor that initiates the workflow
     */
    public static class SourceActor extends Actor<WorkflowMessage> {
        public SourceActor(ActorSystem system, String actorId) {
            super(system, actorId);
            withSupervisionStrategy(SupervisionStrategy.RESTART);
        }
        
        @Override
        protected void receive(WorkflowMessage message) {
            switch (message) {
                case WorkflowMessage.StartWorkflow startMsg -> {
                    System.out.println("Source: Starting workflow " + startMsg.workflowId());
                    // Forward to next actor in the chain
                    forward(new WorkflowMessage.ProcessData(
                        startMsg.workflowId(), 
                        startMsg.data(),
                        1
                    ));
                }
                default -> System.out.println("Source: Unexpected message type: " + message);
            }
        }
        
        @Override
        protected void preStart() {
            System.out.println("Source actor started: " + getActorId());
        }
    }
    
    /**
     * Processor Actor that performs a step in the workflow
     */
    public static class ProcessorActor extends Actor<WorkflowMessage> {
        private final int processorNumber;
        
        public ProcessorActor(ActorSystem system, String actorId) {
            super(system, actorId);
            // Extract processor number from the actor ID
            String[] parts = actorId.split("-");
            this.processorNumber = Integer.parseInt(parts[1]);
            withSupervisionStrategy(SupervisionStrategy.RESUME);
        }
        
        @Override
        protected void receive(WorkflowMessage message) {
            switch (message) {
                case WorkflowMessage.ProcessData processMsg -> {
                    // Check if this is the right step for this processor
                    if (processMsg.step() == processorNumber) {
                        System.out.println("Processor " + processorNumber + 
                                          ": Processing workflow " + processMsg.workflowId());
                        
                        // Simulate processing by transforming the data
                        String processedData = processData(processMsg.data());
                        
                        // Forward to next actor in the chain
                        forward(new WorkflowMessage.ProcessData(
                            processMsg.workflowId(),
                            processedData,
                            processMsg.step() + 1
                        ));
                    } else {
                        System.out.println("Processor " + processorNumber + 
                                          ": Skipping message for step " + processMsg.step());
                    }
                }
                default -> System.out.println("Processor " + processorNumber + 
                                             ": Unexpected message type: " + message);
            }
        }
        
        private String processData(String data) {
            // Simulate processing by appending processor number
            return data + "-P" + processorNumber;
        }
        
        @Override
        protected void preStart() {
            System.out.println("Processor " + processorNumber + " started: " + getActorId());
        }
        
        @Override
        protected boolean onError(WorkflowMessage message, Throwable exception) {
            System.out.println("Processor " + processorNumber + " error: " + exception.getMessage());
            // Return true to reprocess the message after recovery
            return true;
        }
    }
    
    /**
     * Sink Actor that receives the final result
     */
    public static class SinkActor extends Actor<WorkflowMessage> {
        
        public SinkActor(ActorSystem system, String actorId) {
            super(system, actorId);
            withSupervisionStrategy(SupervisionStrategy.ESCALATE);
        }
        
        @Override
        protected void receive(WorkflowMessage message) {
            switch (message) {
                case WorkflowMessage.ProcessData processMsg -> {
                    System.out.println("Sink: Received final result for workflow " + 
                                      processMsg.workflowId() + ": " + processMsg.data());
                    
                    // Store the result
                    synchronized (results) {
                        results.add(processMsg.data());
                    }
                    
                    // Signal workflow completion
                    completionLatch.countDown();
                }
                default -> System.out.println("Sink: Unexpected message type: " + message);
            }
        }
        
        @Override
        protected void preStart() {
            System.out.println("Sink actor started: " + getActorId());
        }
    }
    
    /**
     * Creates a workflow with the specified number of processors using the improved API
     */
    private static void setupWorkflow(ActorSystem system, int processorCount) {
        // Create sink actor
        Pid sinkPid = system.register(SinkActor.class, "sink");
        
        // Create processor chain
        Pid firstProcessorPid = system.createActorChain(ProcessorActor.class, "processor", processorCount);
        
        // Create source actor and connect to the first processor
        Pid sourcePid = system.register(SourceActor.class, "source");
        Actor<?> sourceActor = system.getActor(sourcePid);
        sourceActor.withNext(firstProcessorPid);
        
        // Connect the last processor to the sink
        Actor<?> lastProcessor = system.getActor(new Pid("processor-" + processorCount, system));
        lastProcessor.withNext(sinkPid);
    }
    
    /**
     * Start a workflow with the given data
     */
    private static void startWorkflow(ActorSystem system, String data) {
        String workflowId = UUID.randomUUID().toString().substring(0, 8);
        Pid sourcePid = new Pid("source", system);
        sourcePid.tell(new WorkflowMessage.StartWorkflow(workflowId, data));
    }
    
    public static void main(String[] args) {
        ActorSystem actorSystem = new ActorSystem();
        
        // Number of workflow executions to run
        int workflowCount = 2;
        completionLatch = new CountDownLatch(workflowCount);
        
        // Create a workflow with 3 processors
        setupWorkflow(actorSystem, 3);
        
        // Start two workflow instances
        startWorkflow(actorSystem, "Data-A");
        startWorkflow(actorSystem, "Data-B");
        
        try {
            // Wait for both workflows to complete
            boolean completed = completionLatch.await(10, TimeUnit.SECONDS);
            if (!completed) {
                System.out.println("Warning: Workflow did not complete within the timeout period");
            }
            
            System.out.println("\nWorkflow Results:");
            for (String result : results) {
                System.out.println("- " + result);
            }
            
            // Shutdown all actors
            for (Actor<?> actor : actorSystem.getActors().values()) {
                actor.stop();
            }
            
        } catch (InterruptedException e) {
            System.err.println("Workflow execution interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}
