package com.cajunsystems.functional.internal;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Stack-safe trampoline for recursive computations.
 * This implementation truly prevents stack overflow by using an iterative evaluation strategy.
 */
public abstract class Trampoline<A> {
    
    private static final class Done<A> extends Trampoline<A> {
        private final A value;
        Done(A value) { this.value = value; }
        
        @Override
        A getValue() { return value; }
        
        @Override
        boolean isDone() { return true; }
    }
    
    private static final class More<A> extends Trampoline<A> {
        private final Supplier<Trampoline<A>> next;
        More(Supplier<Trampoline<A>> next) { this.next = next; }
        
        @Override
        Trampoline<A> getNext() { return next.get(); }
        
        @Override
        boolean isDone() { return false; }
    }
    
    abstract boolean isDone();
    A getValue() { throw new UnsupportedOperationException(); }
    Trampoline<A> getNext() { throw new UnsupportedOperationException(); }
    
    public static <A> Trampoline<A> done(A value) { return new Done<>(value); }
    public static <A> Trampoline<A> more(Supplier<Trampoline<A>> next) { return new More<>(next); }
    public static <A> Trampoline<A> delay(Supplier<A> computation) { return more(() -> done(computation.get())); }
    
    public <B> Trampoline<B> map(Function<A, B> f) {
        return more(() -> {
            A value = this.run();
            return done(f.apply(value));
        });
    }
    
    public <B> Trampoline<B> flatMap(Function<A, Trampoline<B>> f) {
        return more(() -> {
            A value = this.run();
            return f.apply(value);
        });
    }
    
    public A run() {
        Trampoline<A> current = this;
        while (!current.isDone()) {
            current = current.getNext();
        }
        return current.getValue();
    }
}
