package examples;


import com.cajunsystems.*;
import com.cajunsystems.handler.Handler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Example demonstrating a workflow execution system using the ActorSystem.
 * The workflow consists of:
 * - A Source Actor that initiates the workflow
 * - Multiple Processor Actors that perform sequential operations
 * - A Sink Actor that receives the final result
 */
public class WorkflowExample {

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
    public static class SourceActor implements Handler<WorkflowMessage> {
        private Pid firstProcessor;
        private final String actorId;
        
        public SourceActor(String actorId) {
            this.actorId = actorId;
        }
        
        public void setFirstProcessor(Pid firstProcessor) {
            this.firstProcessor = firstProcessor;
        }
        
        @Override
        public void receive(WorkflowMessage message, ActorContext context) {
            if (message instanceof WorkflowMessage.StartWorkflow startMsg) {
                System.out.println("Source: Starting workflow " + startMsg.workflowId());
                // Forward to first processor
                firstProcessor.tell(new WorkflowMessage.ProcessData(
                    startMsg.workflowId(), 
                    startMsg.data(),
                    1
                ));
            } else {
                System.out.println("Source: Unexpected message type: " + message);
            }
        }
        
        @Override
        public void preStart(ActorContext context) {
            System.out.println("Source actor started: " + actorId);
        }
    }
    
    /**
     * Processor Actor that performs a step in the workflow
     */
    public static class ProcessorActor implements Handler<WorkflowMessage> {
        private final int processorNumber;
        private Pid nextPid; // Next processor or sink
        private final String actorId;
        
        public ProcessorActor(String actorId) {
            this.actorId = actorId;
            // Extract processor number from the actor ID
            String[] parts = actorId.split("-");
            this.processorNumber = Integer.parseInt(parts[1]);
        }
        
        public void setNextProcessor(Pid nextPid) {
            this.nextPid = nextPid;
        }
        
        @Override
        public void receive(WorkflowMessage message, ActorContext context) {
            if (message instanceof WorkflowMessage.ProcessData processMsg) {
                // Check if this is the right step for this processor
                if (processMsg.step() == processorNumber) {
                    System.out.println(STR."Processor \{processorNumber}: Processing workflow \{processMsg.workflowId()}");
                    
                    // Process the data (in a real application, this would do actual work)
                    String processedData = processData(processMsg.data());
                    
                    // Forward to next processor or sink
                    nextPid.tell(new WorkflowMessage.ProcessData(
                        processMsg.workflowId(),
                        processedData,
                        processMsg.step() + 1
                    ));
                } else {
                    System.out.println(STR."Processor \{processorNumber}: Skipping message for step \{processMsg.step()}");
                }
            } else {
                System.out.println("Processor " + processorNumber + 
                                  ": Unexpected message type: " + message);
            }
        }
        
        private String processData(String data) {
            // Simulate processing by adding a processor-specific suffix
            return data + "-P" + processorNumber;
        }
        
        @Override
        public void preStart(ActorContext context) {
            System.out.println("Processor actor started: " + actorId);
        }
        
        // Error handling method - not required by the Handler interface
        public void handleError(Throwable exception) {
            System.err.println("Error in processor " + processorNumber + ": " + exception.getMessage());
            // In a real application, you might implement more sophisticated error handling
        }
    }
    
    /**
     * Sink Actor that receives the final result
     */
    public static class SinkActor implements Handler<WorkflowMessage> {
        private final String actorId;
        
        public SinkActor(String actorId) {
            this.actorId = actorId;
        }
        
        @Override
        public void receive(WorkflowMessage message, ActorContext context) {
            if (message instanceof WorkflowMessage.ProcessData processMsg) {
                System.out.println("Sink: Received final result for workflow " + 
                                   processMsg.workflowId() + ": " + processMsg.data());
                
                // Store result and signal completion
                results.add(processMsg.data());
                completionLatch.countDown();
            } else {
                System.out.println("Sink: Unexpected message type: " + message);
            }
        }
        
        @Override
        public void preStart(ActorContext context) {
            System.out.println("Sink actor started: " + actorId);
        }
    }
    
    /**
     * Creates a workflow with the specified number of processors
     */
    private static void setupWorkflow(ActorSystem system, int processorCount) {
        // Create sink actor using the new interface-based approach
        SinkActor sink = new SinkActor("sink");
        Pid sinkPid = system.actorOf(sink)
            .withSupervisionStrategy(SupervisionStrategy.ESCALATE)
            .withId("sink")
            .spawn();
        
        System.out.println("Sink actor started: sink");
        

        
        // Create processors in reverse order (last to first)
        Pid[] processorPids = new Pid[processorCount];
        Pid nextPid = sinkPid;
        
        for (int i = processorCount; i >= 1; i--) {
            final int processorNumber = i;
            final Pid nextProcessor = nextPid;
            
            String processorId = "processor-" + i;
            
            processorPids[i-1] = system.actorOf(new Handler<WorkflowMessage>() {
                @Override
                public void receive(WorkflowMessage message, ActorContext context) {
                    if (message instanceof WorkflowMessage.ProcessData processMsg) {
                        // Check if this is the right step for this processor
                        if (processMsg.step() == processorNumber) {
                            System.out.println("Processor " + processorNumber + 
                                              ": Processing workflow " + processMsg.workflowId());

                            // Process the data (in a real application, this would do actual work)
                            String processedData = processMsg.data() + "-P" + processorNumber;

                            // Forward to next processor or sink
                            nextProcessor.tell(new WorkflowMessage.ProcessData(
                                processMsg.workflowId(),
                                processedData,
                                processMsg.step() + 1
                            ));
                        } else {
                            System.out.println("Processor " + processorNumber + 
                                              ": Skipping message for step " + processMsg.step());
                        }
                    } else {
                        System.out.println("Processor " + processorNumber + 
                                          ": Unexpected message type: " + message);
                    }
                }
            })
            .withSupervisionStrategy(SupervisionStrategy.RESUME)
            .withId(processorId)
            .spawn();
            
            System.out.println("Processor actor started: " + processorId);
            
            // Update nextPid for the next iteration
            nextPid = processorPids[i-1];
        }
        
        // Create source actor using the new interface-based approach
        final Pid firstProcessorPid = processorPids[0];
        system.actorOf(new Handler<WorkflowMessage>() {
            @Override
            public void receive(WorkflowMessage message, ActorContext context) {
                if (message instanceof WorkflowMessage.StartWorkflow startMsg) {
                    System.out.println("Source: Starting workflow " + startMsg.workflowId());
                    
                    // Forward to first processor
                    firstProcessorPid.tell(new WorkflowMessage.ProcessData(
                        startMsg.workflowId(),
                        startMsg.data(),
                        1
                    ));
                } else {
                    System.out.println("Source: Unexpected message type: " + message);
                }
            }
        })
        .withSupervisionStrategy(SupervisionStrategy.RESTART)
        .withId("source")
        .spawn();
        
        System.out.println("Source actor started: source");
        
        // No need to connect actors manually as we've already set up the connections
        // when creating the actors with the new interface-based approach
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
