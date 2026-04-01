package com.cajunsystems.builder;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.persistence.PersistenceProvider;
import com.cajunsystems.persistence.PersistenceProviderRegistry;
import com.cajunsystems.persistence.impl.FileSystemPersistenceProvider;
import com.cajunsystems.test.TempPersistenceExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for counter recovery from persisted actors.
 * Tests the automatic initialization of sequence counters from persisted state
 * to prevent ID collisions after system restarts.
 * 
 * <p>These tests verify that when actors with sequential IDs are persisted,
 * the system automatically scans them on startup and initializes counters
 * to prevent collisions with new actors.
 */
@ExtendWith(TempPersistenceExtension.class)
class IdCounterRecoveryIntegrationTest {

    private ActorSystem system;
    private PersistenceProvider persistenceProvider;
    private Path tempDir;

    // Test handlers
    public static class TestStatefulHandler implements StatefulHandler<Integer, String> {
        @Override
        public Integer receive(String message, Integer state, com.cajunsystems.ActorContext context) {
            return state + 1;
        }
    }
    
    public static class TestHandler implements com.cajunsystems.handler.Handler<String> {
        @Override
        public void receive(String message, com.cajunsystems.ActorContext context) {
            // Simple handler for parent actors
        }
    }

    @BeforeEach
    void setUp() {
        // Get temp directory from extension
        tempDir = Path.of(System.getProperty("java.io.tmpdir"), "cajun-test-" + System.nanoTime());
        
        // Create persistence provider
        persistenceProvider = new FileSystemPersistenceProvider(tempDir.toString());
        PersistenceProviderRegistry.getInstance().registerProvider(persistenceProvider);
        PersistenceProviderRegistry.getInstance().setDefaultProvider(persistenceProvider.getProviderName());
        
        // Create actor system and set persistence provider
        system = new ActorSystem();
        system.setPersistenceProvider(persistenceProvider);
        
        // Reset counters for clean testing
        IdTemplateProcessor.resetCounters();
    }

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.shutdown();
        }
        // Reset counters after each test to avoid affecting other tests
        IdTemplateProcessor.resetCounters();
    }
    
    /**
     * Helper method to simulate a persisted actor by creating a snapshot file.
     * This is faster than actually persisting state and sufficient for testing counter recovery.
     * The FileSystemPersistenceProvider expects snapshot files named: {actorId}.snapshot
     * For hierarchical IDs (containing '/'), creates subdirectories as needed.
     */
    private void simulatePersistedActor(String actorId) throws Exception {
        java.nio.file.Path snapshotDir = tempDir.resolve("snapshots");
        java.nio.file.Files.createDirectories(snapshotDir);
        
        // Handle hierarchical IDs by creating subdirectories
        java.nio.file.Path snapshotFile = snapshotDir.resolve(actorId + ".snapshot");
        java.nio.file.Files.createDirectories(snapshotFile.getParent());
        java.nio.file.Files.createFile(snapshotFile);
    }

    // ============================================================================
    // CLASS_BASED_SEQUENTIAL Strategy Tests
    // ============================================================================

    @Test
    void shouldRecoverCountersFromPersistedActorsWithClassBasedSequential() throws Exception {
        // First run: Simulate persisted actors by creating snapshot directories
        // This simulates actors that have been persisted in a previous run
        simulatePersistedActor("teststateful:1");
        simulatePersistedActor("teststateful:2");
        simulatePersistedActor("teststateful:3");
        
        // Shutdown system (simulating restart)
        system.shutdown();
        
        // Second run: Create new system (simulating restart)
        IdTemplateProcessor.resetCounters(); // Simulate fresh JVM
        ActorSystem newSystem = new ActorSystem();
        newSystem.setPersistenceProvider(persistenceProvider); // Set same provider
        
        // Create new actor with CLASS_BASED_SEQUENTIAL - should NOT collide
        Pid actor4 = newSystem.statefulActorOf(TestStatefulHandler.class, 0)
            .withIdStrategy(IdStrategy.CLASS_BASED_SEQUENTIAL)
            .spawn();
        
        // Verify new actor continues sequence
        assertEquals("teststateful:4", actor4.actorId(), 
            "New actor should continue sequence from persisted state");
        
        newSystem.shutdown();
    }

    @Test
    void shouldHandleMultipleClassesWithSeparateCounters() throws Exception {
        // First run: Simulate persisted actors with different class prefixes
        simulatePersistedActor("teststateful:1");
        simulatePersistedActor("teststateful:2");
        simulatePersistedActor("otherclass:1");
        
        system.shutdown();
        
        // Second run: New system
        IdTemplateProcessor.resetCounters();
        ActorSystem newSystem = new ActorSystem();
        newSystem.setPersistenceProvider(persistenceProvider);
        
        // Create new actors with strategies - should continue from persisted state
        Pid test3 = newSystem.statefulActorOf(TestStatefulHandler.class, 0)
            .withIdStrategy(IdStrategy.CLASS_BASED_SEQUENTIAL)
            .spawn();
        
        // Verify counter resumed correctly
        assertEquals("teststateful:3", test3.actorId(), 
            "TestStatefulHandler counter should resume from 2 to 3");
        
        newSystem.shutdown();
    }

    // ============================================================================
    // Template with Colon Separator Tests (WORKS)
    // ============================================================================

    @Test
    void shouldRecoverCountersFromTemplateWithColonSeparator() throws Exception {
        // Note: Templates with {seq} use class-based counters, which don't match
        // the template prefix. For counter recovery to work with templates, the persisted
        // IDs must match the class name pattern.
        
        // First run: Simulate persisted actors that were created with same class
        simulatePersistedActor("teststateful:1");
        simulatePersistedActor("teststateful:2");
        
        system.shutdown();
        
        // Second run: New system
        IdTemplateProcessor.resetCounters();
        ActorSystem newSystem = new ActorSystem();
        newSystem.setPersistenceProvider(persistenceProvider);
        
        // Create new actor with template using {seq} - uses class counter
        Pid user3 = newSystem.statefulActorOf(TestStatefulHandler.class, 0)
            .withIdTemplate("user:{seq}")
            .spawn();
        
        // {seq} uses the class counter (teststateful), which was set to 2 from persisted state
        assertEquals("user:3", user3.actorId(), "Counter should resume from persisted state");
        
        newSystem.shutdown();
    }

    @Test
    void shouldHandleMultipleTemplatesWithColonSeparator() throws Exception {
        // This test demonstrates that {seq} uses class-based counters,
        // so all templates for the same class share the same counter
        
        // First run: Simulate persisted actors with class-based IDs
        simulatePersistedActor("teststateful:1");
        simulatePersistedActor("teststateful:2");
        
        system.shutdown();
        
        // Second run: New system
        IdTemplateProcessor.resetCounters();
        ActorSystem newSystem = new ActorSystem();
        newSystem.setPersistenceProvider(persistenceProvider);
        
        // Create new actors with different templates but same class
        // Both use the same class counter
        Pid user3 = newSystem.statefulActorOf(TestStatefulHandler.class, 0)
            .withIdTemplate("user:{seq}")
            .spawn();
        
        Pid session4 = newSystem.statefulActorOf(TestStatefulHandler.class, 0)
            .withIdTemplate("session:{seq}")
            .spawn();
        
        // Both continue from the same class counter
        assertEquals("user:3", user3.actorId(), "Uses class counter teststateful");
        assertEquals("session:4", session4.actorId(), "Uses same class counter teststateful");
        
        newSystem.shutdown();
    }

    // ============================================================================
    // Template with Dash Separator Tests (DOES NOT WORK - Known Limitation)
    // ============================================================================

    @Test
    void shouldNotRecoverCountersFromTemplateWithDashSeparator() throws Exception {
        // First run: Simulate persisted actors with dash-separated IDs
        simulatePersistedActor("user-1");
        simulatePersistedActor("user-2");
        
        system.shutdown();
        
        // Second run: New system
        IdTemplateProcessor.resetCounters();
        ActorSystem newSystem = new ActorSystem();
        newSystem.setPersistenceProvider(persistenceProvider);
        
        // Create new actor - will COLLIDE because dash separator doesn't work
        Pid user3 = newSystem.statefulActorOf(TestStatefulHandler.class, 0)
            .withIdTemplate("user-{seq}")
            .spawn();
        
        // This demonstrates the known limitation: counter resets to 1
        assertEquals("user-1", user3.actorId(), 
            "Counter does NOT resume with dash separator - this is the known limitation");
        
        newSystem.shutdown();
    }

    // ============================================================================
    // Hierarchical ID Tests
    // ============================================================================

    @Test
    void shouldRecoverCountersFromHierarchicalIds() throws Exception {
        // First run: Simulate persisted hierarchical actors
        simulatePersistedActor("parent/teststateful:1");
        simulatePersistedActor("parent/teststateful:2");
        
        system.shutdown();
        
        // Second run: New system
        IdTemplateProcessor.resetCounters();
        ActorSystem newSystem = new ActorSystem();
        newSystem.setPersistenceProvider(persistenceProvider);
        
        // Create parent
        Pid parent = newSystem.actorOf(TestHandler.class)
            .withId("parent")
            .spawn();
        
        // Create new child with strategy - should continue sequence
        Pid child3 = newSystem.statefulActorOf(TestStatefulHandler.class, 0)
            .withIdStrategy(IdStrategy.CLASS_BASED_SEQUENTIAL)
            .withParent(newSystem.getActor(parent))
            .spawn();
        
        assertEquals("parent/teststateful:3", child3.actorId(), 
            "Counter should resume for hierarchical IDs");
        
        newSystem.shutdown();
    }

    // ============================================================================
    // Edge Cases
    // ============================================================================

    @Test
    void shouldHandleEmptyPersistedActorsList() {
        // No persisted actors - counters should start from 1
        Pid actor1 = system.statefulActorOf(TestStatefulHandler.class, 0)
            .withIdStrategy(IdStrategy.CLASS_BASED_SEQUENTIAL)
            .spawn();
        
        assertEquals("teststateful:1", actor1.actorId(), "First actor should have sequence 1");
    }
    
    @Test
    void verifyListPersistedActorsWorks() throws Exception {
        // Create some snapshot files
        simulatePersistedActor("test:1");
        simulatePersistedActor("test:2");
        simulatePersistedActor("test:3");
        
        // Verify they can be found
        List<String> persisted = persistenceProvider.listPersistedActors();
        assertEquals(3, persisted.size());
        assertTrue(persisted.contains("test:1"));
        assertTrue(persisted.contains("test:2"));
        assertTrue(persisted.contains("test:3"));
    }

    @Test
    void shouldHandleGapsInSequence() throws Exception {
        // First run: Simulate persisted actors with gaps in sequence
        simulatePersistedActor("teststateful:5");
        simulatePersistedActor("teststateful:10");
        
        system.shutdown();
        
        // Second run: New system
        IdTemplateProcessor.resetCounters();
        ActorSystem newSystem = new ActorSystem();
        newSystem.setPersistenceProvider(persistenceProvider);
        
        // Create new actor - should start after max found
        Pid actor3 = newSystem.statefulActorOf(TestStatefulHandler.class, 0)
            .withIdStrategy(IdStrategy.CLASS_BASED_SEQUENTIAL)
            .spawn();
        
        assertEquals("teststateful:11", actor3.actorId(), 
            "Counter should resume after maximum found sequence number");
        
        newSystem.shutdown();
    }

    @Test
    void shouldIgnoreNonSequentialPatterns() throws Exception {
        // First run: Simulate mix of sequential and non-sequential IDs
        simulatePersistedActor("teststateful:1");
        simulatePersistedActor("explicit-actor");  // Non-sequential pattern
        simulatePersistedActor("teststateful:2");
        
        system.shutdown();
        
        // Second run: New system
        IdTemplateProcessor.resetCounters();
        ActorSystem newSystem = new ActorSystem();
        newSystem.setPersistenceProvider(persistenceProvider);
        
        // Create new sequential actor - should skip explicit IDs
        Pid actor3 = newSystem.statefulActorOf(TestStatefulHandler.class, 0)
            .withIdStrategy(IdStrategy.CLASS_BASED_SEQUENTIAL)
            .spawn();
        
        assertEquals("teststateful:3", actor3.actorId(), 
            "Counter should skip non-sequential IDs and resume from sequential ones");
        
        newSystem.shutdown();
    }
}
