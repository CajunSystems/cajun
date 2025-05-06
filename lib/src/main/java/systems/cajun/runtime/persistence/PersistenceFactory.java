package systems.cajun.runtime.persistence;

import systems.cajun.persistence.MessageJournal;
import systems.cajun.persistence.SnapshotStore;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Factory class for creating persistence component implementations.
 */
public class PersistenceFactory {
    
    private static final String DEFAULT_BASE_DIR = "cajun_persistence";
    private static final String JOURNAL_DIR = "journal";
    private static final String SNAPSHOT_DIR = "snapshots";
    
    /**
     * Creates a file-based message journal with the default directory.
     *
     * @param <M> The type of messages
     * @return A new FileMessageJournal instance
     */
    public static <M> MessageJournal<M> createFileMessageJournal() {
        Path journalDir = Paths.get(DEFAULT_BASE_DIR, JOURNAL_DIR);
        return new FileMessageJournal<>(journalDir);
    }
    
    /**
     * Creates a file-based message journal with a custom directory.
     *
     * @param <M> The type of messages
     * @param baseDir The base directory for persistence
     * @return A new FileMessageJournal instance
     */
    public static <M> MessageJournal<M> createFileMessageJournal(String baseDir) {
        Path journalDir = Paths.get(baseDir, JOURNAL_DIR);
        return new FileMessageJournal<>(journalDir);
    }
    
    /**
     * Creates a file-based snapshot store with the default directory.
     *
     * @param <S> The type of state
     * @return A new FileSnapshotStore instance
     */
    public static <S> SnapshotStore<S> createFileSnapshotStore() {
        Path snapshotDir = Paths.get(DEFAULT_BASE_DIR, SNAPSHOT_DIR);
        return new FileSnapshotStore<>(snapshotDir);
    }
    
    /**
     * Creates a file-based snapshot store with a custom directory.
     *
     * @param <S> The type of state
     * @param baseDir The base directory for persistence
     * @return A new FileSnapshotStore instance
     */
    public static <S> SnapshotStore<S> createFileSnapshotStore(String baseDir) {
        Path snapshotDir = Paths.get(baseDir, SNAPSHOT_DIR);
        return new FileSnapshotStore<>(snapshotDir);
    }
}
