package examples;

import com.cajunsystems.Actor;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This test demonstrates state consistency between actors and threads.
 * It performs a series of operations that require maintaining consistent state:
 * 1. Deposit & withdrawal operations on a simulated account
 * 2. State transitions that must maintain invariants
 * 3. Concurrent access patterns that test consistency guarantees
 * <p>
 * The test compares:
 * 1. Actors with isolated state
 * 2. Threads with synchronized access to shared state
 * 3. Threads with atomic reference-based state
 * 4. Threads with unsynchronized access (unsafe)
 */
public class StateConsistencyTest {

    private static final Logger logger = LoggerFactory.getLogger(StateConsistencyTest.class);
    private static final int NUM_WORKERS = 10;
    private static final int OPERATIONS_PER_WORKER = 200_000;
    private static final int TOTAL_OPERATIONS = NUM_WORKERS * OPERATIONS_PER_WORKER;
    private static final int INITIAL_BALANCE = 1000;
    private static final String RESULTS_FILE = "state_consistency_results.txt";

    // Warmup parameters
    private static final int WARMUP_ITERATIONS = 10;
    private static final int WARMUP_OPERATIONS_PER_WORKER = 10_000; // A fraction of actual operations

    public static void main(String[] args) throws Exception {
        logger.info("Starting Actor vs Threads State Consistency Comparison");

        // Create a results file
        Path resultsPath = Paths.get(RESULTS_FILE);
        Files.deleteIfExists(resultsPath);
        Files.createFile(resultsPath);

        // Run actor-based implementation
        ConsistencyResult actorResult = runActorImplementation();

        // Run thread-based implementation with synchronized methods
        ConsistencyResult synchronizedThreadResult = runSynchronizedThreadImplementation();

        // Run thread-based implementation with atomic references
        ConsistencyResult atomicThreadResult = runAtomicThreadImplementation();

        // Run thread-based implementation without synchronization (unsafe)
        ConsistencyResult unsafeThreadResult = runUnsafeThreadImplementation();

        // Format results
        String results = formatResults(actorResult, synchronizedThreadResult, atomicThreadResult, unsafeThreadResult);

        logger.info(results);

        // Write results to file
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(RESULTS_FILE))) {
            writer.write(results);
        }

        logger.info("Results saved to {}", RESULTS_FILE);
    }

    private static String formatResults(
            ConsistencyResult actorResult,
            ConsistencyResult synchronizedThreadResult,
            ConsistencyResult atomicThreadResult,
            ConsistencyResult unsafeThreadResult) {

        return String.format(
                "State Consistency Comparison Results:\n" +
                        "Total operations: %d (across %d workers, %d operations each)\n\n" +
                        "Actor-based implementation (isolated state):\n" +
                        "  Total time: %d ms\n" +
                        "  Final balance: %d (expected: %d)\n" +
                        "  Balance consistency violations: %d\n" +
                        "  Invariant violations: %d\n\n" +
                        "Thread-based implementation with synchronized methods (shared state):\n" +
                        "  Total time: %d ms\n" +
                        "  Final balance: %d (expected: %d)\n" +
                        "  Balance consistency violations: %d\n" +
                        "  Invariant violations: %d\n\n" +
                        "Thread-based implementation with atomic references (shared state):\n" +
                        "  Total time: %d ms\n" +
                        "  Final balance: %d (expected: %d)\n" +
                        "  Balance consistency violations: %d\n" +
                        "  Invariant violations: %d\n\n" +
                        "Thread-based implementation without synchronization (unsafe, shared state):\n" +
                        "  Total time: %d ms\n" +
                        "  Final balance: %d (expected: %d)\n" +
                        "  Balance consistency violations: %d\n" +
                        "  Invariant violations: %d\n\n",
                TOTAL_OPERATIONS, NUM_WORKERS, OPERATIONS_PER_WORKER,
                actorResult.executionTime, actorResult.finalBalance, INITIAL_BALANCE,
                actorResult.balanceViolations, actorResult.invariantViolations,
                synchronizedThreadResult.executionTime, synchronizedThreadResult.finalBalance, INITIAL_BALANCE,
                synchronizedThreadResult.balanceViolations, synchronizedThreadResult.invariantViolations,
                atomicThreadResult.executionTime, atomicThreadResult.finalBalance, INITIAL_BALANCE,
                atomicThreadResult.balanceViolations, atomicThreadResult.invariantViolations,
                unsafeThreadResult.executionTime, unsafeThreadResult.finalBalance, INITIAL_BALANCE,
                unsafeThreadResult.balanceViolations, unsafeThreadResult.invariantViolations);
    }

    /**
     * Runs the actor-based implementation and returns the execution time and consistency metrics.
     * Each actor maintains its own isolated state, so no synchronization is needed.
     */
    private static ConsistencyResult runActorImplementation() throws Exception {
        logger.info("Running actor-based implementation (isolated state)...");

        // Warmup phase
        logger.info("Starting warmup for actor-based implementation...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            ActorSystem warmupSystem = new ActorSystem();
            CountDownLatch warmupLatch = new CountDownLatch(NUM_WORKERS);
            AccountActor warmupAccountActor = new AccountActor(warmupSystem, "warmup-account-" + i, INITIAL_BALANCE);
            registerActor(warmupSystem, "warmup-account-" + i, warmupAccountActor);

            List<WorkerActor> warmupWorkers = new ArrayList<>();
            for (int j = 0; j < NUM_WORKERS; j++) {
                WorkerActor worker = new WorkerActor(warmupSystem, "warmup-worker-" + i + "-" + j, "warmup-account-" + i, WARMUP_OPERATIONS_PER_WORKER, warmupLatch);
                registerActor(warmupSystem, "warmup-worker-" + i + "-" + j, worker);
                warmupWorkers.add(worker);
            }
            for (WorkerActor worker : warmupWorkers) {
                worker.tell(new StartMessage());
            }
            warmupLatch.await();
            warmupSystem.shutdown();
            logger.debug("Warmup iteration {}/{} for actors completed.", i + 1, WARMUP_ITERATIONS);
        }
        logger.info("Warmup for actor-based implementation completed.");

        // Actual measurement
        logger.info("Starting actual measurement for actor-based implementation...");
        ActorSystem system = new ActorSystem();

        // Create completion latch
        CountDownLatch completionLatch = new CountDownLatch(NUM_WORKERS);

        // Create and register account actor
        AccountActor accountActor = new AccountActor(system, "account", INITIAL_BALANCE);
        registerActor(system, "account", accountActor);

        // Create worker actors
        List<WorkerActor> workers = new ArrayList<>();
        for (int i = 0; i < NUM_WORKERS; i++) {
            WorkerActor worker = new WorkerActor(system, "worker-" + i, "account", OPERATIONS_PER_WORKER, completionLatch);
            registerActor(system, "worker-" + i, worker);
            workers.add(worker);
        }

        // Start timing
        long startTime = System.currentTimeMillis();

        // Start all workers
        for (WorkerActor worker : workers) {
            worker.tell(new StartMessage());
        }

        // Wait for completion
        completionLatch.await();

        // Calculate execution time
        long executionTime = System.currentTimeMillis() - startTime;

        // Get the final balance and consistency metrics
        CompletableFuture<Integer> balanceFuture = new CompletableFuture<>();
        accountActor.tell(new AccountMessage.GetBalanceMessage(balanceFuture));
        int finalBalance = balanceFuture.get(30, TimeUnit.SECONDS);

        // Shutdown actor system
        system.shutdown();

        logger.info("Actor-based implementation completed in {} ms, final balance: {}, violations: {}, invariant violations: {}",
                executionTime, finalBalance, accountActor.getBalanceViolations(), accountActor.getInvariantViolations());

        return new ConsistencyResult(
                executionTime,
                finalBalance,
                accountActor.getBalanceViolations(),
                accountActor.getInvariantViolations());
    }

    /**
     * Runs the thread-based implementation with synchronized methods and returns the execution time and consistency metrics.
     * All threads share the same state object and use synchronized methods to access it.
     */
    private static ConsistencyResult runSynchronizedThreadImplementation() throws Exception {
        logger.info("Running thread-based implementation with synchronized methods (shared state)...");

        // Warmup phase
        logger.info("Starting warmup for synchronized thread-based implementation...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            SynchronizedAccount warmupAccount = new SynchronizedAccount(INITIAL_BALANCE);
            CountDownLatch warmupLatch = new CountDownLatch(NUM_WORKERS);
            ExecutorService warmupExecutor = Executors.newVirtualThreadPerTaskExecutor();
            for (int j = 0; j < NUM_WORKERS; j++) {
                warmupExecutor.submit(new SynchronizedWorkerThread(warmupAccount, WARMUP_OPERATIONS_PER_WORKER, warmupLatch));
            }
            warmupLatch.await();
            warmupExecutor.shutdown();
            try {
                if (!warmupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    warmupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                warmupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.debug("Warmup iteration {}/{} for synchronized threads completed.", i + 1, WARMUP_ITERATIONS);
        }
        logger.info("Warmup for synchronized thread-based implementation completed.");

        // Actual measurement
        logger.info("Starting actual measurement for synchronized thread-based implementation...");
        // Create shared state object
        SynchronizedAccount account = new SynchronizedAccount(INITIAL_BALANCE);

        // Create completion latch
        CountDownLatch completionLatch = new CountDownLatch(NUM_WORKERS);

        // Create executor service
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        // Start timing
        long startTime = System.currentTimeMillis();

        // Create and start worker threads
        for (int i = 0; i < NUM_WORKERS; i++) {
            executor.submit(new SynchronizedWorkerThread(account, OPERATIONS_PER_WORKER, completionLatch));
        }

        // Wait for completion
        completionLatch.await();

        // Calculate execution time
        long executionTime = System.currentTimeMillis() - startTime;

        // Get the final state
        int finalBalance = account.getBalance();
        int balanceViolations = account.getBalanceViolations();
        int invariantViolations = account.getInvariantViolations();

        // Shutdown executor
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        logger.info("Thread-based implementation with synchronized methods completed in {} ms, final balance: {}, violations: {}, invariant violations: {}",
                executionTime, finalBalance, balanceViolations, invariantViolations);

        return new ConsistencyResult(executionTime, finalBalance, balanceViolations, invariantViolations);
    }

    /**
     * Runs the thread-based implementation with atomic references and returns the execution time and consistency metrics.
     * All threads share the same state object using atomic references for updates.
     */
    private static ConsistencyResult runAtomicThreadImplementation() throws Exception {
        logger.info("Running thread-based implementation with atomic references (shared state)...");

        // Warmup phase
        logger.info("Starting warmup for atomic thread-based implementation...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            AtomicAccount warmupAccount = new AtomicAccount(INITIAL_BALANCE);
            CountDownLatch warmupLatch = new CountDownLatch(NUM_WORKERS);
            ExecutorService warmupExecutor = Executors.newVirtualThreadPerTaskExecutor();
            for (int j = 0; j < NUM_WORKERS; j++) {
                warmupExecutor.submit(new AtomicWorkerThread(warmupAccount, WARMUP_OPERATIONS_PER_WORKER, warmupLatch));
            }
            warmupLatch.await();
            warmupExecutor.shutdown();
            try {
                if (!warmupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    warmupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                warmupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.debug("Warmup iteration {}/{} for atomic threads completed.", i + 1, WARMUP_ITERATIONS);
        }
        logger.info("Warmup for atomic thread-based implementation completed.");

        // Actual measurement
        logger.info("Starting actual measurement for atomic thread-based implementation...");
        // Create shared state object
        AtomicAccount account = new AtomicAccount(INITIAL_BALANCE);

        // Create completion latch
        CountDownLatch completionLatch = new CountDownLatch(NUM_WORKERS);

        // Create executor service
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        // Start timing
        long startTime = System.currentTimeMillis();

        // Create and start worker threads
        for (int i = 0; i < NUM_WORKERS; i++) {
            executor.submit(new AtomicWorkerThread(account, OPERATIONS_PER_WORKER, completionLatch));
        }

        // Wait for completion
        completionLatch.await();

        // Calculate execution time
        long executionTime = System.currentTimeMillis() - startTime;

        // Get the final state
        int finalBalance = account.getBalance();
        int balanceViolations = account.getBalanceViolations();
        int invariantViolations = account.getInvariantViolations();

        // Shutdown executor
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        logger.info("Thread-based implementation with atomic references completed in {} ms, final balance: {}, violations: {}, invariant violations: {}",
                executionTime, finalBalance, balanceViolations, invariantViolations);

        return new ConsistencyResult(executionTime, finalBalance, balanceViolations, invariantViolations);
    }

    /**
     * Runs the thread-based implementation without synchronization (unsafe) and returns the execution time and consistency metrics.
     * All threads share the same state object but do not synchronize access, which will likely lead to race conditions.
     */
    private static ConsistencyResult runUnsafeThreadImplementation() throws Exception {
        logger.info("Running thread-based implementation without synchronization (unsafe, shared state)...");

        // Warmup phase
        logger.info("Starting warmup for unsafe thread-based implementation...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            UnsafeAccount warmupAccount = new UnsafeAccount(INITIAL_BALANCE);
            CountDownLatch warmupLatch = new CountDownLatch(NUM_WORKERS);
            ExecutorService warmupExecutor = Executors.newVirtualThreadPerTaskExecutor();
            for (int j = 0; j < NUM_WORKERS; j++) {
                warmupExecutor.submit(new UnsafeWorkerThread(warmupAccount, WARMUP_OPERATIONS_PER_WORKER, warmupLatch));
            }
            warmupLatch.await();
            warmupExecutor.shutdown();
            try {
                if (!warmupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    warmupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                warmupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.debug("Warmup iteration {}/{} for unsafe threads completed.", i + 1, WARMUP_ITERATIONS);
        }
        logger.info("Warmup for unsafe thread-based implementation completed.");

        // Actual measurement
        logger.info("Starting actual measurement for unsafe thread-based implementation...");
        // Create shared state object
        UnsafeAccount account = new UnsafeAccount(INITIAL_BALANCE);

        // Create completion latch
        CountDownLatch completionLatch = new CountDownLatch(NUM_WORKERS);

        // Create executor service
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        // Start timing
        long startTime = System.currentTimeMillis();

        // Create and start worker threads
        for (int i = 0; i < NUM_WORKERS; i++) {
            executor.submit(new UnsafeWorkerThread(account, OPERATIONS_PER_WORKER, completionLatch));
        }

        // Wait for completion
        completionLatch.await();

        // Calculate execution time
        long executionTime = System.currentTimeMillis() - startTime;

        // Get the final state
        int finalBalance = account.getBalance();
        int balanceViolations = account.getBalanceViolations();
        int invariantViolations = account.getInvariantViolations();

        // Shutdown executor
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        logger.info("Thread-based implementation without synchronization completed in {} ms, final balance: {}, violations: {}, invariant violations: {}",
                executionTime, finalBalance, balanceViolations, invariantViolations);

        return new ConsistencyResult(executionTime, finalBalance, balanceViolations, invariantViolations);
    }

    /**
     * Helper method to register an actor with the system and start it.
     */
    private static <T extends Actor<?>> void registerActor(ActorSystem system, String actorId, T actor) {
        try {
            // Use reflection to access the private actors map in ActorSystem
            java.lang.reflect.Field actorsField = ActorSystem.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentHashMap<String, Actor<?>> actors =
                    (ConcurrentHashMap<String, Actor<?>>) actorsField.get(system);

            // Register the actor
            actors.put(actorId, actor);

            // Start the actor
            actor.start();

            logger.debug("Registered and started actor: {}", actorId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to register actor: " + actorId, e);
        }
    }

    // ===== Message classes =====

    /**
     * Message to start processing.
     */
    private static class StartMessage {
    }

    private sealed interface AccountMessage {

        /**
         * Message to deposit money into an account.
         */
        record DepositMessage(int amount) implements AccountMessage {
        }

        /**
         * Message to withdraw money from an account.
         */
        record WithdrawMessage(int amount) implements AccountMessage {
        }

        /**
         * Message to get the current balance.
         */
        record GetBalanceMessage(CompletableFuture<Integer> resultFuture) {
        }
    }


    // ===== Actor implementations =====

    /**
     * Account actor that maintains state consistency through the actor model.
     */
    public static class AccountActor extends Actor<Object> {
        private int balance;
        private int balanceViolations = 0;
        private int invariantViolations = 0;

        public AccountActor(ActorSystem system, String actorId, int initialBalance) {
            super(system, actorId);
            this.balance = initialBalance;
        }

        @Override
        protected void receive(Object message) {
            if (message instanceof AccountMessage.DepositMessage depositMsg) {
                int previousBalance = balance;
                balance += depositMsg.amount();

                // Check for consistency - deposit should always increase balance
                if (balance <= previousBalance) {
                    balanceViolations++;
                }
            } else if (message instanceof AccountMessage.WithdrawMessage withdrawMsg) {
                int previousBalance = balance;
                int amount = withdrawMsg.amount();

                // Check invariant - cannot withdraw more than the balance
                if (amount > balance) {
                    invariantViolations++;
                    // In a real system, we might reject the withdrawal
                    // For this test, we'll allow it to go negative to track issues
                }

                balance -= amount;

                // Check for consistency - withdrawal should always decrease balance
                if (balance >= previousBalance) {
                    balanceViolations++;
                }
            } else if (message instanceof AccountMessage.GetBalanceMessage getBalanceMsg) {
                getBalanceMsg.resultFuture().complete(balance);
            }
        }

        public int getBalanceViolations() {
            return balanceViolations;
        }

        public int getInvariantViolations() {
            return invariantViolations;
        }
    }

    /**
     * Worker actor that sends deposit and withdrawal messages to an account actor.
     */
    public static class WorkerActor extends Actor<Object> {
        private final String accountActorId;
        private final int operationsToPerform;
        private final CountDownLatch completionLatch;
        private final ActorSystem actorSystem;

        public WorkerActor(ActorSystem system, String actorId, String accountActorId,
                           int operationsToPerform, CountDownLatch completionLatch) {
            super(system, actorId);
            this.accountActorId = accountActorId;
            this.operationsToPerform = operationsToPerform;
            this.completionLatch = completionLatch;
            this.actorSystem = system;
        }

        @Override
        protected void receive(Object message) {
            if (message instanceof StartMessage) {
                // Get reference to account actor
                Actor<AccountMessage> accountActor = (Actor<AccountMessage>) actorSystem.getActor(new Pid(accountActorId, actorSystem));

                // Alternate between deposits and withdrawals
                for (int i = 0; i < operationsToPerform; i++) {
                    if (i % 2 == 0) {
                        // Deposit 10 units
                        accountActor.tell(new AccountMessage.DepositMessage(10));
                    } else {
                        // Withdraw 10 units
                        accountActor.tell(new AccountMessage.WithdrawMessage(10));
                    }

                    // Simulate some work to make the operation more realistic
                    if (i % 1000 == 0) {
                        Thread.yield();
                    }
                }

                // Signal completion
                completionLatch.countDown();
            }
        }
    }

    // ===== Thread implementations =====

    /**
     * Worker thread that uses synchronized methods to access shared state.
     */
    private static class SynchronizedWorkerThread implements Runnable {
        private final SynchronizedAccount account;
        private final int operationsToPerform;
        private final CountDownLatch completionLatch;

        public SynchronizedWorkerThread(SynchronizedAccount account, int operationsToPerform, CountDownLatch completionLatch) {
            this.account = account;
            this.operationsToPerform = operationsToPerform;
            this.completionLatch = completionLatch;
        }

        @Override
        public void run() {
            try {
                // Alternate between deposits and withdrawals
                for (int i = 0; i < operationsToPerform; i++) {
                    if (i % 2 == 0) {
                        // Deposit 10 units
                        account.deposit(10);
                    } else {
                        // Withdraw 10 units
                        account.withdraw(10);
                    }

                    // Simulate some work to make the operation more realistic
                    if (i % 1000 == 0) {
                        Thread.yield();
                    }
                }
            } finally {
                // Signal completion
                completionLatch.countDown();
            }
        }
    }

    /**
     * Worker thread that uses atomic references to access shared state.
     */
    private static class AtomicWorkerThread implements Runnable {
        private final AtomicAccount account;
        private final int operationsToPerform;
        private final CountDownLatch completionLatch;

        public AtomicWorkerThread(AtomicAccount account, int operationsToPerform, CountDownLatch completionLatch) {
            this.account = account;
            this.operationsToPerform = operationsToPerform;
            this.completionLatch = completionLatch;
        }

        @Override
        public void run() {
            try {
                // Alternate between deposits and withdrawals
                for (int i = 0; i < operationsToPerform; i++) {
                    if (i % 2 == 0) {
                        // Deposit 10 units
                        account.deposit(10);
                    } else {
                        // Withdraw 10 units
                        account.withdraw(10);
                    }

                    // Simulate some work to make the operation more realistic
                    if (i % 1000 == 0) {
                        Thread.yield();
                    }
                }
            } finally {
                // Signal completion
                completionLatch.countDown();
            }
        }
    }

    /**
     * Worker thread that does not synchronize access to shared state (unsafe).
     */
    private static class UnsafeWorkerThread implements Runnable {
        private final UnsafeAccount account;
        private final int operationsToPerform;
        private final CountDownLatch completionLatch;

        public UnsafeWorkerThread(UnsafeAccount account, int operationsToPerform, CountDownLatch completionLatch) {
            this.account = account;
            this.operationsToPerform = operationsToPerform;
            this.completionLatch = completionLatch;
        }

        @Override
        public void run() {
            try {
                // Alternate between deposits and withdrawals
                for (int i = 0; i < operationsToPerform; i++) {
                    if (i % 2 == 0) {
                        // Deposit 10 units
                        account.deposit(10);
                    } else {
                        // Withdraw 10 units
                        account.withdraw(10);
                    }

                    // Simulate some work to make the operation more realistic
                    if (i % 1000 == 0) {
                        Thread.yield();
                    }
                }
            } finally {
                // Signal completion
                completionLatch.countDown();
            }
        }
    }

    // ===== Shared state implementations =====

    /**
     * Account implementation with synchronized methods for thread safety.
     */
    private static class SynchronizedAccount {
        private int balance;
        private int balanceViolations = 0;
        private int invariantViolations = 0;

        public SynchronizedAccount(int initialBalance) {
            this.balance = initialBalance;
        }

        public synchronized void deposit(int amount) {
            int previousBalance = balance;
            balance += amount;

            // Check for consistency - deposit should always increase balance
            if (balance <= previousBalance) {
                balanceViolations++;
            }
        }

        public synchronized void withdraw(int amount) {
            int previousBalance = balance;

            // Check invariant - cannot withdraw more than the balance
            if (amount > balance) {
                invariantViolations++;
                // In a real system, we might reject the withdrawal
                // For this test, we'll allow it to go negative to track issues
            }

            balance -= amount;

            // Check for consistency - withdrawal should always decrease balance
            if (balance >= previousBalance) {
                balanceViolations++;
            }
        }

        public synchronized int getBalance() {
            return balance;
        }

        public synchronized int getBalanceViolations() {
            return balanceViolations;
        }

        public synchronized int getInvariantViolations() {
            return invariantViolations;
        }
    }

    /**
     * Account implementation with atomic references for thread safety.
     */
    private static class AtomicAccount {
        private final AtomicInteger balance;
        private final AtomicInteger balanceViolations = new AtomicInteger(0);
        private final AtomicInteger invariantViolations = new AtomicInteger(0);

        public AtomicAccount(int initialBalance) {
            this.balance = new AtomicInteger(initialBalance);
        }

        public void deposit(int amount) {
            int previousBalance = balance.get();
            int newBalance = balance.addAndGet(amount);

            // Check for consistency - deposit should always increase balance
            if (newBalance <= previousBalance) {
                balanceViolations.incrementAndGet();
            }
        }

        public void withdraw(int amount) {
            int previousBalance = balance.get();

            // Check invariant - cannot withdraw more than the balance
            if (amount > previousBalance) {
                invariantViolations.incrementAndGet();
                // In a real system, we might reject the withdrawal
                // For this test, we'll allow it to go negative to track issues
            }

            int newBalance = balance.addAndGet(-amount);

            // Check for consistency - withdrawal should always decrease balance
            if (newBalance >= previousBalance) {
                balanceViolations.incrementAndGet();
            }
        }

        public int getBalance() {
            return balance.get();
        }

        public int getBalanceViolations() {
            return balanceViolations.get();
        }

        public int getInvariantViolations() {
            return invariantViolations.get();
        }
    }

    /**
     * Account implementation without synchronization (unsafe).
     */
    private static class UnsafeAccount {
        private int balance;
        private int balanceViolations = 0;
        private int invariantViolations = 0;

        public UnsafeAccount(int initialBalance) {
            this.balance = initialBalance;
        }

        public void deposit(int amount) {
            int previousBalance = balance;
            balance += amount;  // This is not thread-safe!

            // Check for consistency - deposit should always increase balance
            if (balance <= previousBalance) {
                balanceViolations++;  // This counter update is also not thread-safe!
            }
        }

        public void withdraw(int amount) {
            int previousBalance = balance;

            // Check invariant - cannot withdraw more than the balance
            if (amount > balance) {
                invariantViolations++;  // This counter update is not thread-safe!
                // In a real system, we might reject the withdrawal
                // For this test, we'll allow it to go negative to track issues
            }

            balance -= amount;  // This is not thread-safe!

            // Check for consistency - withdrawal should always decrease balance
            if (balance >= previousBalance) {
                balanceViolations++;  // This counter update is also not thread-safe!
            }
        }

        public int getBalance() {
            return balance;  // This read is not guaranteed to be consistent
        }

        public int getBalanceViolations() {
            return balanceViolations;  // This read is not guaranteed to be consistent
        }

        public int getInvariantViolations() {
            return invariantViolations;  // This read is not guaranteed to be consistent
        }
    }

    // ===== Results data structure =====

    /**
     * Data structure to hold consistency test results.
     */
    private static class ConsistencyResult {
        private final long executionTime;
        private final int finalBalance;
        private final int balanceViolations;
        private final int invariantViolations;

        public ConsistencyResult(long executionTime, int finalBalance, int balanceViolations, int invariantViolations) {
            this.executionTime = executionTime;
            this.finalBalance = finalBalance;
            this.balanceViolations = balanceViolations;
            this.invariantViolations = invariantViolations;
        }
    }
}
