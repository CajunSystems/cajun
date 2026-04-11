package com.cajunsystems.cluster;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TtlCacheTest {

    @Test
    void testPutAndGet() {
        TtlCache<String, String> cache = new TtlCache<>(5_000);
        cache.put("k", "v");
        assertEquals(Optional.of("v"), cache.get("k"));
    }

    @Test
    void testExpiredEntryReturnsEmpty() throws InterruptedException {
        TtlCache<String, String> cache = new TtlCache<>(50);
        cache.put("k", "v");
        Thread.sleep(70);
        assertEquals(Optional.empty(), cache.get("k"));
    }

    @Test
    void testInvalidateRemovesEntry() {
        TtlCache<String, String> cache = new TtlCache<>(5_000);
        cache.put("k", "v");
        cache.invalidate("k");
        assertEquals(Optional.empty(), cache.get("k"));
    }

    @Test
    void testSnapshot_excludesExpiredEntries() throws InterruptedException {
        TtlCache<String, String> cache = new TtlCache<>(5_000);
        cache.put("live", "yes");
        // Put with very short TTL by creating a separate short-lived cache
        TtlCache<String, String> shortCache = new TtlCache<>(50);
        shortCache.put("expired", "no");
        Thread.sleep(70);
        Map<String, String> snap = cache.snapshot();
        assertTrue(snap.containsKey("live"));
        Map<String, String> shortSnap = shortCache.snapshot();
        assertFalse(shortSnap.containsKey("expired"));
    }

    @Test
    void testCleanupExpiredRemovesFromMap() throws InterruptedException {
        TtlCache<String, String> cache = new TtlCache<>(50);
        cache.put("k1", "v1");
        cache.put("k2", "v2");
        Thread.sleep(70);
        cache.cleanupExpired();
        assertEquals(0, cache.size());
    }

    @Test
    void testConcurrentPutAndGet() throws InterruptedException {
        TtlCache<String, String> cache = new TtlCache<>(5_000);
        int threads = 10;
        int perThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);
        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        String key = "thread-" + threadId + "-key-" + i;
                        cache.put(key, "v" + i);
                        cache.get(key); // should not throw
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executor.shutdown();
        assertEquals(0, errors.get(), "No exceptions expected in concurrent access");
        assertEquals(threads * perThread, cache.size());
    }

    @Test
    void testOverwriteResetsExpiry() throws InterruptedException {
        TtlCache<String, String> cache = new TtlCache<>(80);
        cache.put("k", "v1");
        Thread.sleep(40);
        // Overwrite — resets TTL to now + 80ms
        cache.put("k", "v2");
        Thread.sleep(50); // now 90ms since original put, but only 50ms since second put
        // Should still be present (second put expires at ~90ms from start, we are at ~90ms, close call)
        // Use get to check
        Optional<String> result = cache.get("k");
        // It may or may not be present depending on timing — just verify no exception
        // For a reliable test, sleep less
        // Actually: first put at t=0, second put at t=40, TTL=80 → second entry expires at t=120
        // We sleep 50ms after second put → total t=90ms, well within 120ms expiry
        assertTrue(result.isPresent(), "Entry should still be valid 50ms after overwrite with 80ms TTL");
        assertEquals("v2", result.get());
    }
}
