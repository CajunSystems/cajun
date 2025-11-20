/**
 * Cajun Actor System - Core Module
 *
 * A high-performance, distributed actor system for Java 21+ featuring:
 * - Virtual threads for lightweight concurrency
 * - High-performance mailbox implementations (MPSC, LinkedBlockingQueue)
 * - Persistence and state management
 * - Clustering support with leader election
 * - Backpressure management
 * - Both traditional inheritance and modern interface-based actor programming
 *
 * @since 0.2.0
 */
module com.cajunsystems.cajun {
    // Required dependencies
    requires org.slf4j;
    requires org.jctools.core;
    requires com.google.common;
    requires org.apache.commons.math3;
    requires io.etcd.jetcd.core;
    requires ch.qos.logback.classic;

    // Core actor system API
    exports com.cajunsystems;

    // Handler interfaces for interface-based actor programming
    exports com.cajunsystems.handler;

    // Builder API for fluent actor creation
    exports com.cajunsystems.builder;

    // Configuration classes
    exports com.cajunsystems.config;

    // Mailbox abstraction and implementations
    exports com.cajunsystems.mailbox;

    // Backpressure management
    exports com.cajunsystems.backpressure;

    // Metrics and monitoring
    exports com.cajunsystems.metrics;

    // Persistence interfaces
    exports com.cajunsystems.persistence;
    exports com.cajunsystems.persistence.impl;

    // Cluster support
    exports com.cajunsystems.cluster;

    // Runtime implementations (exported for now, may be internalized in future)
    exports com.cajunsystems.runtime.persistence;
    exports com.cajunsystems.runtime.cluster;

    // Internal implementations (not exported - encapsulated)
    // com.cajunsystems.internal is not exported
}
