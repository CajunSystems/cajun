package com.cajunsystems.persistence;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for automatic persistence truncation.
 *
 * <p>This config controls how journals are truncated after snapshots (sync
 * mode) or by a background daemon (async mode). It is intended to be
 * consumed by framework code such as {@code StatefulActor} and not by
 * user code directly.</p>
 */
public final class PersistenceTruncationConfig {

    private static final long DEFAULT_RETAIN_BEHIND_SNAPSHOT = 500L;
    private static final long DEFAULT_RETAIN_LAST_MESSAGES = 5_000L;
    private static final Duration DEFAULT_DAEMON_INTERVAL = Duration.ofMinutes(5);

    private final PersistenceTruncationMode mode;
    private final long retainMessagesBehindSnapshot;
    private final long retainLastMessagesPerActor;
    private final Duration daemonInterval;

    private PersistenceTruncationConfig(Builder builder) {
        this.mode = builder.mode;
        this.retainMessagesBehindSnapshot = builder.retainMessagesBehindSnapshot;
        this.retainLastMessagesPerActor = builder.retainLastMessagesPerActor;
        this.daemonInterval = builder.daemonInterval;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Default configuration: synchronous truncation after snapshots with
     * sensible defaults.
     */
    public static PersistenceTruncationConfig defaultSync() {
        return builder()
                .mode(PersistenceTruncationMode.SYNC_ON_SNAPSHOT)
                .retainMessagesBehindSnapshot(DEFAULT_RETAIN_BEHIND_SNAPSHOT)
                .retainLastMessagesPerActor(DEFAULT_RETAIN_LAST_MESSAGES)
                .daemonInterval(DEFAULT_DAEMON_INTERVAL)
                .build();
    }

    /**
     * Convenience for an async-daemon focused configuration.
     */
    public static PersistenceTruncationConfig defaultAsync() {
        return builder()
                .mode(PersistenceTruncationMode.ASYNC_DAEMON)
                .retainMessagesBehindSnapshot(DEFAULT_RETAIN_BEHIND_SNAPSHOT)
                .retainLastMessagesPerActor(DEFAULT_RETAIN_LAST_MESSAGES)
                .daemonInterval(DEFAULT_DAEMON_INTERVAL)
                .build();
    }

    public PersistenceTruncationMode getMode() {
        return mode;
    }

    public long getRetainMessagesBehindSnapshot() {
        return retainMessagesBehindSnapshot;
    }

    public long getRetainLastMessagesPerActor() {
        return retainLastMessagesPerActor;
    }

    public Duration getDaemonInterval() {
        return daemonInterval;
    }

    public static final class Builder {
        private PersistenceTruncationMode mode = PersistenceTruncationMode.SYNC_ON_SNAPSHOT;
        private long retainMessagesBehindSnapshot = DEFAULT_RETAIN_BEHIND_SNAPSHOT;
        private long retainLastMessagesPerActor = DEFAULT_RETAIN_LAST_MESSAGES;
        private Duration daemonInterval = DEFAULT_DAEMON_INTERVAL;

        public Builder mode(PersistenceTruncationMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        public Builder retainMessagesBehindSnapshot(long retainMessagesBehindSnapshot) {
            if (retainMessagesBehindSnapshot < 0) {
                throw new IllegalArgumentException("retainMessagesBehindSnapshot must be >= 0");
            }
            this.retainMessagesBehindSnapshot = retainMessagesBehindSnapshot;
            return this;
        }

        public Builder retainLastMessagesPerActor(long retainLastMessagesPerActor) {
            if (retainLastMessagesPerActor < 0) {
                throw new IllegalArgumentException("retainLastMessagesPerActor must be >= 0");
            }
            this.retainLastMessagesPerActor = retainLastMessagesPerActor;
            return this;
        }

        public Builder daemonInterval(Duration daemonInterval) {
            this.daemonInterval = Objects.requireNonNull(daemonInterval, "daemonInterval");
            return this;
        }

        public PersistenceTruncationConfig build() {
            return new PersistenceTruncationConfig(this);
        }
    }
}
