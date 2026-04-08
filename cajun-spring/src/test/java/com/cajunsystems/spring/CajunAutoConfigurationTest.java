package com.cajunsystems.spring;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.ActorContext;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.spring.annotation.ActorComponent;
import com.cajunsystems.spring.annotation.InjectActor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = {
        CajunAutoConfiguration.class,
        CajunAutoConfigurationTest.TestConfig.class,
        CajunAutoConfigurationTest.GreetingHandler.class,
        CajunAutoConfigurationTest.TestService.class
})
class CajunAutoConfigurationTest {

    // ---- Minimal Spring Boot test context ----

    @Configuration
    static class TestConfig {
        // No extra config needed; CajunAutoConfiguration kicks in automatically
    }

    // ---- Handler registered as a Spring + Cajun actor ----

    @ActorComponent(id = "greeter")
    static class GreetingHandler implements Handler<String> {
        static final AtomicReference<String> lastMessage = new AtomicReference<>();
        static final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void receive(String message, ActorContext context) {
            lastMessage.set(message);
            latch.countDown();
        }
    }

    // ---- Service that uses @InjectActor ----

    @Service
    static class TestService {
        @InjectActor(GreetingHandler.class)
        Pid greeterPid;

        @InjectActor(id = "greeter")
        ActorRef<String> greeterRef;
    }

    // ---- Injected Spring beans ----

    @Autowired ActorSystem actorSystem;
    @Autowired CajunActorRegistry registry;
    @Autowired TestService testService;

    // ---- Tests ----

    @Test
    void actorSystemBeanIsCreated() {
        assertNotNull(actorSystem);
    }

    @Test
    void registryBeanIsCreated() {
        assertNotNull(registry);
    }

    @Test
    void actorComponentIsSpawnedAndRegistered() {
        Pid pid = registry.getByHandlerClass(GreetingHandler.class);
        assertNotNull(pid);
        assertEquals("greeter", pid.actorId());

        Pid byId = registry.getByActorId("greeter");
        assertEquals(pid, byId);
    }

    @Test
    void injectActorByClass_injectsPid() {
        assertNotNull(testService.greeterPid);
        assertEquals("greeter", testService.greeterPid.actorId());
    }

    @Test
    void injectActorByClass_injectsActorRef() {
        assertNotNull(testService.greeterRef);
        assertEquals("greeter", testService.greeterRef.getActorId());
    }

    @Test
    void actorRefCanSendMessages() throws InterruptedException {
        GreetingHandler.lastMessage.set(null);

        testService.greeterRef.tell("hello");

        assertTrue(GreetingHandler.latch.await(5, TimeUnit.SECONDS));
        assertEquals("hello", GreetingHandler.lastMessage.get());
    }

    @Test
    void manualSpawnRegisteredViaRegistry() {
        Pid pid = actorSystem.actorOf((msg, ctx) -> {})
                .withId("manual-actor")
                .spawn();

        registry.register(Object.class, "manual-actor", pid);

        Pid found = registry.getByActorId("manual-actor");
        assertNotNull(found);
        assertEquals("manual-actor", found.actorId());
    }
}
