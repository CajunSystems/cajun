package com.cajunsystems.runtime.cluster;

import com.cajunsystems.cluster.MessagingSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A simple implementation of the MessagingSystem interface using direct TCP connections.
 * This implementation is suitable for development and testing but may not be ideal for production use.
 */
public class DirectMessagingSystem implements MessagingSystem {
    
    private static final Logger logger = LoggerFactory.getLogger(DirectMessagingSystem.class);
    
    private final String systemId;
    private final int port;
    private final Map<String, NodeAddress> nodeAddresses = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private MessageHandler messageHandler;
    
    /**
     * Creates a new DirectMessagingSystem.
     *
     * @param systemId The ID of this actor system
     * @param port The port to listen on for incoming messages
     */
    public DirectMessagingSystem(String systemId, int port) {
        this.systemId = systemId;
        this.port = port;
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
    
    @Override
    public <Message> CompletableFuture<Void> sendMessage(String targetSystemId, String actorId, Message message) {
        return CompletableFuture.runAsync(() -> {
            NodeAddress address = nodeAddresses.get(targetSystemId);
            if (address == null) {
                throw new IllegalArgumentException("Unknown target system ID: " + targetSystemId);
            }
            
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address.host, address.port), 5000);
                
                RemoteMessage<Message> remoteMessage = new RemoteMessage<>(
                        systemId,
                        actorId,
                        message
                );
                
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                out.writeObject(remoteMessage);
                out.flush();
            } catch (IOException e) {
                logger.error("Failed to send message to {}:{}", address.host, address.port, e);
                throw new RuntimeException("Failed to send message", e);
            }
        });
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
                logger.info("DirectMessagingSystem started on port {}", port);
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
            
            executor.shutdown();
            logger.info("DirectMessagingSystem stopped");
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
            
            if (messageHandler != null) {
                messageHandler.onMessage(remoteMessage.actorId, remoteMessage.message);
            } else {
                logger.warn("Received message but no handler is registered");
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
     *
     * @param <T> The type of the message payload
     */
    private static class RemoteMessage<T> implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private String sourceSystemId;
        private String actorId;
        private T message;
        
        public RemoteMessage(String sourceSystemId, String actorId, T message) {
            this.sourceSystemId = sourceSystemId;
            this.actorId = actorId;
            this.message = message;
        }
        
        /**
         * Gets the ID of the source system that sent this message.
         * This method is used for serialization/deserialization purposes.
         *
         * @return The source system ID
         */
        @SuppressWarnings("unused")
        public String getSourceSystemId() {
            return sourceSystemId;
        }
        
        /**
         * Gets the ID of the target actor for this message.
         * This method is used for serialization/deserialization purposes.
         *
         * @return The actor ID
         */
        @SuppressWarnings("unused")
        public String getActorId() {
            return actorId;
        }
        
        /**
         * Gets the message payload.
         * This method is used for serialization/deserialization purposes.
         *
         * @return The message
         */
        @SuppressWarnings("unused")
        public T getMessage() {
            return message;
        }
    }
}
