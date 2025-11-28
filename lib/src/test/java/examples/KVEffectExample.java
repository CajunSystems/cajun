//DEPS com.cajunsystems:cajun:0.3.1
//JAVA 21+
//PREVIEW

package examples;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.functional.Effect;
import com.cajunsystems.handler.Handler;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static com.cajunsystems.functional.ActorSystemEffectExtensions.*;

/**
 * KV Store Example using Effects - LSM Tree Architecture
 * 
 * Demonstrates building a Key-Value store based on Log-Structured Merge (LSM) tree
 * using Effect-based actors. The architecture includes:
 * 
 * - MemTable Actor: In-memory sorted map that handles writes
 * - SSTable Actor: Immutable on-disk sorted string tables
 * - Manifest Actor: Tracks all SSTables and coordinates queries
 * 
 * All messages and states implement Serializable to support actor persistence.
 * Pid references are automatically rehydrated with the ActorSystem after recovery.
 * 
 * Run with: jbang KVEffectExample.java
 */
public class KVEffectExample {
    
    // Messages
    sealed interface MemTableMsg extends Serializable {}
    record Put(String key, String value, Pid replyTo) implements MemTableMsg {}
    record Get(String key, Pid replyTo) implements MemTableMsg {}
    record Flush(Pid replyTo) implements MemTableMsg {}
    
    sealed interface SSTableMsg extends Serializable {}
    record WriteSSTable(Map<String, String> data, String filename, Pid replyTo) implements SSTableMsg {}
    record ReadFromSSTable(String key, Pid replyTo) implements SSTableMsg {}
    
    sealed interface ManifestMsg extends Serializable {}
    record RegisterSSTable(String filename, Pid replyTo) implements ManifestMsg {}
    record QueryKey(String key, Pid replyTo) implements ManifestMsg {}
    record GetAllSSTables(Pid replyTo) implements ManifestMsg {}
    
    // Responses
    record PutResponse(boolean success) implements Serializable {}
    record GetResponse(Optional<String> value) implements Serializable {}
    record FlushResponse(String sstableFilename) implements Serializable {}
    record SSTableList(List<String> filenames) implements Serializable {}
    
    // States
    record MemTableState(
        HashMap<String, String> data,
        int maxSize,
        String manifestActorId,
        String sstableActorId,
        String dataDir
    ) implements Serializable {
        MemTableState(int maxSize, String manifestActorId, String sstableActorId, String dataDir) {
            this(new HashMap<>(), maxSize, manifestActorId, sstableActorId, dataDir);
        }
        
        boolean shouldFlush() {
            return data.size() >= maxSize;
        }
        
        MemTableState withNewData() {
            return new MemTableState(new HashMap<>(), maxSize, manifestActorId, sstableActorId, dataDir);
        }
    }
    
    record SSTableState(String dataDir, Map<String, Map<String, String>> tables) implements Serializable {
        SSTableState(String dataDir) {
            this(dataDir, new HashMap<>());
        }
    }
    
    record ManifestState(List<String> sstableFiles, String sstableActorId, String dataDir) implements Serializable {
        ManifestState(String sstableActorId, String dataDir) {
            this(new ArrayList<>(), sstableActorId, dataDir);
        }
    }
    
    // MemTable Actor
    static Effect<MemTableState, MemTableMsg, Void> createMemTableBehavior(Pid manifestActor, Pid sstableActor) {
        return Effect.<MemTableState, MemTableMsg, Void>match()
            .when(Put.class, (state, msg, ctx) -> {
                // Example: Could use filterOrElse for validation:
                // return Effect.<MemTableState, Put, Void>modify(s -> { ... })
                //     .filterOrElse(
                //         s -> !msg.key().isEmpty(),
                //         (s, m, c) -> {
                //             m.replyTo().tell(new PutResponse(false));
                //             return Effect.identity();  // Keep state unchanged, send error reply
                //         }
                //     );
                
                // Add to memtable
                state.data().put(msg.key(), msg.value());
                ctx.getLogger().info("PUT {}={} (size: {}/{})", msg.key(), msg.value(), 
                                    state.data().size(), state.maxSize());
                
                // Check if flush needed
                if (state.shouldFlush()) {
                    ctx.getLogger().info("MemTable full, triggering auto-flush");
                    ctx.tellSelf(new Flush(msg.replyTo()));
                } else {
                    msg.replyTo().tell(new PutResponse(true));
                }
                
                return Effect.modify(s -> s);
            })
            .when(Get.class, (state, msg, ctx) -> {
                Optional<String> value = Optional.ofNullable(state.data().get(msg.key()));
                if (value.isPresent()) {
                    ctx.getLogger().info("GET {} = {}", msg.key(), value.get());
                    msg.replyTo().tell(new GetResponse(value));
                } else {
                    ctx.getLogger().info("GET {} - checking SSTables", msg.key());
                    manifestActor.tell(new QueryKey(msg.key(), msg.replyTo()));
                }
                return Effect.identity();
            })
            .when(Flush.class, (state, msg, ctx) -> {
                Map<String, String> dataToFlush = new HashMap<>(state.data());
                String filename = "sstable-" + System.currentTimeMillis() + ".db";
                
                ctx.getLogger().info("Flushing {} keys to {}", dataToFlush.size(), filename);
                // Send to SSTable and Manifest without expecting replies
                sstableActor.tell(new WriteSSTable(dataToFlush, filename, msg.replyTo()));
                manifestActor.tell(new RegisterSSTable(filename, msg.replyTo()));
                msg.replyTo().tell(new FlushResponse(filename));
                
                return Effect.setState(state.withNewData());
            })
            .build();
    }
    
    // SSTable Actor
    static Effect<SSTableState, SSTableMsg, Void> createSSTableBehavior() {
        return Effect.<SSTableState, SSTableMsg, Void>match()
            .when(WriteSSTable.class, (state, msg, ctx) -> {
                try {
                    Path filePath = Paths.get(state.dataDir(), msg.filename());
                    try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
                        for (Map.Entry<String, String> entry : msg.data().entrySet()) {
                            writer.write(entry.getKey() + "=" + entry.getValue());
                            writer.newLine();
                        }
                    }
                    
                    ctx.getLogger().info("Wrote SSTable: {} ({} keys)", msg.filename(), msg.data().size());
                    
                    // Cache the data in memory
                    Map<String, Map<String, String>> newTables = new HashMap<>(state.tables());
                    newTables.put(msg.filename(), new HashMap<>(msg.data()));
                    
                    msg.replyTo().tell(new PutResponse(true));
                    return Effect.setState(new SSTableState(state.dataDir(), newTables));
                } catch (IOException e) {
                    ctx.getLogger().error("Failed to write SSTable", e);
                    msg.replyTo().tell(new PutResponse(false));
                    return Effect.modify(s -> s);
                }
            })
            .when(ReadFromSSTable.class, (state, msg, ctx) -> {
                for (Map<String, String> table : state.tables().values()) {
                    if (table.containsKey(msg.key())) {
                        String value = table.get(msg.key());
                        ctx.getLogger().info("SSTable read {} = {}", msg.key(), value);
                        msg.replyTo().tell(new GetResponse(Optional.of(value)));
                        return Effect.identity();
                    }
                }
                ctx.getLogger().info("SSTable read {} = NOT_FOUND", msg.key());
                msg.replyTo().tell(new GetResponse(Optional.empty()));
                return Effect.identity();
            })
            .build();
    }
    
    // Manifest Actor
    static Effect<ManifestState, ManifestMsg, Void> createManifestBehavior(Pid sstableActor) {
        return Effect.<ManifestState, ManifestMsg, Void>match()
            .when(RegisterSSTable.class, (state, msg, ctx) -> {
                List<String> newFiles = new ArrayList<>(state.sstableFiles());
                newFiles.add(msg.filename());
                
                ctx.getLogger().info("Registered SSTable: {} (total: {})", msg.filename(), newFiles.size());
                msg.replyTo().tell(new PutResponse(true));
                
                return Effect.setState(new ManifestState(newFiles, state.sstableActorId(), state.dataDir()));
            })
            .when(QueryKey.class, (state, msg, ctx) -> {
                ctx.getLogger().info("Querying {} SSTables for key: {}", state.sstableFiles().size(), msg.key());
                sstableActor.tell(new ReadFromSSTable(msg.key(), msg.replyTo()));
                return Effect.identity();
            })
            .when(GetAllSSTables.class, (state, msg, ctx) -> {
                msg.replyTo().tell(new SSTableList(new ArrayList<>(state.sstableFiles())));
                return Effect.identity();
            })
            .build();
    }
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== KV Store with LSM Tree (Effect-based) ===\n");
        
        // Clean up any old persistence data to avoid serialization issues
        Path persistenceDir = Paths.get("persistence");
        if (Files.exists(persistenceDir)) {
            Files.walk(persistenceDir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException e) {}
                });
        }
        
        ActorSystem system = new ActorSystem();
        String dataDir = "kv-data";
        Files.createDirectories(Paths.get(dataDir));
        
        try {
            Pid sstableActor = fromEffect(system, createSSTableBehavior(), new SSTableState(dataDir))
                .withId("sstable")
                .withPersistence(false)
                .spawn();
            
            Pid manifestActor = fromEffect(system, createManifestBehavior(sstableActor), 
                new ManifestState("sstable", dataDir))
                .withId("manifest")
                .withPersistence(false)
                .spawn();
            
            Pid memtableActor = fromEffect(system, createMemTableBehavior(manifestActor, sstableActor),
                new MemTableState(5, "manifest", "sstable", dataDir))
                .withId("memtable")
                .withPersistence(false)
                .spawn();
            
            System.out.println("✓ Actors spawned: MemTable, SSTable, Manifest\n");
            
            Pid client = system.actorOf(new Handler<Object>() {
                @Override
                public void receive(Object msg, com.cajunsystems.ActorContext context) {
                    System.out.println("  → " + msg);
                }
            }).withId("client").spawn();
            
            System.out.println("1. Writing 7 keys (auto-flush at 5):");
            for (int i = 1; i <= 7; i++) {
                memtableActor.tell(new Put("key" + i, "value" + i, client));
                Thread.sleep(100);
            }
            
            Thread.sleep(500);
            
            System.out.println("\n2. Reading keys:");
            memtableActor.tell(new Get("key1", client));
            Thread.sleep(100);
            memtableActor.tell(new Get("key7", client));
            Thread.sleep(100);
            memtableActor.tell(new Get("key99", client));
            Thread.sleep(100);
            
            System.out.println("\n3. Manual flush:");
            memtableActor.tell(new Flush(client));
            Thread.sleep(500);
            
            System.out.println("\n4. Query manifest:");
            manifestActor.tell(new GetAllSSTables(client));
            Thread.sleep(200);
            
            System.out.println("\n=== Demo Complete ===");
            
        } finally {
            system.shutdown();
            Files.walk(Paths.get(dataDir))
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException e) {}
                });
        }
    }
}
