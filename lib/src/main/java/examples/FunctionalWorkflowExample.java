package examples;

import systems.cajun.ActorContext;
import systems.cajun.ActorSystem;
import systems.cajun.Pid;
import systems.cajun.handler.Handler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Example demonstrating a workflow execution system using Functional Actors.
 * The workflow consists of:
 * - A Source Actor that initiates the workflow
 * - Multiple Processor Actors that perform sequential operations
 * - A Sink Actor that receives the final result
 */
public class FunctionalWorkflowExample {

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
     * Creates a workflow with the specified number of processors using functional actors
     */
    private static void setupFunctionalWorkflow(ActorSystem system, int processorCount) {
        // Create sink actor using the new interface-based approach
        Pid sinkPid = system.actorOf(new Handler<WorkflowMessage>() {
            @Override
            public void receive(WorkflowMessage message, ActorContext context) {
            if (message instanceof WorkflowMessage.ProcessData processMsg) {
                System.out.println("Sink: Received final result for workflow " + 
                                  processMsg.workflowId() + ": " + processMsg.data());

                // Store the result
                synchronized (results) {
                    results.add(processMsg.data());
                }

                // Signal workflow completion
                completionLatch.countDown();
            } else {
                System.out.println("Sink: Unexpected message type: " + message);
            }
        }
        }).withId("sink").spawn();

        // Create processor actors in a chain using the new interface-based approach
        Pid lastProcessorPid = sinkPid;
        for (int i = processorCount; i > 0; i--) {
            final int processorNumber = i;
            final Pid nextActor = lastProcessorPid;
            
            Pid processorPid = system.actorOf(new Handler<WorkflowMessage>() {
                @Override
                public void receive(WorkflowMessage message, ActorContext context) {
                if (message instanceof WorkflowMessage.ProcessData processMsg) {
                    // Check if this is the right step for this processor
                    if (processMsg.step() == processorNumber) {
                        System.out.println("Processor " + processorNumber + 
                                           ": Processing workflow " + processMsg.workflowId());

                        // Simulate processing by transforming the data
                        String processedData = processMsg.data() + "-P" + processorNumber;

                        // Forward to next actor
                        if (nextActor != null) {
                            nextActor.tell(new WorkflowMessage.ProcessData(
                                processMsg.workflowId(),
                                processedData,
                                processMsg.step() + 1
                            ));
                        }
                    } else {
                        System.out.println("Processor " + processorNumber + 
                                           ": Skipping message for step " + processMsg.step());
                    }
                } else {
                    System.out.println("Processor " + processorNumber + 
                                     ": Unexpected message type: " + message);
                }
            }
            }).withId("processor-" + processorNumber).spawn();
            
            lastProcessorPid = processorPid;
        }

        // First processor is the last one we created
        Pid firstProcessorPid = lastProcessorPid;

        // Create source actor using the new interface-based approach
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
        }).withId("source").spawn();
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
        setupFunctionalWorkflow(actorSystem, 3);

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

        } catch (InterruptedException e) {
            System.err.println("Workflow execution interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            // Shutdown the actor system
            for (var actor : actorSystem.getActors().values()) {
                actor.stop();
            }
        }
    }
}
