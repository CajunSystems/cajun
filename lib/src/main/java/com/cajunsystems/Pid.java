package com.cajunsystems;

import com.cajunsystems.cluster.ClusterActorSystem;
import com.cajunsystems.cluster.DeliveryGuarantee;
import com.cajunsystems.persistence.MessageAdapter;

import java.io.*;
import java.util.concurrent.TimeUnit;

/**
 * Process ID (Pid) for an actor, used to send messages to the actor.
 * This class handles both local and remote actor references.
 * 
 * Note: Pid implements Serializable for use with stateful actors.
 * The ActorSystem reference is not serialized and will be null after deserialization.
 * This is acceptable for stateful actor persistence where Pids are used as message addresses.
 */
public record Pid(String actorId, ActorSystem system) implements Serializable {
    
    // Serialization proxy pattern for records
    @Serial
    private Object writeReplace() {
        return new SerializationProxy(this);
    }
    
    @Serial
    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
        throw new InvalidObjectException("Proxy required");
    }
    
    // Serialization proxy that only serializes the actorId
    private static class SerializationProxy implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        
        private final String actorId;
        
        SerializationProxy(Pid pid) {
            this.actorId = pid.actorId;
        }
        
        @Serial
        private Object readResolve() {
            // Reconstruct Pid with null ActorSystem after deserialization
            // This is acceptable for persistence scenarios where Pids are message addresses
            return new Pid(actorId, null);
        }
    }

    /**
     * Sends a message to the actor.
     * If the actor is local, the message is delivered directly.
     * If the actor is remote (in cluster mode), the message is routed to the appropriate node.
     *
     * @param message The message to send
     * @param <Message> The type of the message
     */
    public <Message> void tell(Message message) {
        system.routeMessage(actorId, message);
    }

    /**
     * Sends a message to the actor with a specific delivery guarantee.
     * This is only applicable in cluster mode with a ReliableMessagingSystem.
     * In local mode or with other messaging systems, this behaves the same as tell(message).
     *
     * @param message The message to send
     * @param deliveryGuarantee The delivery guarantee to use
     * @param <Message> The type of the message
     */
    public <Message> void tell(Message message, DeliveryGuarantee deliveryGuarantee) {
        if (system instanceof ClusterActorSystem) {
            ((ClusterActorSystem) system).routeMessage(actorId, message, deliveryGuarantee);
        } else {
            // Fall back to standard delivery for non-cluster systems
            system.routeMessage(actorId, message);
        }
    }

    /**
     * Sends a message to the actor with a delay.
     * The message will be delivered after the specified delay.
     *
     * @param message The message to send
     * @param delay The delay amount
     * @param timeUnit The time unit for the delay
     * @param <Message> The type of the message
     */
    public <Message> void tell(Message message, long delay, TimeUnit timeUnit) {
        system.routeMessage(actorId, message, delay, timeUnit);
    }
    
    /**
     * Sends a message to the actor with a delay and a specific delivery guarantee.
     * This is only applicable in cluster mode with a ReliableMessagingSystem.
     *
     * @param message The message to send
     * @param delay The delay amount
     * @param timeUnit The time unit for the delay
     * @param deliveryGuarantee The delivery guarantee to use
     * @param <Message> The type of the message
     */
    public <Message> void tell(Message message, long delay, TimeUnit timeUnit, DeliveryGuarantee deliveryGuarantee) {
        if (system instanceof ClusterActorSystem) {
            ((ClusterActorSystem) system).routeMessage(actorId, message, delay, timeUnit, deliveryGuarantee);
        } else {
            // Fall back to standard delivery for non-cluster systems
            system.routeMessage(actorId, message, delay, timeUnit);
        }
    }
    
    /**
     * Returns a string representation of this Pid.
     *
     * @return A string in the format "actorId@systemId"
     */
    @Override
    public String toString() {
        String systemId = "local";
        if (system != null && system.getClass().getName().contains("Cluster")) {
            try {
                // Use reflection to get the system ID from ClusterActorSystem
                systemId = (String) system.getClass().getMethod("getSystemId").invoke(system);
            } catch (Exception e) {
                // Fall back to local if we can't get the system ID
            }
        }
        return actorId + "@" + systemId;
    }
    
    /**
     * Sends a message to a stateful actor by automatically adapting it to an OperationAwareMessage.
     * This allows stateless actors to send regular messages to stateful actors without
     * requiring the original messages to implement OperationAwareMessage.
     * 
     * By default, this method treats the message as a write operation (isReadOnly = false).
     * If you know the message is read-only, use tellReadOnly instead.
     *
     * @param <T> The type of the original message
     * @param message The message to send
     */
    public <T extends Serializable> void tellStateful(T message) {
        MessageAdapter<T> adapter = MessageAdapter.writeOp(message);
        tell(adapter);
    }
    
    /**
     * Sends a message to a stateful actor as a read-only operation by automatically adapting it
     * to an OperationAwareMessage with isReadOnly = true.
     * This allows stateless actors to send regular messages to stateful actors without
     * requiring the original messages to implement OperationAwareMessage.
     *
     * @param <T> The type of the original message
     * @param message The message to send as a read-only operation
     */
    public <T extends Serializable> void tellReadOnly(T message) {
        MessageAdapter<T> adapter = MessageAdapter.readOnly(message);
        tell(adapter);
    }
    
    /**
     * Sends a message to a stateful actor with a delay by automatically adapting it
     * to an OperationAwareMessage.
     * 
     * @param <T> The type of the original message
     * @param message The message to send
     * @param delay The delay amount
     * @param timeUnit The time unit for the delay
     */
    public <T extends Serializable> void tellStateful(T message, long delay, TimeUnit timeUnit) {
        MessageAdapter<T> adapter = MessageAdapter.writeOp(message);
        tell(adapter, delay, timeUnit);
    }
    
    /**
     * Sends a message to a stateful actor as a read-only operation with a delay.
     * 
     * @param <T> The type of the original message
     * @param message The message to send as a read-only operation
     * @param delay The delay amount
     * @param timeUnit The time unit for the delay
     */
    public <T extends Serializable> void tellReadOnly(T message, long delay, TimeUnit timeUnit) {
        MessageAdapter<T> adapter = MessageAdapter.readOnly(message);
        tell(adapter, delay, timeUnit);
    }
}
