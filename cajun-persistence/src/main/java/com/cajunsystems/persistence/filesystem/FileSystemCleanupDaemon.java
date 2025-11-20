package com.cajunsystems.persistence.filesystem;

import com.cajunsystems.persistence.MessageJournal;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * Background cleanup daemon for journals, primarily intended for filesystem persistence.
 *
 * <p>This daemon periodically truncates registered journals, keeping only the
 * last {@code retainLastMessagesPerActor} messages per actor.</p>
 */
public final class FileSystemCleanupDaemon implements AutoCloseable {

    private static final FileSystemCleanupDaemon INSTANCE = new FileSystemCleanupDaemon();

    private final Map<String, MessageJournal<?>> journals = new ConcurrentHashMap<>();
    private final Map<String, Long> perActorRetention = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cajun-fs-cleanup");
        t.setDaemon(true);
        return t;
    });

    private volatile long retainLastMessagesPerActor = 10_000; // default safety window
    private volatile Duration interval = Duration.ofMinutes(5);
    private volatile boolean started = false;

    private FileSystemCleanupDaemon() {
    }

    public static FileSystemCleanupDaemon getInstance() {
        return INSTANCE;
    }

    /**
     * Register a journal for background cleanup.
     *
     * @param actorId actor identifier
     * @param journal journal instance used for that actor
     */
    public void registerJournal(String actorId, MessageJournal<?> journal) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(journal, "journal");
        journals.put(actorId, journal);
    }

    /**
     * Register a journal for background cleanup with a specific retention
     * policy for this actor. If not specified, the global
     * {@code retainLastMessagesPerActor} value will be used.
     */
    public void registerJournal(String actorId, MessageJournal<?> journal, long retainLastMessagesPerActor) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(journal, "journal");
        if (retainLastMessagesPerActor < 0) {
            throw new IllegalArgumentException("retainLastMessagesPerActor must be >= 0");
        }
        journals.put(actorId, journal);
        perActorRetention.put(actorId, retainLastMessagesPerActor);
    }

    /**
     * Unregister a journal from background cleanup.
     */
    public void unregisterJournal(String actorId) {
        if (actorId != null) {
            journals.remove(actorId);
            perActorRetention.remove(actorId);
        }
    }

    /**
     * Configure how many messages to retain per actor when cleaning.
     */
    public void setRetainLastMessagesPerActor(long retainLastMessagesPerActor) {
        if (retainLastMessagesPerActor < 0) {
            throw new IllegalArgumentException("retainLastMessagesPerActor must be >= 0");
        }
        this.retainLastMessagesPerActor = retainLastMessagesPerActor;
    }

    /**
     * Configure how often the cleanup runs.
     */
    public void setInterval(Duration interval) {
        this.interval = Objects.requireNonNull(interval, "interval");
    }

    /**
     * Start the background cleanup daemon.
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        scheduler.scheduleAtFixedRate(this::runCleanupSafely,
                interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void runCleanupSafely() {
        try {
            runCleanupOnce().get();
        } catch (Exception ignored) {
            // Swallow exceptions to keep daemon alive; errors should be logged by journals themselves
        }
    }

    /**
     * Perform a single cleanup pass over all registered journals.
     * Exposed mainly for tests or manual triggering.
     */
    public CompletableFuture<Void> runCleanupOnce() {
        if (journals.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<?>[] futures = journals.entrySet().stream()
                .map(entry -> {
                    String actorId = entry.getKey();
                    MessageJournal<?> journal = entry.getValue();
                    long retainForActor = perActorRetention.getOrDefault(actorId, retainLastMessagesPerActor);
                    return journal.getHighestSequenceNumber(actorId)
                            .thenCompose(highestSeq -> {
                                if (highestSeq == null || highestSeq < 0) {
                                    return CompletableFuture.completedFuture(null);
                                }
                                long cutoff = highestSeq - retainForActor;
                                if (cutoff <= 0) {
                                    return CompletableFuture.completedFuture(null);
                                }
                                return journal.truncateBefore(actorId, cutoff);
                            });
                })
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futures);
    }

    @Override
    public void close() {
        scheduler.shutdown();
        journals.clear();
        perActorRetention.clear();
        started = false;
    }
}
