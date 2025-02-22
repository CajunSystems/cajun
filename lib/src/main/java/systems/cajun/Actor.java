package systems.cajun;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.StructuredTaskScope;

public abstract class Actor<Message> {

    private final BlockingQueue<Message> mailbox;
    private volatile boolean isRunning;

    public Actor() {
        this.mailbox = new LinkedBlockingQueue<>();
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

    public void stop() {
        if (!isRunning) {
            return;
        }
        isRunning = false;
    }
}
