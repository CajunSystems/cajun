package com.cajunsystems.persistence;

/**
 * Base exception for journal-related errors.
 * Provides specific exception types for different failure scenarios.
 */
public class JournalException extends RuntimeException {

    public JournalException(String message) {
        super(message);
    }

    public JournalException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Thrown when a journal entry exceeds the maximum size limit.
     * This typically indicates the message payload is too large for the storage backend.
     */
    public static class EntryTooLargeException extends JournalException {
        private final long entrySize;
        private final long maxSize;

        public EntryTooLargeException(long entrySize, long maxSize) {
            super(String.format("Journal entry size (%d bytes) exceeds maximum allowed size (%d bytes)",
                entrySize, maxSize));
            this.entrySize = entrySize;
            this.maxSize = maxSize;
        }

        public long getEntrySize() {
            return entrySize;
        }

        public long getMaxSize() {
            return maxSize;
        }
    }

    /**
     * Thrown when a transaction fails to commit.
     * This may indicate storage issues, lock contention, or data conflicts.
     */
    public static class TransactionFailedException extends JournalException {
        public TransactionFailedException(String message) {
            super(message);
        }

        public TransactionFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Thrown when corrupted or invalid data is detected during read operations.
     * This may indicate storage corruption, version mismatch, or serialization issues.
     */
    public static class CorruptedDataException extends JournalException {
        private final long sequenceNumber;

        public CorruptedDataException(String message, long sequenceNumber) {
            super(String.format("%s (sequence: %d)", message, sequenceNumber));
            this.sequenceNumber = sequenceNumber;
        }

        public CorruptedDataException(String message, long sequenceNumber, Throwable cause) {
            super(String.format("%s (sequence: %d)", message, sequenceNumber), cause);
            this.sequenceNumber = sequenceNumber;
        }

        public long getSequenceNumber() {
            return sequenceNumber;
        }
    }

    /**
     * Thrown when storage capacity is exceeded or unavailable.
     */
    public static class StorageException extends JournalException {
        public StorageException(String message) {
            super(message);
        }

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

