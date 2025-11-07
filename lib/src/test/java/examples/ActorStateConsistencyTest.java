///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.cajunsystems:cajun:0.1.4
//JAVA 21+
//PREVIEW

package examples;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.config.ResizableMailboxConfig;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A simplified performance example that demonstrates actor state consistency
 * using the Cajun actor system's built-in backpressure mechanisms.
 */
public class ActorStateConsistencyTest {
    private static final Logger logger = LoggerFactory.getLogger(ActorStateConsistencyTest.class);

    // Configuration
    private static final int NUM_ACCOUNTS = 5;
    private static final int NUM_WORKERS_PER_ACCOUNT = 4;
    private static final int OPERATIONS_PER_WORKER = 5_000;
    private static final int INITIAL_BALANCE = 1000;
    private static final int BATCH_SIZE = 50;
    private static final int TRANSACTION_AMOUNT = 10;

    // Test modes
    private static final String BATCHED_MODE = "batched";
    private static final String UNBATCHED_MODE = "unbatched";

    public static void main(String[] args) {
        runTest(UNBATCHED_MODE);
    }

    /**
     * Run the test in the specified mode
     */
    private static void runTest(String mode) {
        logger.info("Running in {} mode", mode);

        // Configure thread pool with virtual threads and optimized settings for high throughput
        ThreadPoolFactory threadPoolFactory = new ThreadPoolFactory()
            .optimizeFor(ThreadPoolFactory.WorkloadType.IO_BOUND)
            .setPreferVirtualThreads(true)
            .setSchedulerThreads(Runtime.getRuntime().availableProcessors() * 2)
            .setUseSharedExecutor(false) // Disable shared executor to prevent thread starvation
            .setActorBatchSize(1); // Process one message at a time for better fairness

        // Create a BackpressureConfig with optimized settings
        BackpressureConfig backpressureConfig = new BackpressureConfig.Builder()
            .warningThreshold(0.7f)
            .criticalThreshold(0.9f)
            .recoveryThreshold(0.5f)
            .minCapacity(100)
            .maxCapacity(10000)
            .build();

        // Create actor system with optimized thread pool and backpressure config
        ActorSystem system = new ActorSystem(threadPoolFactory, backpressureConfig);

        try {
            // Reduce operations per worker for better stability
            int operationsPerWorker = 1000;
            
            // Create accounts
            List<Pid> accountPids = createAccounts(system);

            // Create workers
            List<Pid> workerPids = createWorkers(system, accountPids, mode, operationsPerWorker);

            // Start the test
            long startTime = System.currentTimeMillis();
            logger.info("Starting test with {} workers and {} accounts", workerPids.size(), accountPids.size());

            // Send start message to all workers
            for (Pid workerPid : workerPids) {
                system.tell(workerPid, new StartMessage());
            }

            // Wait for completion with longer timeout (120 seconds)
            int expectedTotalOperations = NUM_ACCOUNTS * NUM_WORKERS_PER_ACCOUNT * operationsPerWorker;
            boolean completed = waitForCompletion(system, accountPids, expectedTotalOperations, 120);

            // Calculate execution time
            long executionTime = System.currentTimeMillis() - startTime;
            logger.info("Test execution time: {}ms", executionTime);

            // Verify final state
            boolean allValid = verifyFinalState(system, accountPids, executionTime, mode);

            if (completed && allValid) {
                logger.info("Test PASSED: All accounts have valid state");
            } else {
                logger.error("Test FAILED: Some accounts have invalid state or test did not complete");
            }

        } catch (Exception e) {
            logger.error("Error running test: {}", e.getMessage(), e);
        } finally {
            // Shutdown the actor system
            system.shutdown();
        }
    }

    /**
     * Wait for operations to complete by polling account states
     */
    private static boolean waitForCompletion(ActorSystem system, List<Pid> accountPids, int expectedTotalOperations, int maxWaitTimeSeconds) {
        logger.info("Waiting for completion of {} total operations across {} accounts (max wait: {}s)",
                expectedTotalOperations, accountPids.size(), maxWaitTimeSeconds);
        
        long startTime = System.currentTimeMillis();
        long endTime = startTime + (maxWaitTimeSeconds * 1000);
        
        while (System.currentTimeMillis() < endTime) {
            try {
                // Check account states
                int totalProcessed = 0;
                for (Pid accountPid : accountPids) {
                    CompletableFuture<AccountState> stateFuture = new CompletableFuture<>();
                    system.tell(accountPid, new GetStateMessage(stateFuture));
                    AccountState state = stateFuture.get(5, TimeUnit.SECONDS);
                    totalProcessed += state.operationsProcessed;
                    
                    // Log progress every 10 seconds
                    if (System.currentTimeMillis() - startTime > 0 && (System.currentTimeMillis() - startTime) % 10000 < 1000) {
                        logger.info("Account {} progress: {}/{} operations processed",
                                accountPid.actorId(), state.operationsProcessed, expectedTotalOperations / accountPids.size());
                    }
                }
                
                // Log overall progress
                if (System.currentTimeMillis() - startTime > 0 && (System.currentTimeMillis() - startTime) % 10000 < 1000) {
                    logger.info("Overall progress: {}/{} operations processed ({}%)",
                            totalProcessed, expectedTotalOperations, 
                            String.format("%.2f", (totalProcessed * 100.0) / expectedTotalOperations));
                }
                
                // Check if all operations have been processed
                if (totalProcessed >= expectedTotalOperations) {
                    logger.info("All operations completed: {} processed", totalProcessed);
                    return true;
                }
                
                // Sleep before checking again
                Thread.sleep(1000);
                
            } catch (Exception e) {
                logger.error("Error while waiting for completion: {}", e.getMessage(), e);
                return false;
            }
        }
        
        logger.warn("Timeout waiting for completion after {}s", maxWaitTimeSeconds);
        return false;
    }

    private static List<Pid> createAccounts(ActorSystem system) {
        List<Pid> accountPids = new ArrayList<>();

        // Create optimized backpressure config for accounts
        BackpressureConfig backpressureConfig = new BackpressureConfig.Builder()
            .warningThreshold(0.7f)
            .criticalThreshold(0.9f)
            .recoveryThreshold(0.5f)
            .minCapacity(100)
            .maxCapacity(10000)
            .build();

        // Create optimized mailbox config for accounts
        ResizableMailboxConfig mailboxConfig = new ResizableMailboxConfig()
            .setInitialCapacity(1000)
            .setMaxCapacity(10000);

        for (int i = 0; i < NUM_ACCOUNTS; i++) {
            String accountId = "account-" + i;
            AccountHandler accountHandler = new AccountHandler(system, accountId);

            Pid accountPid = system.actorOf(accountHandler)
                    .withId(accountId)
                    .withBackpressureConfig(backpressureConfig)
                    .withMailboxConfig(mailboxConfig)
                    .spawn();

            accountPids.add(accountPid);
            logger.info("Created account {} with PID: {}", i, accountPid.actorId());
        }

        return accountPids;
    }

    private static List<Pid> createWorkers(ActorSystem system, List<Pid> accountPids, String mode, int operationsPerWorker) {
        List<Pid> workerPids = new ArrayList<>();
        boolean isBatchMode = mode.equals(BATCHED_MODE);

        // Create workers for each account
        for (int i = 0; i < NUM_ACCOUNTS; i++) {
            Pid accountPid = accountPids.get(i);
            List<Pid> singleAccountList = List.of(accountPid);

            for (int j = 0; j < NUM_WORKERS_PER_ACCOUNT; j++) {
                String workerId = "worker-" + ((i * NUM_WORKERS_PER_ACCOUNT) + j);

                // Create worker actor
                Pid workerPid = system.actorOf(new WorkerHandler()).withId(workerId).spawn();
                workerPids.add(workerPid);

                // Initialize worker with its account and operation count
                system.tell(workerPid, new InitMessage(singleAccountList, operationsPerWorker, isBatchMode));

                logger.info("Created worker {} for account {}", workerId, accountPid.actorId());
            }
        }

        return workerPids;
    }

    /**
     * Verify the final state of the accounts
     */
    private static boolean verifyFinalState(ActorSystem system, List<Pid> accountPids, long executionTime, String mode) {
        logger.info("Verifying final state for {} mode", mode);
        
        boolean allValid = true;
        int totalOperations = 0;
        int totalDeposits = 0;
        int totalWithdrawals = 0;
        long maxDelay = 0;
        long totalDelay = 0;
        int validAccounts = 0;
        
        // Collect account states
        List<AccountState> accountStates = new ArrayList<>();
        for (Pid accountPid : accountPids) {
            try {
                CompletableFuture<AccountState> stateFuture = new CompletableFuture<>();
                system.tell(accountPid, new GetStateMessage(stateFuture));
                AccountState state = stateFuture.get(5, TimeUnit.SECONDS);
                accountStates.add(state);
                
                // Track metrics
                totalOperations += state.operationsProcessed;
                totalDeposits += state.deposits;
                totalWithdrawals += state.withdrawals;
                maxDelay = Math.max(maxDelay, state.maxProcessingDelay);
                totalDelay += state.avgProcessingDelay * state.operationsProcessed;
                
                // Verify account balance
                boolean isValid = state.balance == INITIAL_BALANCE + (state.deposits * TRANSACTION_AMOUNT) - (state.withdrawals * TRANSACTION_AMOUNT);
                if (isValid) {
                    validAccounts++;
                } else {
                    logger.error("Account {} has invalid balance: {}, expected: {}, deposits: {}, withdrawals: {}",
                            accountPid.actorId(), state.balance, 
                            INITIAL_BALANCE + (state.deposits * TRANSACTION_AMOUNT) - (state.withdrawals * TRANSACTION_AMOUNT),
                            state.deposits, state.withdrawals);
                }
                
                allValid = allValid && isValid;
                
            } catch (Exception e) {
                logger.error("Error getting state for account {}: {}", accountPid.actorId(), e.getMessage());
                allValid = false;
            }
        }
        
        // Calculate aggregate metrics
        double avgDelay = totalOperations > 0 ? (double) totalDelay / totalOperations : 0;
        double operationsPerSecond = totalOperations > 0 ? (double) totalOperations / (executionTime / 1000.0) : 0;
        
        // Log detailed results
        logger.info("Account verification results:");
        logger.info("Mode: {}", mode);
        logger.info("Valid accounts: {}/{}", validAccounts, accountPids.size());
        logger.info("Total operations processed: {}", totalOperations);
        logger.info("Total deposits: {}", totalDeposits);
        logger.info("Total withdrawals: {}", totalWithdrawals);
        logger.info("Max processing delay: {} ns", maxDelay);
        logger.info("Avg processing delay: {} ns", avgDelay);
        logger.info("Execution time: {} ms", executionTime);
        logger.info("Operations per second: {}", String.format("%.2f", operationsPerSecond));
        
        return allValid;
    }

    /**
     * Handler for Worker Actor
     */
    private static class WorkerHandler implements Handler<Object> {
        private List<Pid> accountPids;
        private int totalOperations;
        private int operationsSent = 0;
        private int operationsAcknowledged = 0;
        private boolean started = false;
        private boolean completed = false;
        private boolean isBatchMode;
        private int batchSize = 50;
        private Random random = new Random();
        private int maxInflightMessages = 10; // Limit number of in-flight messages

        public WorkerHandler() {
            // Default constructor required by ActorSystem
        }

        @Override
        public void receive(Object message, com.cajunsystems.ActorContext context) {
            if (message instanceof InitMessage initMsg) {
                this.accountPids = initMsg.accountPids;
                this.totalOperations = initMsg.operations;
                this.isBatchMode = initMsg.isBatchMode;

                if (accountPids != null && !accountPids.isEmpty()) {
                    logger.debug("WorkerHandler created for account: {}, operations: {}, batchSize: {}, mode: {}",
                            accountPids.get(0).actorId(), totalOperations, batchSize, isBatchMode ? "batched" : "unbatched");
                }
            } else if (message instanceof StartMessage && !started) {
                started = true;

                if (accountPids != null && !accountPids.isEmpty()) {
                    logger.info("Worker {} starting {} operations on account {}",
                            context.self().actorId(), totalOperations, accountPids.get(0).actorId());

                    if (isBatchMode) {
                        sendNextBatch(context);
                    } else {
                        // For unbatched mode, send initial messages up to the max in-flight limit
                        sendMessagesUpToLimit(context);
                    }
                } else {
                    logger.warn("Worker {} has no account PIDs to work with", context.self().actorId());
                }
            } else if (message instanceof AckMessage) {
                operationsAcknowledged++;

                // In unbatched mode, send next message as soon as we get an acknowledgment
                if (!isBatchMode && operationsSent < totalOperations) {
                    sendSingleMessage(context);
                }
                // In batch mode, send next batch when all messages in current batch are acknowledged
                else if (isBatchMode && operationsAcknowledged % batchSize == 0 && operationsSent < totalOperations) {
                    sendNextBatch(context);
                }

                // Check if all operations are acknowledged
                if (operationsAcknowledged == totalOperations && !completed) {
                    completed = true;
                    logger.info("Worker {} completed: {}/{} operations acknowledged",
                            context.self().actorId(), operationsAcknowledged, totalOperations);
                }
                
                // Log progress periodically
                if (operationsAcknowledged % 100 == 0 || operationsAcknowledged == totalOperations) {
                    logger.debug("Worker {} progress: {}/{} operations acknowledged, {} sent",
                            context.self().actorId(), operationsAcknowledged, totalOperations, operationsSent);
                }
            } else if (message instanceof IsCompletedMessage isCompletedMsg) {
                // Always complete the future, even if null
                if (isCompletedMsg.future != null) {
                    logger.debug("Worker {} reporting completion status: {}, sent: {}, acked: {}", 
                            context.self().actorId(), completed, operationsSent, operationsAcknowledged);
                    isCompletedMsg.future.complete(completed);
                } else {
                    logger.warn("Worker {} received IsCompletedMessage with null future", context.self().actorId());
                }
            }
        }

        private void sendMessagesUpToLimit(com.cajunsystems.ActorContext context) {
            // Send messages up to the max in-flight limit
            int messagesToSend = Math.min(maxInflightMessages, totalOperations - operationsSent);
            
            for (int i = 0; i < messagesToSend; i++) {
                sendSingleMessage(context);
            }
        }

        private void sendSingleMessage(com.cajunsystems.ActorContext context) {
            if (operationsSent >= totalOperations) {
                return;
            }

            Pid accountPid = accountPids.get(0);
            
            if (random.nextBoolean()) {
                context.tell(accountPid, new DepositMessage(TRANSACTION_AMOUNT, context.self()));
            } else {
                context.tell(accountPid, new WithdrawMessage(TRANSACTION_AMOUNT, context.self()));
            }
            operationsSent++;

            if (operationsSent % 100 == 0 || operationsSent == totalOperations) {
                logger.debug("Worker {} sent {}/{} operations",
                        context.self().actorId(), operationsSent, totalOperations);
            }
        }

        private void sendNextBatch(com.cajunsystems.ActorContext context) {
            if (operationsSent >= totalOperations) {
                logger.debug("Worker {} has sent all operations ({}/{})",
                        context.self().actorId(), operationsSent, totalOperations);
                return;
            }
            
            int batchOperations = Math.min(batchSize, totalOperations - operationsSent);
            if (batchOperations <= 0) {
                logger.debug("Worker {} has sent all operations ({}/{})",
                        context.self().actorId(), operationsSent, totalOperations);
                return;
            }
            
            Pid accountPid = accountPids.get(0);

            for (int i = 0; i < batchOperations; i++) {
                if (random.nextBoolean()) {
                    context.tell(accountPid, new DepositMessage(TRANSACTION_AMOUNT, context.self()));
                } else {
                    context.tell(accountPid, new WithdrawMessage(TRANSACTION_AMOUNT, context.self()));
                }
                operationsSent++;
            }

            if (operationsSent % 100 == 0 || operationsSent == totalOperations) {
                logger.info("Worker {} progress: {}/{} operations sent in {} batches",
                        context.self().actorId(), operationsSent, totalOperations,
                        (int) Math.ceil((double) operationsSent / batchSize));
            }
        }
    }

    /**
     * Handler for Account Actor
     */
    private static class AccountHandler implements Handler<Object> {
        private int balance;
        private int operationsProcessed = 0;
        private long maxProcessingDelay = 0;
        private long totalProcessingDelay = 0;
        private int depositsReceived = 0;
        private int withdrawalsReceived = 0;

        public AccountHandler(ActorSystem system, String actorId) {
            this.balance = INITIAL_BALANCE;
        }

        @Override
        public void receive(Object message, com.cajunsystems.ActorContext context) {
            if (message instanceof InitMessage initMsg) {
                this.balance = initMsg.initialBalance;
                logger.debug("AccountHandler created with initial balance: {}", balance);
            } else if (message instanceof DepositMessage depositMsg) {
                long startTime = System.nanoTime();

                // Process deposit
                balance += depositMsg.amount;
                depositsReceived++;
                operationsProcessed++;

                // Track processing time
                long processingTime = System.nanoTime() - startTime;
                maxProcessingDelay = Math.max(maxProcessingDelay, processingTime);
                totalProcessingDelay += processingTime;

                // Send acknowledgment
                context.tell(depositMsg.sender, new AckMessage());
            } else if (message instanceof WithdrawMessage withdrawMsg) {
                long startTime = System.nanoTime();

                // Process withdrawal
                balance -= withdrawMsg.amount;
                withdrawalsReceived++;
                operationsProcessed++;

                // Track processing time
                long processingTime = System.nanoTime() - startTime;
                maxProcessingDelay = Math.max(maxProcessingDelay, processingTime);
                totalProcessingDelay += processingTime;

                // Send acknowledgment
                context.tell(withdrawMsg.sender, new AckMessage());
            } else if (message instanceof GetStateMessage getStateMsg) {
                // Calculate average processing delay
                long avgProcessingDelay = operationsProcessed > 0 ? totalProcessingDelay / operationsProcessed : 0;

                // Create and return account state
                AccountState state = new AccountState(
                        balance,
                        operationsProcessed,
                        depositsReceived,
                        withdrawalsReceived,
                        maxProcessingDelay,
                        avgProcessingDelay
                );

                if (getStateMsg.future != null) {
                    getStateMsg.future.complete(state);
                }
            }
        }
    }

    // Message classes
    private static class DepositMessage {
        public final int amount;
        public final Pid sender;

        public DepositMessage(int amount, Pid sender) {
            this.amount = amount;
            this.sender = sender;
        }
    }

    private static class WithdrawMessage {
        public final int amount;
        public final Pid sender;

        public WithdrawMessage(int amount, Pid sender) {
            this.amount = amount;
            this.sender = sender;
        }
    }

    private static class AckMessage {
        // Empty acknowledgment message
    }

    private static class StartMessage {
        // Empty start message
    }

    private static class IsCompletedMessage {
        public final CompletableFuture<Boolean> future;

        public IsCompletedMessage(CompletableFuture<Boolean> future) {
            this.future = future;
        }
    }

    private static class GetStateMessage {
        public final CompletableFuture<AccountState> future;

        public GetStateMessage(CompletableFuture<AccountState> future) {
            this.future = future;
        }
    }

    private static class AccountState {
        public final int balance;
        public final int operationsProcessed;
        public final int deposits;
        public final int withdrawals;
        public final long maxProcessingDelay;
        public final long avgProcessingDelay;

        public AccountState(int balance, int operationsProcessed, int deposits, int withdrawals, long maxProcessingDelay, long avgProcessingDelay) {
            this.balance = balance;
            this.operationsProcessed = operationsProcessed;
            this.deposits = deposits;
            this.withdrawals = withdrawals;
            this.maxProcessingDelay = maxProcessingDelay;
            this.avgProcessingDelay = avgProcessingDelay;
        }
    }

    private static class InitMessage {
        public final List<Pid> accountPids;
        public final int operations;
        public final boolean isBatchMode;
        public final int initialBalance;

        // Constructor for worker initialization
        public InitMessage(List<Pid> accountPids, int operations, boolean isBatchMode) {
            this.accountPids = accountPids;
            this.operations = operations;
            this.isBatchMode = isBatchMode;
            this.initialBalance = 0;
        }

        // Constructor for account initialization
        public InitMessage(int initialBalance) {
            this.initialBalance = initialBalance;
            this.accountPids = null;
            this.operations = 0;
            this.isBatchMode = false;
        }
    }
}
