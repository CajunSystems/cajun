package com.cajunsystems.spring;

import com.cajunsystems.Pid;
import com.cajunsystems.Reply;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A type-safe reference to a Cajun actor, wrapping a {@link Pid}.
 *
 * <p>{@code ActorRef<Message>} ties the actor's message type to the reference, giving compile-time
 * safety when sending messages. Use it wherever you would otherwise hold a raw {@link Pid}.
 *
 * <p>Instances are returned by {@link CajunActorRegistry#getActorRef(Class)} and can also be
 * injected directly via the {@code @InjectActor} annotation on fields of type {@code ActorRef}.
 *
 * <pre>{@code
 * // Obtain via registry
 * ActorRef<OrderMessage> ref = registry.getActorRef(OrderHandler.class);
 *
 * // Or via field injection
 * @InjectActor(OrderHandler.class)
 * private ActorRef<OrderMessage> orderActor;
 *
 * // Send a message
 * orderActor.tell(new OrderMessage.Place(orderId));
 *
 * // Ask pattern (request-response)
 * OrderMessage.Status status = orderActor
 *     .ask(new OrderMessage.GetStatus(orderId), Duration.ofSeconds(5))
 *     .get();
 * }</pre>
 *
 * @param <Message> the message type accepted by the referenced actor
 */
public final class ActorRef<Message> {

    private final Pid pid;

    /**
     * Creates an {@code ActorRef} wrapping the given {@link Pid}.
     *
     * @param pid the underlying actor process identifier; must not be {@code null}
     */
    public ActorRef(Pid pid) {
        this.pid = Objects.requireNonNull(pid, "pid must not be null");
    }

    /**
     * Sends a message to the actor (fire-and-forget).
     *
     * @param message the message to send
     */
    public void tell(Message message) {
        pid.tell(message);
    }

    /**
     * Sends a message to the actor with a delivery delay.
     *
     * @param message  the message to send
     * @param delay    the delay amount
     * @param timeUnit the time unit of the delay
     */
    public void tell(Message message, long delay, TimeUnit timeUnit) {
        pid.tell(message, delay, timeUnit);
    }

    /**
     * Sends a request to the actor and returns a {@link Reply} that completes when the actor
     * responds.
     *
     * @param message  the request message
     * @param timeout  maximum time to wait for a response
     * @param <Response> the expected response type
     * @return a {@link Reply} that can be awaited synchronously or asynchronously
     */
    public <Response> Reply<Response> ask(Message message, Duration timeout) {
        return pid.ask(message, timeout);
    }

    /**
     * Returns the underlying {@link Pid} for use with lower-level Cajun APIs.
     *
     * @return the wrapped {@link Pid}
     */
    public Pid getPid() {
        return pid;
    }

    /**
     * Returns the actor's string identifier.
     *
     * @return the actor ID
     */
    public String getActorId() {
        return pid.actorId();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ActorRef<?> other)) return false;
        return Objects.equals(pid, other.pid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pid);
    }

    @Override
    public String toString() {
        return "ActorRef[" + pid.actorId() + "]";
    }
}
