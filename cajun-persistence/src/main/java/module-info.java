/**
 * Cajun Persistence Module
 *
 * Provides persistence implementations for the Cajun actor system.
 *
 * Implementations:
 * - Filesystem-based persistence (portable, good for development)
 * - LMDB persistence (high-performance, production-ready)
 *
 * Performance comparison:
 * - Filesystem: ~10K-50K writes/sec, ~50K-100K reads/sec
 * - LMDB: ~500K-1M writes/sec, ~1M-2M reads/sec
 *
 * @since 0.2.0
 */
module com.cajunsystems.persistence {
    requires transitive com.cajunsystems.core;
    requires org.lmdbjava;
    requires org.slf4j;

    exports com.cajunsystems.persistence;
    exports com.cajunsystems.persistence.filesystem;
    exports com.cajunsystems.persistence.lmdb;
    exports com.cajunsystems.persistence.impl;
}
