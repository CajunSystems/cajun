///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.cajunsystems:cajun:0.3.0
//JAVA 21+
//PREVIEW

package examples;


import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.handler.Handler;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TimedCounter {

    public sealed interface CountProtocol {

        record CountUp() implements CountProtocol {
        }
        
        record CountDown() implements CountProtocol {
        }
    }

    public static class CountReceiver implements Handler<CountProtocol> {

        private int count = 0;

        @Override
        public void receive(CountProtocol countMessage, ActorContext context) {
            switch (countMessage) {
                case CountProtocol.CountUp ignored -> {
                    count++;
                    System.out.println(STR."Current Count: \{count}");
                }
                case CountProtocol.CountDown ignored -> {
                    count--;
                    System.out.println(STR."Current Count: \{count}");
                }
            }
        }
    }

    private final ScheduledExecutorService scheduler;

    public TimedCounter() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    public void startPeriodicTask(Runnable task) {
        scheduler.scheduleAtFixedRate(task, 0, 5, TimeUnit.SECONDS);
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        var actorSystem = new ActorSystem();
        
        // Use the new interface-based approach to create the actor
        var receiverActor = actorSystem.actorOf(new Handler<CountProtocol>() {
            private int count = 0;
            
            @Override
            public void receive(CountProtocol message, ActorContext context) {
                if (message instanceof CountProtocol.CountUp) {
                    count++;
                    System.out.println(STR."Count: \{count}");
                } else if (message instanceof CountProtocol.CountDown) {
                    count--;
                    System.out.println(STR."Count: \{count}");
                }
            }
        }).withId("count-receiver").spawn();
        
        receiverActor.tell(new CountProtocol.CountUp()); // Count: 1
        var runner = new TimedCounter();
        runner.startPeriodicTask(() -> {
            receiverActor.tell(new CountProtocol.CountUp()); // Count: 2, 3, 4, etc.
        });
    }
}
