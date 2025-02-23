package examples;

import systems.cajun.Actor;
import systems.cajun.ActorSystem;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TimedCounter {

    public sealed interface CountProtocol {

        record CountUp() implements CountProtocol {
        }
    }

    public static class CountReceiver extends Actor<CountProtocol> {

        private int count = 0;

        public CountReceiver(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        @Override
        protected void receive(CountProtocol countMessage) {
            switch (countMessage) {
                case CountProtocol.CountUp ignored -> {
                    count++;
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
        var receiverActor = actorSystem.register(CountReceiver.class, "count-receiver");
        receiverActor.tell(new CountProtocol.CountUp()); // Count: 1
        var runner = new TimedCounter();
        runner.startPeriodicTask(() -> {
            receiverActor.tell(new CountProtocol.CountUp()); // Count: 1
        });
    }
}
