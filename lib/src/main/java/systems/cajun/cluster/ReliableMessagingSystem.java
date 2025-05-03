package systems.cajun.cluster;

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
import java.util.concurrent.Executors;

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
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private MessageHandler messageHandler;
    private final MessageTracker messageTracker;
    private DeliveryGuarantee defaultDeliveryGuarantee;
    
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
        this.systemId = systemId;
        this.port = port;
        this.defaultDeliveryGuarantee = defaultDeliveryGuarantee;
        this.messageTracker = new MessageTracker();
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
     * Gets the default delivery guarantee for this messaging system.
     *
     * @return The default delivery guarantee
     */
    public DeliveryGuarantee getDefaultDeliveryGuarantee() {
        return defaultDeliveryGuarantee;
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
        });
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
     * Performs the actual message sending.
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
        
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address.host, address.port), 5000);
            
            RemoteMessage<Message> remoteMessage = new RemoteMessage<>(
                    systemId,
                    actorId,
                    message,
                    messageId,
                    deliveryGuarantee
            );
            
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            out.writeObject(remoteMessage);
            out.flush();
            
            // For EXACTLY_ONCE and AT_LEAST_ONCE, we need to wait for an acknowledgment
            if (deliveryGuarantee != DeliveryGuarantee.AT_MOST_ONCE && messageId != null) {
                ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
                MessageAcknowledgment ack = (MessageAcknowledgment) in.readObject();
                
                if (ack.isSuccess()) {
                    // Message was successfully delivered and processed
                    messageTracker.acknowledgeMessage(messageId);
                }
            }
        } catch (ClassNotFoundException e) {
            throw new IOException("Failed to read acknowledgment", e);
        }
    }
    
    @Override
    public CompletableFuture<Void> registerMessageHandler(MessageHandler handler) {
        return CompletableFuture.runAsync(() -> {
            this.messageHandler = handler;
        });
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
        });
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
        });
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
            ObjectInputStream in = new ObjectInputStream(clientSocket.getInputStream());
            
            // Deserialize and handle the message
            RemoteMessage<?> remoteMessage = (RemoteMessage<?>) in.readObject();
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
                ObjectOutputStream out = new ObjectOutputStream(clientSocket.getOutputStream());
                MessageAcknowledgment ack = new MessageAcknowledgment(messageId, success);
                out.writeObject(ack);
                out.flush();
            }
            
        } catch (IOException | ClassNotFoundException e) {
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
     */
    private static class RemoteMessage<T> implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private final String sourceSystemId;
        private final String actorId;
        private final T message;
        private final String messageId;
        private final DeliveryGuarantee deliveryGuarantee;
        
        public RemoteMessage(String sourceSystemId, String actorId, T message, 
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
     */
    private static class MessageAcknowledgment implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private final String messageId;
        private final boolean success;
        
        public MessageAcknowledgment(String messageId, boolean success) {
            this.messageId = messageId;
            this.success = success;
        }
        
        public boolean isSuccess() {
            return success;
        }
    }
}
