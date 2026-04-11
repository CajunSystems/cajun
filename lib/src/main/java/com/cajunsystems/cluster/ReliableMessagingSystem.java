package com.cajunsystems.cluster;

import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.metrics.ClusterMetrics;
import com.cajunsystems.serialization.KryoSerializationProvider;
import com.cajunsystems.serialization.SerializationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * An implementation of the MessagingSystem interface that supports different delivery guarantees.
 * This implementation extends the DirectMessagingSystem with acknowledgments, retries, and deduplication
 * to provide exactly-once, at-least-once, and at-most-once delivery guarantees.
 */
public class ReliableMessagingSystem implements MessagingSystem {

    private static final Logger logger = LoggerFactory.getLogger(ReliableMessagingSystem.class);

    private final String systemId;
    private final int port;
    private final Map<String, NodeAddress> nodeAddresses = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private MessageHandler messageHandler;
    private final MessageTracker messageTracker;
    private DeliveryGuarantee defaultDeliveryGuarantee;
    private final ThreadPoolFactory threadPoolConfig;
    private final SerializationProvider provider;
    private ClusterMetrics clusterMetrics;

    /**
     * Creates a new ReliableMessagingSystem with EXACTLY_ONCE as the default delivery guarantee.
     *
     * @param systemId The ID of this actor system
     * @param port The port to listen on for incoming messages
     */
    public ReliableMessagingSystem(String systemId, int port) {
        this(systemId, port, DeliveryGuarantee.EXACTLY_ONCE);
    }

    /**
     * Creates a new ReliableMessagingSystem with the specified default delivery guarantee.
     *
     * @param systemId The ID of this actor system
     * @param port The port to listen on for incoming messages
     * @param defaultDeliveryGuarantee The default delivery guarantee to use
     */
    public ReliableMessagingSystem(String systemId, int port, DeliveryGuarantee defaultDeliveryGuarantee) {
        this(systemId, port, defaultDeliveryGuarantee, new ThreadPoolFactory());
    }

    /**
     * Creates a new ReliableMessagingSystem with the specified default delivery guarantee and thread pool configuration.
     *
     * @param systemId The ID of this actor system
     * @param port The port to listen on for incoming messages
     * @param defaultDeliveryGuarantee The default delivery guarantee to use
     * @param threadPoolConfig The thread pool configuration to use
     */
    public ReliableMessagingSystem(String systemId, int port, DeliveryGuarantee defaultDeliveryGuarantee, ThreadPoolFactory threadPoolConfig) {
        this(systemId, port, defaultDeliveryGuarantee, threadPoolConfig, KryoSerializationProvider.INSTANCE);
    }

    /**
     * Creates a new ReliableMessagingSystem with the specified delivery guarantee, thread pool, and serialization provider.
     *
     * @param systemId The ID of this actor system
     * @param port The port to listen on for incoming messages
     * @param defaultDeliveryGuarantee The default delivery guarantee to use
     * @param threadPoolConfig The thread pool configuration to use
     * @param provider The serialization provider for encoding/decoding messages
     */
    public ReliableMessagingSystem(String systemId, int port, DeliveryGuarantee defaultDeliveryGuarantee,
                                   ThreadPoolFactory threadPoolConfig, SerializationProvider provider) {
        this.systemId = systemId;
        this.port = port;
        this.defaultDeliveryGuarantee = defaultDeliveryGuarantee;
        this.messageTracker = new MessageTracker();
        this.threadPoolConfig = threadPoolConfig;
        this.provider = provider;
        this.executor = threadPoolConfig.createExecutorService("messaging-system-" + systemId);
    }

    /**
     * Adds a node to the known node addresses.
     *
     * @param nodeId The ID of the node
     * @param host The hostname or IP address
     * @param port The port
     */
    public void addNode(String nodeId, String host, int port) {
        nodeAddresses.put(nodeId, new NodeAddress(host, port));
    }

    /**
     * Removes a node from the known node addresses.
     *
     * @param nodeId The ID of the node to remove
     */
    public void removeNode(String nodeId) {
        nodeAddresses.remove(nodeId);
    }

    /**
     * Sets the default delivery guarantee for this messaging system.
     *
     * @param deliveryGuarantee The default delivery guarantee to use
     */
    public void setDefaultDeliveryGuarantee(DeliveryGuarantee deliveryGuarantee) {
        this.defaultDeliveryGuarantee = deliveryGuarantee;
    }

    /**
     * Sets the cluster metrics instance for this messaging system.
     * Optional — if not set, no metrics are recorded.
     *
     * @param metrics The ClusterMetrics instance to use
     */
    public void setClusterMetrics(ClusterMetrics metrics) {
        this.clusterMetrics = metrics;
    }

    /**
     * Gets the default delivery guarantee for this messaging system.
     *
     * @return The default delivery guarantee
     */
    public DeliveryGuarantee getDefaultDeliveryGuarantee() {
        return defaultDeliveryGuarantee;
    }

    /**
     * Gets the thread pool factory for this messaging system.
     *
     * @return The thread pool factory
     */
    public ThreadPoolFactory getThreadPoolFactory() {
        return threadPoolConfig;
    }

    /**
     * Gets the serialization provider used by this messaging system.
     *
     * @return The serialization provider
     */
    public SerializationProvider getSerializationProvider() {
        return provider;
    }

    @Override
    public <Message> CompletableFuture<Void> sendMessage(String targetSystemId, String actorId, Message message) {
        return sendMessage(targetSystemId, actorId, message, defaultDeliveryGuarantee);
    }

    /**
     * Sends a message to a remote actor system with the specified delivery guarantee.
     *
     * @param targetSystemId The ID of the target actor system
     * @param actorId The ID of the target actor
     * @param message The message to send
     * @param deliveryGuarantee The delivery guarantee to use
     * @param <Message> The type of the message
     * @return A CompletableFuture that completes when the message is sent
     */
    public <Message> CompletableFuture<Void> sendMessage(
            String targetSystemId, String actorId, Message message, DeliveryGuarantee deliveryGuarantee) {

        return CompletableFuture.runAsync(() -> {
            NodeAddress address = nodeAddresses.get(targetSystemId);
            if (address == null) {
                throw new IllegalArgumentException("Unknown target system ID: " + targetSystemId);
            }

            try {
                String messageId = null;

                // For EXACTLY_ONCE and AT_LEAST_ONCE, we need to track the message
                if (deliveryGuarantee != DeliveryGuarantee.AT_MOST_ONCE) {
                    messageId = messageTracker.generateMessageId();
                    messageTracker.trackOutgoingMessage(
                        messageId, targetSystemId, actorId, message,
                        this::retrySendMessage
                    );
                }

                doSendMessage(targetSystemId, address, actorId, message, messageId, deliveryGuarantee);

            } catch (Exception e) {
                logger.error("Failed to send message to {}:{}", address.host, address.port, e);
                throw new RuntimeException("Failed to send message", e);
            }
        }, executor);
    }

    /**
     * Retries sending a message (used by the MessageTracker).
     *
     * @param messageId The ID of the message
     * @param targetSystemId The ID of the target system
     * @param actorId The ID of the target actor
     * @param message The message to retry
     */
    private <Message> void retrySendMessage(
            String messageId, String targetSystemId, String actorId, Object message) {

        NodeAddress address = nodeAddresses.get(targetSystemId);
        if (address == null) {
            logger.error("Cannot retry message to unknown system: {}", targetSystemId);
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Message typedMessage = (Message) message;
            doSendMessage(targetSystemId, address, actorId, typedMessage, messageId, DeliveryGuarantee.AT_LEAST_ONCE);
        } catch (Exception e) {
            logger.error("Failed to retry message to {}:{}", address.host, address.port, e);
        }
    }

    /**
     * Performs the actual message sending using length-prefixed framing.
     *
     * @param targetSystemId The ID of the target system
     * @param address The address of the target system
     * @param actorId The ID of the target actor
     * @param message The message to send
     * @param messageId The ID of the message (may be null for AT_MOST_ONCE)
     * @param deliveryGuarantee The delivery guarantee to use
     * @param <Message> The type of the message
     * @throws IOException If an I/O error occurs
     */
    private <Message> void doSendMessage(
            String targetSystemId, NodeAddress address, String actorId,
            Message message, String messageId, DeliveryGuarantee deliveryGuarantee) throws IOException {

        if (clusterMetrics != null) clusterMetrics.incrementRemoteMessagesSent();

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address.host, address.port), 5000);

            RemoteMessage<Message> remoteMessage = new RemoteMessage<>(
                    systemId,
                    actorId,
                    message,
                    messageId,
                    deliveryGuarantee
            );

            // Serialize with length-prefixed framing: [4-byte length][payload bytes]
            byte[] payload = provider.serialize(remoteMessage);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();

            // For EXACTLY_ONCE and AT_LEAST_ONCE, we need to wait for an acknowledgment
            if (deliveryGuarantee != DeliveryGuarantee.AT_MOST_ONCE && messageId != null) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                int ackLen = in.readInt();
                byte[] ackBytes = in.readNBytes(ackLen);
                MessageAcknowledgment ack = provider.deserialize(ackBytes, MessageAcknowledgment.class);

                if (ack.isSuccess()) {
                    // Message was successfully delivered and processed
                    messageTracker.acknowledgeMessage(messageId);
                }
            }
        } catch (IOException e) {
            if (clusterMetrics != null) clusterMetrics.incrementRemoteMessageFailures();
            throw e;
        }
    }

    @Override
    public CompletableFuture<Void> registerMessageHandler(MessageHandler handler) {
        return CompletableFuture.runAsync(() -> {
            this.messageHandler = handler;
        }, executor);
    }

    @Override
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            if (running) {
                return;
            }

            try {
                serverSocket = new ServerSocket(port);
                running = true;

                executor.submit(this::acceptConnections);
                logger.info("ReliableMessagingSystem started on port {}", port);
            } catch (IOException e) {
                logger.error("Failed to start messaging system", e);
                throw new RuntimeException("Failed to start messaging system", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            if (!running) {
                return;
            }

            running = false;

            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                logger.error("Error closing server socket", e);
            }

            messageTracker.shutdown();
            executor.shutdown();
            logger.info("ReliableMessagingSystem stopped");
        }, executor);
    }

    private void acceptConnections() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                executor.submit(() -> handleClient(clientSocket));
            } catch (IOException e) {
                if (running) {
                    logger.error("Error accepting connection", e);
                }
                // If not running, this is expected during shutdown
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        try (clientSocket) {
            DataInputStream in = new DataInputStream(clientSocket.getInputStream());

            // Read length-prefixed message: [4-byte length][payload bytes]
            int msgLen = in.readInt();
            byte[] msgBytes = in.readNBytes(msgLen);
            RemoteMessage<?> remoteMessage = provider.deserialize(msgBytes, RemoteMessage.class);

            if (clusterMetrics != null) clusterMetrics.incrementRemoteMessagesReceived();

            String messageId = remoteMessage.messageId;
            DeliveryGuarantee deliveryGuarantee = remoteMessage.deliveryGuarantee;

            boolean shouldProcess = true;
            boolean success = false;

            // For EXACTLY_ONCE, check if we've already processed this message
            if (deliveryGuarantee == DeliveryGuarantee.EXACTLY_ONCE && messageId != null) {
                if (messageTracker.isMessageProcessed(messageId)) {
                    // We've already processed this message, don't process it again
                    // but still send a success acknowledgment
                    shouldProcess = false;
                    success = true;
                    logger.debug("Received duplicate message {}, not processing again", messageId);
                }
            }

            // Process the message if needed
            if (shouldProcess && messageHandler != null) {
                try {
                    messageHandler.onMessage(remoteMessage.actorId, remoteMessage.message);
                    success = true;

                    // For EXACTLY_ONCE, mark the message as processed
                    if (deliveryGuarantee == DeliveryGuarantee.EXACTLY_ONCE && messageId != null) {
                        messageTracker.markMessageProcessed(messageId);
                    }
                } catch (Exception e) {
                    logger.error("Error processing message", e);
                    success = false;
                }
            }

            // Send acknowledgment for EXACTLY_ONCE and AT_LEAST_ONCE
            if (deliveryGuarantee != DeliveryGuarantee.AT_MOST_ONCE && messageId != null) {
                MessageAcknowledgment ack = new MessageAcknowledgment(messageId, success);
                byte[] ackBytes = provider.serialize(ack);
                DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream());
                out.writeInt(ackBytes.length);
                out.write(ackBytes);
                out.flush();
            }

        } catch (IOException e) {
            logger.error("Error handling client connection", e);
        }
    }

    /**
     * Represents a remote node's address.
     */
    private static class NodeAddress {
        final String host;
        final int port;

        NodeAddress(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /**
     * Represents a message sent between actor systems.
     * Does not require Serializable — serialization is handled by the provider.
     */
    static class RemoteMessage<T> {
        final String sourceSystemId;
        final String actorId;
        final T message;
        final String messageId;
        final DeliveryGuarantee deliveryGuarantee;

        // No-arg constructor for deserialization frameworks
        RemoteMessage() {
            this.sourceSystemId = null;
            this.actorId = null;
            this.message = null;
            this.messageId = null;
            this.deliveryGuarantee = null;
        }

        RemoteMessage(String sourceSystemId, String actorId, T message,
                            String messageId, DeliveryGuarantee deliveryGuarantee) {
            this.sourceSystemId = sourceSystemId;
            this.actorId = actorId;
            this.message = message;
            this.messageId = messageId;
            this.deliveryGuarantee = deliveryGuarantee;
        }
    }

    /**
     * Represents an acknowledgment for a message.
     * Does not require Serializable — serialization is handled by the provider.
     */
    static class MessageAcknowledgment {
        final String messageId;
        final boolean success;

        // No-arg constructor for deserialization frameworks
        MessageAcknowledgment() {
            this.messageId = null;
            this.success = false;
        }

        MessageAcknowledgment(String messageId, boolean success) {
            this.messageId = messageId;
            this.success = success;
        }

        public boolean isSuccess() {
            return success;
        }
    }
}
