package com.cajunsystems.persistence;

/**
 * Mode for automatic persistence truncation.
 */
public enum PersistenceTruncationMode {

    /**
     * Disable automatic truncation.
     */
    OFF,

    /**
     * Truncate journals synchronously as part of the snapshot lifecycle.
     */
    SYNC_ON_SNAPSHOT,

    /**
     * Truncate journals asynchronously using a background daemon.
     *
     * <p>Daemon wiring is backend-specific and may not be enabled for all
     * persistence providers.</p>
     */
    ASYNC_DAEMON
}
