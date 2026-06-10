package com.cajunsystems.handler;

import com.cajunsystems.ActorContext;

import java.util.function.BiFunction;

/**
 * Adapter class that converts functional-style message handling to the Handler interface.
 * This provides backward compatibility with the functional approach while using the new interface-based system.
 *
 * @param <Message> The type of messages this handler processes
 */
public class FunctionalHandlerAdapter<Message> implements Handler<Message> {
    
    private final BiFunction<Message, ActorContext, Void> messageHandler;
    
    /**
     * Creates a new adapter with the specified message handler function.
     *
     * @param messageHandler The function that handles messages
     */
    public FunctionalHandlerAdapter(BiFunction<Message, ActorContext, Void> messageHandler) {
        this.messageHandler = messageHandler;
    }
    
    @Override
    public void receive(Message message, ActorContext context) {
        messageHandler.apply(message, context);
    }
    
    /**
     * Creates a new handler adapter from a message handling function.
     *
     * @param <M> The type of messages the handler processes
     * @param messageHandler The function that handles messages
     * @return A new handler adapter
     */
    public static <M> FunctionalHandlerAdapter<M> of(BiFunction<M, ActorContext, Void> messageHandler) {
        return new FunctionalHandlerAdapter<>(messageHandler);
    }
}
