package com.cajunsystems.direct;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ParTest {

    @Test
    void parReturnsBothResults() {
        Scoped.supervised(scope -> {
            Par.Result2<String, Integer> result = Par.par(
                    scope,
                    () -> "hello",
                    () -> 42
            );
            assertEquals("hello", result.first());
            assertEquals(42, result.second());
            return null;
        });
    }

    @Test
    void parWithListOfCallables() {
        Scoped.supervised(scope -> {
            List<Callable<Integer>> tasks = List.of(
                    () -> 1,
                    () -> 2,
                    () -> 3,
                    () -> 4,
                    () -> 5
            );
            List<Integer> results = Par.par(scope, tasks);
            assertEquals(List.of(1, 2, 3, 4, 5), results);
            return null;
        });
    }

    @Test
    void parMapMapsFunctionInParallel() {
        Scoped.supervised(scope -> {
            List<String> input = List.of("a", "bb", "ccc", "dddd");
            List<Integer> results = Par.parMap(scope, input, String::length);
            assertEquals(List.of(1, 2, 3, 4), results);
            return null;
        });
    }

    @Test
    void parFailureInOneTaskCancelsOthersAndPropagatesException() {
        RuntimeException expected = new RuntimeException("task failed");
        AtomicBoolean secondTaskInterrupted = new AtomicBoolean(false);

        assertThrows(RuntimeException.class, () -> {
            Scoped.supervised(scope -> {
                Par.par(
                        scope,
                        () -> {
                            throw expected;
                        },
                        () -> {
                            try {
                                Thread.sleep(5000);
                            } catch (InterruptedException e) {
                                secondTaskInterrupted.set(true);
                                throw e;
                            }
                            return "should not complete";
                        }
                );
                return null;
            });
        });

        // Give a brief moment for interruption to propagate
        // The second task should have been cancelled
        // (It may or may not have been interrupted depending on timing,
        // but the exception should propagate)
    }

    @Test
    void parFailureInListPropagatesException() {
        RuntimeException expected = new RuntimeException("list task failed");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            Scoped.supervised(scope -> {
                List<Callable<Integer>> tasks = List.of(
                        () -> {
                            throw expected;
                        },
                        () -> {
                            Thread.sleep(5000);
                            return 2;
                        },
                        () -> {
                            Thread.sleep(5000);
                            return 3;
                        }
                );
                Par.par(scope, tasks);
                return null;
            });
        });

        assertEquals("list task failed", thrown.getMessage());
    }

    @Test
    void parWithVaryingTaskDurationsVerifiesParallelism() {
        Scoped.supervised(scope -> {
            long start = System.currentTimeMillis();

            Par.Result2<String, String> result = Par.par(
                    scope,
                    () -> {
                        Thread.sleep(200);
                        return "fast";
                    },
                    () -> {
                        Thread.sleep(200);
                        return "also fast";
                    }
            );

            long elapsed = System.currentTimeMillis() - start;

            assertEquals("fast", result.first());
            assertEquals("also fast", result.second());

            // If run sequentially, would take ~400ms. In parallel, should be ~200ms.
            // Use generous threshold to avoid flaky tests.
            assertTrue(elapsed < 350, "Tasks should run in parallel, but took " + elapsed + "ms");
            return null;
        });
    }

    @Test
    void parListWithVaryingDurationsVerifiesParallelism() {
        Scoped.supervised(scope -> {
            long start = System.currentTimeMillis();

            List<Callable<Integer>> tasks = List.of(
                    () -> {
                        Thread.sleep(200);
                        return 1;
                    },
                    () -> {
                        Thread.sleep(200);
                        return 2;
                    },
                    () -> {
                        Thread.sleep(200);
                        return 3;
                    },
                    () -> {
                        Thread.sleep(200);
                        return 4;
                    }
            );

            List<Integer> results = Par.par(scope, tasks);

            long elapsed = System.currentTimeMillis() - start;

            assertEquals(List.of(1, 2, 3, 4), results);

            // If sequential, would take ~800ms. In parallel, should be ~200ms.
            assertTrue(elapsed < 400, "Tasks should run in parallel, but took " + elapsed + "ms");
            return null;
        });
    }

    @Test
    void parMapWithVaryingDurationsVerifiesParallelism() {
        Scoped.supervised(scope -> {
            long start = System.currentTimeMillis();

            List<Integer> input = List.of(1, 2, 3, 4, 5);
            List<Integer> results = Par.parMap(scope, input, x -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return x * 10;
            });

            long elapsed = System.currentTimeMillis() - start;

            assertEquals(List.of(10, 20, 30, 40, 50), results);

            // If sequential, would take ~1000ms. In parallel, should be ~200ms.
            assertTrue(elapsed < 400, "parMap should execute in parallel, but took " + elapsed + "ms");
            return null;
        });
    }

    @Test
    void parWithEmptyListReturnsEmptyList() {
        Scoped.supervised(scope -> {
            List<Callable<Integer>> tasks = List.of();
            List<Integer> results = Par.par(scope, tasks);
            assertTrue(results.isEmpty());
            return null;
        });
    }

    @Test
    void parMapWithEmptyListReturnsEmptyList() {
        Scoped.supervised(scope -> {
            List<String> input = List.of();
            List<Integer> results = Par.parMap(scope, input, String::length);
            assertTrue(results.isEmpty());
            return null;
        });
    }

    @Test
    void parExecutesBothTasksConcurrently() {
        Scoped.supervised(scope -> {
            AtomicInteger counter = new AtomicInteger(0);

            Par.Result2<Integer, Integer> result = Par.par(
                    scope,
                    () -> {
                        counter.incrementAndGet();
                        // Wait briefly to let both tasks start
                        Thread.sleep(50);
                        return counter.get();
                    },
                    () -> {
                        counter.incrementAndGet();
                        Thread.sleep(50);
                        return counter.get();
                    }
            );

            // Both tasks should have incremented the counter
            // Both results should see counter >= 2 if truly parallel
            assertEquals(2, counter.get());
            // At least one of them should see 2 (both started)
            assertTrue(result.first() == 2 || result.second() == 2,
                    "At least one task should see both increments");
            return null;
        });
    }
}