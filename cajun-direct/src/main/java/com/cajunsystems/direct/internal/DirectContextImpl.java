package com.cajunsystems.direct.internal;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.direct.DirectContext;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Internal implementation of {@link DirectContext} that delegates to {@link ActorContext}.
 */
public class DirectContextImpl implements DirectContext {

    private final ActorContext underlying;

    public DirectContextImpl(ActorContext underlying) {
        this.underlying = underlying;
    }

    @Override
    public Pid self() {
        return underlying.self();
    }

    @Override
    public String getActorId() {
        return underlying.getActorId();
    }

    @Override
    public <T> void tell(Pid target, T message) {
        underlying.tell(target, message);
    }

    @Override
    public <T> void tellSelf(T message, long delay, TimeUnit timeUnit) {
        underlying.tellSelf(message, delay, timeUnit);
    }

    @Override
    public <T> void tellSelf(T message) {
        underlying.tellSelf(message);
    }

    @Override
    public Pid getParent() {
        return underlying.getParent();
    }

    @Override
    public Map<String, Pid> getChildren() {
        return underlying.getChildren();
    }

    @Override
    public ActorSystem getSystem() {
        return underlying.getSystem();
    }

    @Override
    public void stop() {
        underlying.stop();
    }

    @Override
    public Logger getLogger() {
        return underlying.getLogger();
    }

    @Override
    public ActorContext underlying() {
        return underlying;
    }
}
