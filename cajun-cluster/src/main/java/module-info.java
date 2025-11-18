/**
 * Cajun Cluster Module
 *
 * Provides clustering implementations for the Cajun actor system, including
 * etcd-based metadata storage and direct messaging for inter-node communication.
 *
 * @since 0.2.0
 */
module com.cajunsystems.cluster {
    requires transitive com.cajunsystems.core;
    requires io.etcd.jetcd.core;
    requires io.grpc.stub;
    requires org.slf4j;

    exports com.cajunsystems.cluster.impl;
}
