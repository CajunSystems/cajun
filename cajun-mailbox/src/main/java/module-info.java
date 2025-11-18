/**
 * Cajun Mailbox Module
 *
 * Provides high-performance mailbox implementations for the Cajun actor system.
 *
 * Implementations:
 * - LinkedMailbox: General-purpose, lock-optimized (2-3x faster than legacy)
 * - MpscMailbox: High-throughput MPSC queue (5-10x faster, unbounded)
 *
 * @since 0.2.0
 */
module com.cajunsystems.mailbox {
    requires transitive com.cajunsystems.core;
    requires transitive org.jctools.core;
    requires org.slf4j;

    exports com.cajunsystems.mailbox;
    exports com.cajunsystems.mailbox.config;
}
