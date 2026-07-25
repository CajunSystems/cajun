package com.cajunsystems.testkit;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.ReplyingMessage;
import com.cajunsystems.builder.ActorBuilder;
import com.cajunsystems.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * A lightweight, dependency-free {@link ActorContext} test double for unit-testing {@code Handler}
 * and {@code StatefulHandler} implementations without spinning up an {@link ActorSystem}.
 * <p>
 * It records the outbound interactions a handler performs — {@link #tell}, {@link #tellSelf},
 * {@link #reply}, and {@link #forward} — so tests can assert on them directly instead of stubbing
 * the entire {@link ActorContext} surface by hand. The {@link #getSender() sender} and
 * {@link #getParent() parent} can be pre-seeded so handlers that reply to the ask pattern or read
 * their parent are testable in isolation.
 *
 * <p>Typical usage:
 * <pre>{@code
 * TestActorContext ctx = new TestActorContext("my-actor");
 * ctx.setSender(new Pid("caller", null));
 *
 * new MyHandler().receive(new Ping(), ctx);
 *
 * assertEquals(1, ctx.getTells().size());
 * assertEquals("pong", ctx.getTells().get(0).message());
 * }</pre>
 *
 * <p>This class is thread-safe for recording, but it is intended for single-threaded unit tests.
 * Methods that require a live actor system ({@link #createChild}, {@link #childBuilder},
 * {@link #getSystem}) are not supported and throw {@link UnsupportedOperationException}; use a real
 * {@link ActorSystem} for tests that exercise child creation.
 */
public class TestActorContext implements ActorContext {

    /** A message recorded as sent to a specific target actor. */
    public record Message(Pid target, Object message) {}

    /** A reply recorded against the originating request. */
    public record Reply(ReplyingMessage request, Object response) {}

    /** A delayed self-message recorded with its scheduling delay (delay is zero for the immediate overload). */
    public record SelfMessage(Object message, long delay, TimeUnit timeUnit) {}

    private final String actorId;
    private final Pid self;
    private final Logger logger;

    private volatile Optional<Pid> sender = Optional.empty();
    private volatile Pid parent;

    private final List<Message> tells = new CopyOnWriteArrayList<>();
    private final List<SelfMessage> selfTells = new CopyOnWriteArrayList<>();
    private final List<Reply> replies = new CopyOnWriteArrayList<>();
    private final List<Message> forwards = new CopyOnWriteArrayList<>();
    private final Map<String, Pid> children = new ConcurrentHashMap<>();

    private volatile boolean stopped = false;

    /**
     * Creates a test context for an actor with the given id. The {@link #self()} PID is created
     * with a {@code null} system, which is fine for recording-based assertions.
     *
     * @param actorId The id this context reports for the actor under test
     */
    public TestActorContext(String actorId) {
        this.actorId = actorId;
        this.self = new Pid(actorId, null);
        this.logger = LoggerFactory.getLogger(TestActorContext.class.getName() + "." + actorId);
    }

    // --- Pre-seeding helpers -------------------------------------------------

    /** Sets the sender returned by {@link #getSender()}. */
    public TestActorContext setSender(Pid sender) {
        this.sender = Optional.ofNullable(sender);
        return this;
    }

    /** Sets the parent returned by {@link #getParent()}. */
    public TestActorContext setParent(Pid parent) {
        this.parent = parent;
        return this;
    }

    /** Registers a child PID visible via {@link #getChildren()}. */
    public TestActorContext putChild(String childId, Pid childPid) {
        this.children.put(childId, childPid);
        return this;
    }

    // --- Recorded-interaction accessors -------------------------------------

    /** Returns the messages sent via {@link #tell(Pid, Object)}, in order. */
    public List<Message> getTells() {
        return Collections.unmodifiableList(new ArrayList<>(tells));
    }

    /** Returns the messages sent via {@link #tellSelf}, in order. */
    public List<SelfMessage> getSelfTells() {
        return Collections.unmodifiableList(new ArrayList<>(selfTells));
    }

    /** Returns the replies sent via {@link #reply(ReplyingMessage, Object)}, in order. */
    public List<Reply> getReplies() {
        return Collections.unmodifiableList(new ArrayList<>(replies));
    }

    /** Returns the messages forwarded via {@link #forward(Pid, Object)}, in order. */
    public List<Message> getForwards() {
        return Collections.unmodifiableList(new ArrayList<>(forwards));
    }

    /** Returns true if {@link #stop()} was called. */
    public boolean isStopped() {
        return stopped;
    }

    /** Clears all recorded interactions (sender/parent/children are retained). */
    public void clearRecorded() {
        tells.clear();
        selfTells.clear();
        replies.clear();
        forwards.clear();
    }

    // --- ActorContext implementation ----------------------------------------

    @Override
    public Pid self() {
        return self;
    }

    @Override
    public String getActorId() {
        return actorId;
    }

    @Override
    public <T> void tell(Pid target, T message) {
        tells.add(new Message(target, message));
    }

    @Override
    public <T> void reply(ReplyingMessage request, T response) {
        replies.add(new Reply(request, response));
        if (request != null && request.replyTo() != null) {
            tells.add(new Message(request.replyTo(), response));
        }
    }

    @Override
    public <T> void tellSelf(T message, long delay, TimeUnit timeUnit) {
        selfTells.add(new SelfMessage(message, delay, timeUnit));
    }

    @Override
    public <T> void tellSelf(T message) {
        selfTells.add(new SelfMessage(message, 0L, TimeUnit.MILLISECONDS));
    }

    @Override
    public <Message> ActorBuilder<Message> childBuilder(Class<? extends Handler<Message>> handlerClass) {
        throw new UnsupportedOperationException(
                "TestActorContext cannot create children; use a real ActorSystem for child-creation tests");
    }

    @Override
    public <T> Pid createChild(Class<?> handlerClass, String childId) {
        throw new UnsupportedOperationException(
                "TestActorContext cannot create children; use a real ActorSystem for child-creation tests");
    }

    @Override
    public <T> Pid createChild(Class<?> handlerClass) {
        throw new UnsupportedOperationException(
                "TestActorContext cannot create children; use a real ActorSystem for child-creation tests");
    }

    @Override
    public Pid getParent() {
        return parent;
    }

    @Override
    public Map<String, Pid> getChildren() {
        return Collections.unmodifiableMap(new java.util.HashMap<>(children));
    }

    @Override
    public ActorSystem getSystem() {
        throw new UnsupportedOperationException(
                "TestActorContext has no ActorSystem; use a real ActorSystem if the handler needs it");
    }

    @Override
    public void stop() {
        stopped = true;
    }

    @Override
    public Optional<Pid> getSender() {
        return sender;
    }

    @Override
    public <T> void forward(Pid target, T message) {
        forwards.add(new Message(target, message));
    }

    @Override
    public Logger getLogger() {
        return logger;
    }
}
