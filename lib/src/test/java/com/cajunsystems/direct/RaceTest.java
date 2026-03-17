package com.cajunsystems.direct;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RaceTest {

    @Test
    void raceReturnsFirstCompletedResult() throws Exception {
        Scoped.supervised(scope -> {
            String result = Race.race(
                    scope,
                    () -> {
                        Thread.sleep(50);
                        return "fast";
                    },
                    () -> {
                        Thread.sleep(500);
                        return "slow";
                    }
            );
            assertEquals("fast", result);
            return null;
        });
    }

    @Test
    void raceReturnsFirstCompletedResultWhenSecondIsFaster() throws Exception {
        Scoped.supervised(scope -> {
            String result = Race.race(
                    scope,
                    () -> {
                        Thread.sleep(500);
                        return "slow";
                    },
                    () -> {
                        Thread.sleep(50);
                        return "fast";
                    }
            );
            assertEquals("fast", result);
            return null;
        });
    }

    @Test
    void raceSlowerTasksAreCancelled() throws Exception {
        AtomicBoolean slowTaskCompleted = new AtomicBoolean(false);

        Scoped.supervised(scope -> {
            String result = Race.race(
                    scope,
                    () -> {
                        Thread.sleep(50);
                        return "fast";
                    },
                    () -> {
                        try {
                            Thread.sleep(2000);
                            slowTaskCompleted.set(true);
                            return "slow";
                        } catch (InterruptedException e) {
                            // Task was cancelled
                            Thread.currentThread().interrupt();
                            throw e;
                        }
                    }
            );
            assertEquals("fast", result);
            // Give a bit of time to verify the slow task was indeed cancelled
            Thread.sleep(200);
            assertFalse(slowTaskCompleted.get(), "Slower task should have been cancelled");
            return null;
        });
    }

    @Test
    void raceWithMultipleTasks() throws Exception {
        AtomicInteger completionCount = new AtomicInteger(0);

        Scoped.supervised(scope -> {
            @SuppressWarnings("unchecked")
            java.util.concurrent.Callable<String>[] tasks = new java.util.concurrent.Callable[]{
                    () -> {
                        Thread.sleep(500);
                        completionCount.incrementAndGet();
                        return "task1";
                    },
                    () -> {
                        Thread.sleep(50);
                        completionCount.incrementAndGet();
                        return "task2";
                    },
                    () -> {
                        Thread.sleep(500);
                        completionCount.incrementAndGet();
                        return "task3";
                    }
            };

            String result = Race.race(scope, tasks);
            assertEquals("task2", result);
            // Give time for potential completions
            Thread.sleep(200);
            // Only the fast task should have completed; others should be cancelled
            assertEquals(1, completionCount.get(), "Only the winning task should complete");
            return null;
        });
    }

    @Test
    void raceOrThrowThrowsWhenAllFail() {
        assertThrows(Exception.class, () -> {
            Scoped.supervised(scope -> {
                Race.raceOrThrow(
                        scope,
                        () -> {
                            throw new RuntimeException("fail1");
                        },
                        () -> {
                            throw new RuntimeException("fail2");
                        }
                );
                return null;
            });
        });
    }

    @Test
    void raceOrThrowReturnsResultWhenOneSucceeds() throws Exception {
        Scoped.supervised(scope -> {
            String result = Race.raceOrThrow(
                    scope,
                    () -> {
                        throw new RuntimeException("fail");
                    },
                    () -> {
                        Thread.sleep(50);
                        return "success";
                    }
            );
            assertEquals("success", result);
            return null;
        });
    }

    @Test
    void timeoutReturnsResultWithinTimeLimit() throws Exception {
        Scoped.supervised(scope -> {
            String result = Race.timeout(
                    scope,
                    Duration.ofSeconds(2),
                    () -> {
                        Thread.sleep(50);
                        return "done";
                    }
            );
            assertEquals("done", result);
            return null;
        });
    }

    @Test
    void timeoutThrowsTimeoutExceptionWhenExceeded() {
        assertThrows(TimeoutException.class, () -> {
            Scoped.supervised(scope -> {
                Race.timeout(
                        scope,
                        Duration.ofMillis(50),
                        () -> {
                            Thread.sleep(2000);
                            return "too late";
                        }
                );
                return null;
            });
        });
    }

    @Test
    void timeoutOrDefaultReturnsResultWhenFast() throws Exception {
        Scoped.supervised(scope -> {
            String result = Race.timeoutOrDefault(
                    scope,
                    Duration.ofSeconds(2),
                    "default",
                    () -> {
                        Thread.sleep(50);
                        return "done";
                    }
            );
            assertEquals("done", result);
            return null;
        });
    }

    @Test
    void timeoutOrDefaultReturnsDefaultOnTimeout() throws Exception {
        Scoped.supervised(scope -> {
            String result = Race.timeoutOrDefault(
                    scope,
                    Duration.ofMillis(50),
                    "default",
                    () -> {
                        Thread.sleep(2000);
                        return "too late";
                    }
            );
            assertEquals("default", result);
            return null;
        });
    }

    @Test
    void raceHandlesImmediateResult() throws Exception {
        Scoped.supervised(scope -> {
            String result = Race.race(
                    scope,
                    () -> "immediate",
                    () -> {
                        Thread.sleep(1000);
                        return "slow";
                    }
            );
            assertEquals("immediate", result);
            return null;
        });
    }

    @Test
    void timeoutWithZeroDurationThrowsTimeoutException() {
        assertThrows(TimeoutException.class, () -> {
            Scoped.supervised(scope -> {
                Race.timeout(
                        scope,
                        Duration.ZERO,
                        () -> {
                            Thread.sleep(100);
                            return "result";
                        }
                );
                return null;
            });
        });
    }
}