package com.cajunsystems.spring;

import com.cajunsystems.Pid;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Spring-managed registry that tracks all actors spawned through the Cajun Spring integration.
 *
 * <p>Actors annotated with {@code @ActorComponent} are automatically registered here after
 * being spawned. You can also register actors manually after spawning them via
 * {@link com.cajunsystems.ActorSystem}.
 *
 * <p>Inject this bean to look up {@link Pid}s or {@link ActorRef}s by handler class or actor ID:
 *
 * <pre>{@code
 * @Autowired
 * private CajunActorRegistry registry;
 *
 * // Look up by handler class (registered via @ActorComponent)
 * Pid pid = registry.getByHandlerClass(OrderHandler.class);
 *
 * // Type-safe ActorRef
 * ActorRef<OrderMessage> ref = registry.getActorRef(OrderHandler.class);
 *
 * // Look up by explicit actor ID
 * Pid pid = registry.getByActorId("order-processor");
 * }</pre>
 */
public class CajunActorRegistry {

    private final Map<Class<?>, Pid> byHandlerClass = new ConcurrentHashMap<>();
    private final Map<String, Pid> byActorId = new ConcurrentHashMap<>();

    /**
     * Registers a spawned actor in this registry.
     *
     * @param handlerClass the {@code Handler} or {@code StatefulHandler} implementation class
     * @param actorId      the actor's string identifier as assigned by the actor system
     * @param pid          the {@link Pid} returned by {@code ActorBuilder.spawn()}
     */
    public void register(Class<?> handlerClass, String actorId, Pid pid) {
        byHandlerClass.put(handlerClass, pid);
        byActorId.put(actorId, pid);
    }

    /**
     * Returns the {@link Pid} for the actor backed by the given handler class, if present.
     *
     * @param handlerClass the handler implementation class
     * @return an {@link Optional} containing the {@link Pid}, or empty if not registered
     */
    public Optional<Pid> findByHandlerClass(Class<?> handlerClass) {
        return Optional.ofNullable(byHandlerClass.get(handlerClass));
    }

    /**
     * Returns the {@link Pid} for the actor backed by the given handler class.
     *
     * @param handlerClass the handler implementation class
     * @return the {@link Pid}
     * @throws IllegalArgumentException if no actor is registered for that handler class
     */
    public Pid getByHandlerClass(Class<?> handlerClass) {
        return findByHandlerClass(handlerClass)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No actor registered for handler class: " + handlerClass.getName()
                                + ". Make sure the handler is annotated with @ActorComponent "
                                + "or registered manually via CajunActorRegistry.register()."));
    }

    /**
     * Returns a type-safe {@link ActorRef} for the actor backed by the given handler class.
     *
     * @param handlerClass the handler implementation class
     * @param <Message>    the message type inferred from the handler
     * @return a new {@link ActorRef} wrapping the registered {@link Pid}
     * @throws IllegalArgumentException if no actor is registered for that handler class
     */
    @SuppressWarnings("unchecked")
    public <Message> ActorRef<Message> getActorRef(Class<?> handlerClass) {
        return new ActorRef<>(getByHandlerClass(handlerClass));
    }

    /**
     * Returns the {@link Pid} for the actor with the given ID, if present.
     *
     * @param actorId the actor's string identifier
     * @return an {@link Optional} containing the {@link Pid}, or empty if not registered
     */
    public Optional<Pid> findByActorId(String actorId) {
        return Optional.ofNullable(byActorId.get(actorId));
    }

    /**
     * Returns the {@link Pid} for the actor with the given ID.
     *
     * @param actorId the actor's string identifier
     * @return the {@link Pid}
     * @throws IllegalArgumentException if no actor is registered with that ID
     */
    public Pid getByActorId(String actorId) {
        return findByActorId(actorId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No actor registered with ID: '" + actorId + "'. "
                                + "Make sure the actor has been spawned before looking it up."));
    }

    /**
     * Returns a type-safe {@link ActorRef} for the actor with the given ID.
     *
     * @param actorId   the actor's string identifier
     * @param <Message> the expected message type
     * @return a new {@link ActorRef} wrapping the registered {@link Pid}
     * @throws IllegalArgumentException if no actor is registered with that ID
     */
    @SuppressWarnings("unchecked")
    public <Message> ActorRef<Message> getActorRef(String actorId) {
        return new ActorRef<>(getByActorId(actorId));
    }

    /**
     * Returns an unmodifiable snapshot of all registered actors keyed by actor ID.
     *
     * @return map of actor ID to {@link Pid}
     */
    public Map<String, Pid> getAllActors() {
        return Collections.unmodifiableMap(byActorId);
    }

    /**
     * Returns the number of registered actors.
     *
     * @return the count of registered actors
     */
    public int size() {
        return byActorId.size();
    }
}
