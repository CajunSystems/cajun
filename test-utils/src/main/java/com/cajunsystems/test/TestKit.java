package com.cajunsystems.test;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.handler.StatefulHandler;

/**
 * Main entry point for testing Cajun actors.
 * Provides utilities for spawning test actors and creating test probes.
 * 
 * <p>Usage:
 * <pre>{@code
 * try (TestKit testKit = TestKit.create()) {
 *     TestPid<String> actor = testKit.spawn(MyHandler.class);
 *     TestProbe<String> probe = testKit.createProbe();
 *     
 *     actor.tell("hello");
 *     probe.expectMessage(Duration.ofSeconds(1));
 * }
 * }</pre>
 */
public class TestKit implements AutoCloseable {
    
    private final ActorSystem system;
    private final boolean ownsSystem;
    
    private TestKit(ActorSystem system, boolean ownsSystem) {
        this.system = system;
        this.ownsSystem = ownsSystem;
    }
    
    /**
     * Creates a new TestKit with its own ActorSystem.
     * The ActorSystem will be automatically shut down when the TestKit is closed.
     */
    public static TestKit create() {
        return new TestKit(new ActorSystem(), true);
    }
    
    /**
     * Creates a new TestKit using an existing ActorSystem.
     * The ActorSystem will NOT be shut down when the TestKit is closed.
     * 
     * @param system the ActorSystem to use
     */
    public static TestKit create(ActorSystem system) {
        return new TestKit(system, false);
    }
    
    /**
     * Gets the underlying ActorSystem.
     */
    public ActorSystem system() {
        return system;
    }
    
    /**
     * Spawns an actor with test instrumentation.
     * 
     * @param handlerClass the handler class to instantiate
     * @param <T> the message type
     * @return a TestPid wrapping the spawned actor
     */
    public <T> TestPid<T> spawn(Class<? extends Handler<T>> handlerClass) {
        Pid pid = system.actorOf(handlerClass).spawn();
        return new TestPid<>(pid, system);
    }
    
    /**
     * Spawns an actor with test instrumentation using a handler instance.
     * 
     * @param handler the handler instance
     * @param <T> the message type
     * @return a TestPid wrapping the spawned actor
     */
    public <T> TestPid<T> spawn(Handler<T> handler) {
        Pid pid = system.actorOf(handler).spawn();
        return new TestPid<>(pid, system);
    }
    
    /**
     * Spawns a stateful actor with test instrumentation.
     * 
     * @param handlerClass the stateful handler class to instantiate
     * @param initialState the initial state
     * @param <S> the state type
     * @param <T> the message type
     * @return a TestPid wrapping the spawned actor
     */
    public <S, T> TestPid<T> spawnStateful(
            Class<? extends StatefulHandler<S, T>> handlerClass, 
            S initialState) {
        Pid pid = system.statefulActorOf(handlerClass, initialState).spawn();
        return new TestPid<>(pid, system);
    }
    
    /**
     * Spawns a stateful actor with test instrumentation using a handler instance.
     * 
     * @param handler the stateful handler instance
     * @param initialState the initial state
     * @param <S> the state type
     * @param <T> the message type
     * @return a TestPid wrapping the spawned actor
     */
    public <S, T> TestPid<T> spawnStateful(
            StatefulHandler<S, T> handler, 
            S initialState) {
        Pid pid = system.statefulActorOf(handler, initialState).spawn();
        return new TestPid<>(pid, system);
    }
    
    /**
     * Creates a test probe for capturing and asserting on messages.
     * 
     * @param <T> the message type
     * @return a new TestProbe
     */
    public <T> TestProbe<T> createProbe() {
        return TestProbe.create(system);
    }
    
    /**
     * Creates a test probe with a specific name for debugging.
     * 
     * @param name the name for the probe actor
     * @param <T> the message type
     * @return a new TestProbe
     */
    public <T> TestProbe<T> createProbe(String name) {
        return TestProbe.create(system, name);
    }
    
    /**
     * Shuts down the ActorSystem if this TestKit owns it.
     */
    @Override
    public void close() {
        if (ownsSystem) {
            system.shutdown();
        }
    }
}
