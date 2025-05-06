package systems.cajun.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * A mock implementation of MessageJournal for testing purposes.
 * This implementation doesn't actually serialize messages, avoiding serialization issues in tests.
 *
 * @param <M> The type of the message
 */
public class MockMessageJournal<M> implements MessageJournal<M> {
    
    private final Map<String, List<JournalEntry<M>>> journal = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequenceNumbers = new ConcurrentHashMap<>();
    
    /**
     * Clear all journal entries for all actors.
     * This is useful for testing to reset the journal state.
     */
    public void clear() {
        journal.clear();
        sequenceNumbers.clear();
    }
    
    /**
     * Get all messages stored in the journal for a specific actor.
     * This is useful for testing to verify message journaling.
     * 
     * @param actorId The actor ID
     * @return The list of journal entries for the actor
     */
    public List<JournalEntry<M>> getMessages(String actorId) {
        return journal.getOrDefault(actorId, new ArrayList<>());
    }
    
    @Override
    public CompletableFuture<Long> append(String actorId, M message) {
        return CompletableFuture.supplyAsync(() -> {
            List<JournalEntry<M>> entries = journal.computeIfAbsent(actorId, k -> new ArrayList<>());
            AtomicLong sequenceNumber = sequenceNumbers.computeIfAbsent(actorId, k -> new AtomicLong(0));
            long sequence = sequenceNumber.getAndIncrement();
            entries.add(new JournalEntry<M>(sequence, actorId, message, Instant.now()));
            return sequence;
        });
    }
    
    @Override
    public CompletableFuture<List<JournalEntry<M>>> readFrom(String actorId, long fromSequenceNumber) {
        return CompletableFuture.supplyAsync(() -> {
            List<JournalEntry<M>> entries = journal.getOrDefault(actorId, new ArrayList<>());
            return entries.stream()
                    .filter(entry -> entry.getSequenceNumber() >= fromSequenceNumber)
                    .collect(Collectors.toList());
        });
    }
    
    @Override
    public CompletableFuture<Void> truncateBefore(String actorId, long upToSequenceNumber) {
        return CompletableFuture.runAsync(() -> {
            List<JournalEntry<M>> entries = journal.getOrDefault(actorId, new ArrayList<>());
            List<JournalEntry<M>> remainingEntries = entries.stream()
                    .filter(entry -> entry.getSequenceNumber() >= upToSequenceNumber)
                    .collect(Collectors.toList());
            journal.put(actorId, remainingEntries);
        });
    }
    
    @Override
    public CompletableFuture<Long> getHighestSequenceNumber(String actorId) {
        return CompletableFuture.supplyAsync(() -> {
            List<JournalEntry<M>> entries = journal.getOrDefault(actorId, new ArrayList<>());
            return entries.isEmpty() ? -1L : entries.get(entries.size() - 1).getSequenceNumber();
        });
    }
    
    /**
     * Get all journal entries for a specific actor.
     * This is useful for testing to verify the journal contents.
     * 
     * @param actorId The actor ID
     * @return The list of journal entries
     */
    public List<JournalEntry<M>> getEntriesForActor(String actorId) {
        return new ArrayList<>(journal.getOrDefault(actorId, new ArrayList<>()));
    }
    
    @Override
    public void close() {
        // No resources to release
    }
}
