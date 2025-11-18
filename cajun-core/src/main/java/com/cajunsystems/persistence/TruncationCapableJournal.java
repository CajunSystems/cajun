package com.cajunsystems.persistence;

/**
 * Marker interface indicating that a MessageJournal implementation supports
 * automatic truncation driven by the actor framework.
 *
 * <p>This is primarily intended for file-based or segment-based journals
 * where periodic truncation is desirable. Backends that do not wish to be
 * truncated automatically should not implement this interface.</p>
 */
public interface TruncationCapableJournal {
}
