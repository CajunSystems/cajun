package com.cajunsystems.functional;

import com.cajunsystems.ActorContext;

import java.util.function.Function;

/**
 * Stack-safe implementation of Effect using trampolining.
 *
 * <p>This implementation prevents stack overflow when chaining many
 * map/flatMap operations by building up a data structure and evaluating
 * it iteratively instead of recursively.
 *
 * <p>Internal implementation detail - users should use the Effect interface.
 *
 * @param <State> The actor state type
 * @param <Message> The message type
 * @param <Result> The result type
 */
final class TrampolinedEffect<State, Message, Result> implements Effect<State, Message, Result> {

    private final EffectTrampoline<State, Message, Result> trampoline;

    /**
     * Creates a trampolined effect from a base effect.
     */
    TrampolinedEffect(Effect<State, Message, Result> baseEffect) {
        this.trampoline = new EffectTrampoline.Pure<>(baseEffect);
    }

    /**
     * Creates a trampolined effect from a trampoline structure.
     */
    private TrampolinedEffect(EffectTrampoline<State, Message, Result> trampoline) {
        this.trampoline = trampoline;
    }

    @Override
    public EffectResult<State, Result> run(State state, Message message, ActorContext context) {
        return EffectTrampoline.evaluate(trampoline, state, message, context);
    }

    /**
     * Stack-safe map implementation.
     * Builds a Map trampoline instead of creating nested lambdas.
     */
    @Override
    public <R2> Effect<State, Message, R2> map(Function<Result, R2> f) {
        return new TrampolinedEffect<>(
            new EffectTrampoline.Map<>(this.trampoline, f)
        );
    }

    /**
     * Stack-safe flatMap implementation.
     * Builds a FlatMap trampoline instead of creating nested lambdas.
     */
    @Override
    public <R2> Effect<State, Message, R2> flatMap(Function<Result, Effect<State, Message, R2>> f) {
        return new TrampolinedEffect<>(
            new EffectTrampoline.FlatMap<>(this.trampoline, f)
        );
    }

    /**
     * Factory method to create a trampolined effect from a base effect.
     */
    static <S, M, R> Effect<S, M, R> trampoline(Effect<S, M, R> effect) {
        if (effect instanceof TrampolinedEffect) {
            return effect; // Already trampolined
        }
        return new TrampolinedEffect<>(effect);
    }
}
