package examples;

import systems.cajun.Actor;
import systems.cajun.ActorSystem;
import systems.cajun.FunctionalActor;
import systems.cajun.Pid;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

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
     * State for the source actor
     */
    private record SourceState(Pid nextActor) {}

    /**
     * State for the processor actor
     */
    private record ProcessorState(int processorNumber, Pid nextActor) {}

    /**
     * Creates a workflow with the specified number of processors using functional actors
     */
    private static void setupFunctionalWorkflow(ActorSystem system, int processorCount) {
        // Create sink actor
        FunctionalActor<Void, WorkflowMessage> sinkActor = new FunctionalActor<>();
        Pid sinkPid = system.register(sinkActor.receiveMessage((state, message) -> {
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
            return null;
        }, null), "sink");

        // Create processor actors
        @SuppressWarnings("unchecked")
        BiFunction<ProcessorState, WorkflowMessage, ProcessorState>[] processorActions = new BiFunction[processorCount];
        @SuppressWarnings("unchecked")
        ProcessorState[] processorStates = new ProcessorState[processorCount];

        // Initialize processor actions and states
        for (int i = 0; i < processorCount; i++) {
            final int processorNumber = i + 1;
            processorStates[i] = new ProcessorState(processorNumber, null);

            processorActions[i] = (state, message) -> {
                if (message instanceof WorkflowMessage.ProcessData processMsg) {
                    // Check if this is the right step for this processor
                    if (processMsg.step() == state.processorNumber()) {
                        System.out.println("Processor " + state.processorNumber() + 
                                          ": Processing workflow " + processMsg.workflowId());

                        // Simulate processing by transforming the data
                        String processedData = processMsg.data() + "-P" + state.processorNumber();

                        // Forward to next actor
                        if (state.nextActor() != null) {
                            state.nextActor().tell(new WorkflowMessage.ProcessData(
                                processMsg.workflowId(),
                                processedData,
                                processMsg.step() + 1
                            ));
                        }
                    } else {
                        System.out.println("Processor " + state.processorNumber() + 
                                          ": Skipping message for step " + processMsg.step());
                    }
                } else {
                    System.out.println("Processor " + state.processorNumber() + 
                                     ": Unexpected message type: " + message);
                }
                return state;
            };
        }

        // Create the processor chain
        Pid firstProcessorPid = FunctionalActor.createChain(
            system, 
            "processor", 
            processorCount, 
            processorStates, 
            processorActions
        );

        // Connect the last processor to the sink
        Actor<?> lastProcessor = system.getActor(new Pid("processor-" + processorCount, system));
        if (lastProcessor != null) {
            lastProcessor.withNext(sinkPid);
        }

        // Create source actor
        FunctionalActor<SourceState, WorkflowMessage> sourceActor = new FunctionalActor<>();
        Pid sourcePid = system.register(sourceActor.receiveMessage((state, message) -> {
            if (message instanceof WorkflowMessage.StartWorkflow startMsg) {
                System.out.println("Source: Starting workflow " + startMsg.workflowId());

                // Forward to first processor
                state.nextActor().tell(new WorkflowMessage.ProcessData(
                    startMsg.workflowId(), 
                    startMsg.data(),
                    1
                ));
            } else {
                System.out.println("Source: Unexpected message type: " + message);
            }
            return state;
        }, new SourceState(firstProcessorPid)), "source");
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
