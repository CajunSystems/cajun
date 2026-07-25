package com.cajunsystems.testkit;

import com.cajunsystems.ActorContext;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.Handler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestActorContextTest {

    /** A handler that echoes back to the sender and pings itself. */
    static class EchoHandler implements Handler<String> {
        @Override
        public void receive(String message, ActorContext context) {
            context.getSender().ifPresent(sender -> context.tell(sender, "echo:" + message));
            context.tellSelf("tick");
        }
    }

    @Test
    void recordsTellsSelfTellsAndSender() {
        TestActorContext ctx = new TestActorContext("echo");
        Pid caller = new Pid("caller", null);
        ctx.setSender(caller);

        new EchoHandler().receive("hi", ctx);

        assertEquals(1, ctx.getTells().size(), "one tell to the sender expected");
        assertEquals(caller, ctx.getTells().get(0).target());
        assertEquals("echo:hi", ctx.getTells().get(0).message());

        assertEquals(1, ctx.getSelfTells().size(), "one self-tell expected");
        assertEquals("tick", ctx.getSelfTells().get(0).message());
    }

    @Test
    void recordsStopAndClear() {
        TestActorContext ctx = new TestActorContext("a");
        ctx.setSender(new Pid("s", null));
        new EchoHandler().receive("x", ctx);
        assertTrue(ctx.getTells().size() >= 1);

        ctx.clearRecorded();
        assertEquals(0, ctx.getTells().size());
        assertEquals(0, ctx.getSelfTells().size());

        ctx.stop();
        assertTrue(ctx.isStopped());
    }
}
