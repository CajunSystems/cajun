package com.cajunsystems;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test to verify that an actor can have multiple children.
 */
public class ActorChildrenTest {

    private ActorSystem system;

    @BeforeEach
    void setUp() {
        system = new ActorSystem();
    }

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.shutdown();
        }
    }

    /**
     * Simple message class for testing.
     */
    private static class TestMessage {
        private final String content;

        public TestMessage(String content) {
            this.content = content;
        }

        public String getContent() {
            return content;
        }
    }

    /**
     * Parent actor that creates multiple children.
     */
    private static class ParentActor extends Actor<TestMessage> {
        private final CountDownLatch childrenCreatedLatch;
        private int numberOfChildren;

        public ParentActor(ActorSystem system, String actorId) {
            super(system, actorId);
            this.childrenCreatedLatch = new CountDownLatch(1);
        }

        @Override
        protected void receive(TestMessage message) {
            if (message.getContent().startsWith("set_children_count:")) {
                // Extract the number of children from the message
                String countStr = message.getContent().substring("set_children_count:".length());
                this.numberOfChildren = Integer.parseInt(countStr);
                System.out.println("[DEBUG_LOG] Set number of children to: " + numberOfChildren);
            } else if ("create_children".equals(message.getContent())) {
                System.out.println("[DEBUG_LOG] Creating " + numberOfChildren + " children");

                // Create multiple child actors
                for (int i = 0; i < numberOfChildren; i++) {
                    String childId = getActorId() + "-child-" + i;
                    createChild(ChildActor.class, childId);
                    System.out.println("[DEBUG_LOG] Created child: " + childId);
                }

                // Signal that children have been created
                childrenCreatedLatch.countDown();
            }
        }

        public CountDownLatch getChildrenCreatedLatch() {
            return childrenCreatedLatch;
        }
    }

    /**
     * Simple child actor for testing.
     */
    private static class ChildActor extends Actor<TestMessage> {
        public ChildActor(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        @Override
        protected void receive(TestMessage message) {
            System.out.println("[DEBUG_LOG] Child " + getActorId() + " received: " + message.getContent());
        }
    }

    @Test
    public void testActorCanHaveMultipleChildren() throws InterruptedException {
        // Number of children to create
        int numberOfChildren = 3;

        // Register parent actor
        Pid parentPid = system.register(ParentActor.class, "parent");
        ParentActor parentActor = (ParentActor) system.getActor(parentPid);

        // Set the number of children
        parentPid.tell(new TestMessage("set_children_count:" + numberOfChildren));

        // Tell parent to create children
        parentPid.tell(new TestMessage("create_children"));

        // Wait for children to be created
        boolean childrenCreated = parentActor.getChildrenCreatedLatch().await(5, TimeUnit.SECONDS);
        assertTrue(childrenCreated, "Children should be created within timeout");

        // Get the children map from the parent
        Map<String, Actor<?>> children = parentActor.getChildren();

        // Verify that the parent has the expected number of children
        System.out.println("[DEBUG_LOG] Number of children: " + children.size());
        assertEquals(numberOfChildren, children.size(), "Parent should have " + numberOfChildren + " children");

        // Verify that each child has the expected ID
        for (int i = 0; i < numberOfChildren; i++) {
            String childId = "parent-child-" + i;
            assertTrue(children.containsKey(childId), "Parent should have child with ID: " + childId);
            System.out.println("[DEBUG_LOG] Found child: " + childId);
        }

        // No need to manually stop actors, they will be stopped by system.shutdown() in tearDown
    }
}
