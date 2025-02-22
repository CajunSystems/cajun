package systems.cajun;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.StructuredTaskScope;

public abstract class Actor<Message> {

    private final BlockingQueue<Message> mailbox;
    private volatile boolean isRunning;
    private final String actorId;
    private final ActorSystem system;
    private final Pid pid;

    public Actor(ActorSystem system) {
        this.system = system;
        this.actorId = UUID.randomUUID().toString();
        this.mailbox = new LinkedBlockingQueue<>();
        this.pid = new Pid(actorId, system);
    }

    public Actor(ActorSystem system, String actorId) {
        this.system = system;
        this.actorId = actorId;
        this.mailbox = new LinkedBlockingQueue<>();
        this.pid = new Pid(actorId, system);
    }

    protected abstract void receive(Message message);

    public void start() {
        isRunning = true;
        Thread.startVirtualThread(() -> {
            try (var scope = new StructuredTaskScope<>()) {
                scope.fork(() -> {
                    processMailbox();
                    return null;
                });
                scope.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    public Pid self() {
        return pid;
    }

    public String getActorId() {
        return actorId;
    }

    public void tell(Message message) {
        mailbox.offer(message);
    }

    protected void processMailbox() {
        while (isRunning) {
            try {
                Message message = mailbox.take();
                try {
                    receive(message);
                } catch (Exception e) {
                    System.out.println(STR."Error processing message: \{e.getMessage()}");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public boolean isRunning() {
        return isRunning;
    }

    public void stop() {
        if (!isRunning) {
            return;
        }
        isRunning = false;
        // Intimate system just in case it was actor who triggered stop
        system.shutdown(actorId);
    }
}
