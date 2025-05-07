package systems.cajun.persistence;

import java.io.Serializable;

/**
 * Interface for messages that are aware of their operation type (read or write).
 * This allows the StatefulActor to optimize journal persistence by only storing
 * write operations, which affect the actor's state.
 */
public interface OperationAwareMessage extends Serializable {
    
    /**
     * Determines if this message is a read operation (does not modify state)
     * or a write operation (modifies state).
     * 
     * @return true if this is a read-only operation, false if it's a write operation
     */
    boolean isReadOnly();
}
