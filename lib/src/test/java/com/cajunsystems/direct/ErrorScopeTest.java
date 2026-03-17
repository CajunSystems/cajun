package com.cajunsystems.direct;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ErrorScopeTest {

    @Test
    void errorScopeCapturesExceptionAndReturnsErrorResult() {
        Either<Throwable, String> result = ErrorScope.supervised(() -> {
            throw new RuntimeException("test error");
        });

        assertTrue(result.isLeft());
        assertEquals("test error", result.getLeft().getMessage());
    }

    @Test
    void errorScopeReturnsSuccessOnNoError() {
        Either<Throwable, String> result = ErrorScope.supervised(() -> "hello");

        assertTrue(result.isRight());
        assertEquals("hello", result.getRight());
    }

    @Test
    void nestedErrorScopesPropagateInnerFailures() {
        Either<Throwable, String> result = ErrorScope.supervised(() -> {
            Either<Throwable, String> inner = ErrorScope.supervised(() -> {
                throw new IllegalStateException("inner failure");
            });

            if (inner.isLeft()) {
                throw inner.getLeft();
            }
            return inner.getRight();
        });

        assertTrue(result.isLeft());
        assertInstanceOf(IllegalStateException.class, result.getLeft());
        assertEquals("inner failure", result.getLeft().getMessage());
    }

    @Test
    void directSupervisorRestartStrategyReInvokesFailedTask() {
        AtomicInteger attempts = new AtomicInteger(0);

        DirectSupervisor supervisor = new DirectSupervisor(DirectSupervisor.Strategy.RESTART, 3);

        Either<Throwable, String> result = supervisor.supervise(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 3) {
                throw new RuntimeException("attempt " + attempt);
            }
            return "success on attempt " + attempt;
        });

        assertTrue(result.isRight());
        assertEquals("success on attempt 3", result.getRight());
        assertEquals(3, attempts.get());
    }

    @Test
    void directSupervisorStopStrategyHaltsOnFirstFailure() {
        AtomicInteger attempts = new AtomicInteger(0);

        DirectSupervisor supervisor = new DirectSupervisor(DirectSupervisor.Strategy.STOP, 3);

        Either<Throwable, String> result = supervisor.supervise(() -> {
            attempts.incrementAndGet();
            throw new RuntimeException("always fails");
        });

        assertTrue(result.isLeft());
        assertEquals("always fails", result.getLeft().getMessage());
        assertEquals(1, attempts.get());
    }

    @Test
    void supervisorCollectsAllChildErrors() {
        DirectSupervisor supervisor = new DirectSupervisor(DirectSupervisor.Strategy.RESTART, 3);

        AtomicInteger attempts = new AtomicInteger(0);

        supervisor.supervise(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 3) {
                throw new RuntimeException("error " + attempt);
            }
            return "done";
        });

        List<Throwable> errors = supervisor.getErrors();
        assertEquals(2, errors.size());
        assertEquals("error 1", errors.get(0).getMessage());
        assertEquals("error 2", errors.get(1).getMessage());
    }

    @Test
    void errorScopeWithParIntegration() {
        Either<Throwable, List<Object>> result = ErrorScope.supervised(() -> {
            return Par.par(
                    () -> "ok",
                    () -> {
                        throw new RuntimeException("par failure");
                    }
            );
        });

        assertTrue(result.isLeft());
        assertInstanceOf(RuntimeException.class, result.getLeft());
    }

    @Test
    void errorScopeWithForkIntegration() throws Exception {
        Either<Throwable, String> result = ErrorScope.supervised(() -> {
            try (var scope = new ConcurrencyScope()) {
                Fork<String> fork = scope.fork(() -> {
                    throw new RuntimeException("fork failure");
                });

                return fork.join();
            }
        });

        assertTrue(result.isLeft());
    }

    @Test
    void cancellationOfRemainingTasksWhenScopeFails() throws Exception {
        AtomicInteger completedTasks = new AtomicInteger(0);

        Either<Throwable, Object> result = ErrorScope.supervised(() -> {
            try (var scope = new ConcurrencyScope()) {
                Fork<String> fast = scope.fork(() -> {
                    throw new RuntimeException("fast failure");
                });

                Fork<String> slow = scope.fork(() -> {
                    Thread.sleep(5000);
                    completedTasks.incrementAndGet();
                    return "slow result";
                });

                // The fast fork fails, which should cause the scope to fail
                String fastResult = fast.join();
                return fastResult;
            }
        });

        assertTrue(result.isLeft());
        // Give a moment for any cleanup
        Thread.sleep(100);
        assertEquals(0, completedTasks.get(), "Slow task should not have completed");
    }

    @Test
    void restartStrategyExhaustsMaxRetries() {
        AtomicInteger attempts = new AtomicInteger(0);

        DirectSupervisor supervisor = new DirectSupervisor(DirectSupervisor.Strategy.RESTART, 3);

        Either<Throwable, String> result = supervisor.supervise(() -> {
            attempts.incrementAndGet();
            throw new RuntimeException("persistent failure");
        });

        assertTrue(result.isLeft());
        assertEquals("persistent failure", result.getLeft().getMessage());
        assertEquals(3, attempts.get());
    }

    @Test
    void errorScopeSuccessWithComplexComputation() {
        Either<Throwable, Integer> result = ErrorScope.supervised(() -> {
            int sum = 0;
            for (int i = 1; i <= 100; i++) {
                sum += i;
            }
            return sum;
        });

        assertTrue(result.isRight());
        assertEquals(5050, result.getRight());
    }

    @Test
    void errorScopeHandlesNullReturnValue() {
        Either<Throwable, String> result = ErrorScope.supervised(() -> null);

        assertTrue(result.isRight());
        assertNull(result.getRight());
    }

    @Test
    void nestedSupervisorsWorkIndependently() {
        DirectSupervisor outer = new DirectSupervisor(DirectSupervisor.Strategy.STOP, 1);
        DirectSupervisor inner = new DirectSupervisor(DirectSupervisor.Strategy.RESTART, 3);

        AtomicInteger innerAttempts = new AtomicInteger(0);

        Either<Throwable, String> outerResult = outer.supervise(() -> {
            Either<Throwable, String> innerResult = inner.supervise(() -> {
                int attempt = innerAttempts.incrementAndGet();
                if (attempt < 2) {
                    throw new RuntimeException("inner retry");
                }
                return "inner success";
            });

            if (innerResult.isRight()) {
                return innerResult.getRight();
            }
            throw innerResult.getLeft();
        });

        assertTrue(outerResult.isRight());
        assertEquals("inner success", outerResult.getRight());
        assertEquals(2, innerAttempts.get());
        assertTrue(outer.getErrors().isEmpty());
        assertEquals(1, inner.getErrors().size());
    }
}