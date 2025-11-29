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
final class TrampolinedEffect<State, Error, Result> implements Effect<State, Error, Result> {

    private final EffectTrampoline<State, Error, Result> trampoline;

    /**
     * Creates a trampolined effect from a base effect.
     */
    TrampolinedEffect(Effect<State, Error, Result> baseEffect) {
        this.trampoline = new EffectTrampoline.Pure<>(baseEffect);
    }

    /**
     * Creates a trampolined effect from a trampoline structure.
     */
    private TrampolinedEffect(EffectTrampoline<State, Error, Result> trampoline) {
        this.trampoline = trampoline;
    }

    @Override
    public EffectResult<State, Result> run(State state, Object message, ActorContext context) {
        return EffectTrampoline.evaluate(trampoline, state, message, context);
    }

    /**
     * Stack-safe map implementation.
     * Builds a Map trampoline instead of creating nested lambdas.
     */
    @Override
    public <R2> Effect<State, Error, R2> map(Function<Result, R2> f) {
        return new TrampolinedEffect<>(
            new EffectTrampoline.Map<>(this.trampoline, f)
        );
    }

    /**
     * Stack-safe flatMap implementation.
     * Builds a FlatMap trampoline instead of creating nested lambdas.
     */
    @Override
    public <R2> Effect<State, Error, R2> flatMap(Function<Result, Effect<State, Error, R2>> f) {
        return new TrampolinedEffect<>(
            new EffectTrampoline.FlatMap<>(this.trampoline, f)
        );
    }

    /**
     * Factory method to create a trampolined effect from a base effect.
     */
    static <S, E, R> Effect<S, E, R> trampoline(Effect<S, E, R> effect) {
        if (effect instanceof TrampolinedEffect) {
            return effect; // Already trampolined
        }
        return new TrampolinedEffect<>(effect);
    }
}
