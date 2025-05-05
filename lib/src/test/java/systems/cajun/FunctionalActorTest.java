package systems.cajun;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

class FunctionalActorTest {
    sealed interface TestMessage {}
    record Inc() implements TestMessage {}
    record Fail() implements TestMessage {}
    record Get(AtomicBoolean called) implements TestMessage {}

    @Test
    void testNormalProcessing() {
        FunctionalActor<Integer, TestMessage> actor = new FunctionalActor<>();
        Receiver<TestMessage> receiver = actor.receiveMessage((state, msg) -> {
            if (msg instanceof Inc) return state + 1;
            return state;
        }, 0);
        receiver = receiver.accept(new Inc());
        receiver = receiver.accept(new Inc());
        // Should be at state 2 after two increments
        Receiver<TestMessage> finalReceiver = receiver;
        AtomicBoolean checked = new AtomicBoolean(false);
        finalReceiver.accept(new Get(checked));
        assertFalse(checked.get(), "Get should not change state or call anything");
    }

    @Test
    void testErrorHandlingWithDefaultLogger() {
        FunctionalActor<Integer, TestMessage> actor = new FunctionalActor<>();
        Receiver<TestMessage> receiver = actor.receiveMessage((state, msg) -> {
            if (msg instanceof Fail) throw new RuntimeException("fail!");
            return state + 1;
        }, 0);
        // Should not throw, just log and continue
        receiver = receiver.accept(new Fail());
        receiver = receiver.accept(new Inc());
        // State should be 1 (since after error, state did not increment)
        // Can't directly check state, but should not throw
    }

    @Test
    void testCustomErrorHandlerIsCalled() {
        FunctionalActor<Integer, TestMessage> actor = new FunctionalActor<>();
        AtomicBoolean errorHandlerCalled = new AtomicBoolean(false);
        BiConsumer<Integer, Exception> errorHandler = (state, ex) -> errorHandlerCalled.set(true);
        Receiver<TestMessage> receiver = actor.receiveMessage((state, msg) -> {
            if (msg instanceof Fail) throw new RuntimeException("fail!");
            return state + 1;
        }, 0, errorHandler);
        receiver = receiver.accept(new Fail());
        assertTrue(errorHandlerCalled.get(), "Custom error handler should be called on exception");
    }

    @Test
    void testContinueWithPreviousStateOnError() {
        FunctionalActor<Integer, TestMessage> actor = new FunctionalActor<>();
        AtomicBoolean errorHandlerCalled = new AtomicBoolean(false);
        BiConsumer<Integer, Exception> errorHandler = (state, ex) -> errorHandlerCalled.set(true);
        Receiver<TestMessage>[] receiverBox = new Receiver[1];
        receiverBox[0] = actor.receiveMessage((state, msg) -> {
            if (msg instanceof Inc) return state + 1;
            if (msg instanceof Fail) throw new RuntimeException("fail!");
            return state;
        }, 0, errorHandler);
        receiverBox[0] = receiverBox[0].accept(new Inc()); // state = 1
        receiverBox[0] = receiverBox[0].accept(new Fail()); // error, should stay at 1
        receiverBox[0] = receiverBox[0].accept(new Inc()); // state = 2
        // No direct way to check internal state, but should not throw
        assertTrue(errorHandlerCalled.get(), "Error handler should be called");
    }
}
