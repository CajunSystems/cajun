package examples;


import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.SupervisionStrategy;
import com.cajunsystems.handler.Handler;

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
    public static class SourceActor implements Handler<WorkflowMessage> {
        private final String actorId;
        
        public SourceActor(String actorId) {
            this.actorId = actorId;
        }
        
        @Override
        public void receive(WorkflowMessage message, ActorContext context) {
            if (message instanceof WorkflowMessage.StartWorkflow startMsg) {
                System.out.println(STR."Source: Starting workflow \{startMsg.workflowId()}");
                // In the new interface-based approach, we'll use the first processor directly
                // This will be set up in the setupWorkflow method
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
        private final String actorId;
        
        public ProcessorActor(String actorId) {
            this.actorId = actorId;
            // Extract processor number from the actor ID
            String[] parts = actorId.split("-");
            this.processorNumber = Integer.parseInt(parts[1]);
        }
        
        @Override
        public void receive(WorkflowMessage message, ActorContext context) {
            if (message instanceof WorkflowMessage.ProcessData processMsg) {
                // Check if this is the right step for this processor
                if (processMsg.step() == processorNumber) {
                    System.out.println("Processor " + processorNumber + 
                                      ": Processing workflow " + processMsg.workflowId());
                    
                    // Process the data and forward it in a real implementation
                    // In this example, we just log that we would process it
                    System.out.println("Would process data: " + processMsg.data());
                    
                    // In a real implementation, we would forward to the next processor
                    // This would be set up in the setupWorkflow method
                } else {
                    System.out.println("Processor " + processorNumber + 
                                      ": Skipping message for step " + processMsg.step());
                }
            } else {
                System.out.println("Processor " + processorNumber + 
                                  ": Unexpected message type: " + message);
            }
        }
        
        // Method removed as it's no longer used in the new interface-based approach
        
        @Override
        public void preStart(ActorContext context) {
            System.out.println("Processor actor started: " + actorId);
        }
        
        // Error handling method - not required by the Handler interface
        public void handleError(Throwable exception) {
            System.err.println("Error in processor " + processorNumber + ": " + exception.getMessage());
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
     * Creates a workflow with the specified number of processors using the improved API
     */
    private static void setupWorkflow(ActorSystem system, int processorCount) {
        // Create sink actor using the new interface-based approach
        Pid sinkPid = system.actorOf(new Handler<WorkflowMessage>() {
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
                System.out.println("Sink actor started: sink");
            }
        })
        .withSupervisionStrategy(SupervisionStrategy.ESCALATE)
        .withId("sink")
        .spawn();
        
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
                
                @Override
                public void preStart(ActorContext context) {
                    System.out.println("Processor actor started: " + processorId);
                }
            })
            .withSupervisionStrategy(SupervisionStrategy.RESUME)
            .withId(processorId)
            .spawn();
            
            // Update nextPid for the next iteration
            nextPid = processorPids[i-1];
        }
        
        // Create source actor and connect to the first processor
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
            
            @Override
            public void preStart(ActorContext context) {
                System.out.println("Source actor started: source");
            }
        })
        .withSupervisionStrategy(SupervisionStrategy.RESTART)
        .withId("source")
        .spawn();
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
        ActorSystem system = new ActorSystem();
        
        // Number of processors in the workflow
        int processorCount = 3;
        
        // Number of workflows to run
        int workflowCount = 5;
        
        // Initialize the completion latch
        completionLatch = new CountDownLatch(workflowCount);
        
        // Setup the workflow actors
        setupWorkflow(system, processorCount);
        
        // Start all actors
        system.getActors().values().forEach(a -> a.start());

        // Start multiple workflows
        for (int i = 0; i < workflowCount; i++) {
            startWorkflow(system, "Data-" + i);
        }
        
        try {
            // Wait for all workflows to complete
            if (!completionLatch.await(10, TimeUnit.SECONDS)) {
                System.out.println("Timeout waiting for workflows to complete");
            }
            
            // Print results
            System.out.println("\nWorkflow Results:");
            for (String result : results) {
                System.out.println(result);
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Interrupted while waiting for workflows");
        } finally {
            // Shutdown the actor system
            system.shutdown();
        }
    }
}
