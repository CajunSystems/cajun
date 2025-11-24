package com.cajunsystems.kvstore;

import com.cajunsystems.ActorSystem;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

public class KVRunner {

    static void main() {
        ActorSystem actorSystem = new ActorSystem();

        var kvStore = actorSystem.statefulActorOf(KVStore.class, new HashMap<>())
                .withId("key-value-store")
                .spawn();

        kvStore.tell(new KVCommand.Put("host", "localhost"));
        kvStore.tell(new KVCommand.Put("port", "8081"));

        CompletableFuture<String> host = actorSystem.ask(kvStore, new KVCommand.Get("host"), Duration.of(10, ChronoUnit.SECONDS));
        CompletableFuture<String> port = actorSystem.ask(kvStore, new KVCommand.Get("port"), Duration.of(10, ChronoUnit.SECONDS));

        host.thenAccept(System.out::println);
        port.thenAccept(System.out::println);
    }
}
