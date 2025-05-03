package systems.cajun;

import java.util.concurrent.TimeUnit;

/**
 * Process ID (Pid) for an actor, used to send messages to the actor.
 * This class handles both local and remote actor references.
 */
public record Pid(String actorId, ActorSystem system) {

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
}
