package com.cajunsystems.persistence;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PidRehydrator} in cluster/cross-node recovery contexts.
 *
 * <p>Verifies that deserialized {@link Pid} instances (which carry a {@code null}
 * {@code actorSystem} field after serialization) are correctly rehydrated with
 * the new {@link ActorSystem} during state recovery.  No Redis or etcd required —
 * all tests are pure in-process unit tests.
 */
class PidRehydratorClusterTest {

    // -------------------------------------------------------------------------
    // Static nested state/record types used across tests
    // -------------------------------------------------------------------------

    /** A record whose single field is a Pid — exercises record traversal. */
    record ActorRef(String name, Pid pid) {}

    /** A plain record with no Pid fields — verifies unchanged passthrough. */
    record SimpleState(int count) {}

    /** An inner record containing a Pid. */
    record InnerRef(Pid pid) {}

    /** An outer record that embeds an InnerRef — exercises nested-record traversal. */
    record OuterState(String label, InnerRef inner, Pid direct) {}

    // -------------------------------------------------------------------------
    // Test infrastructure
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * A {@link Pid} constructed with a {@code null} system (as produced by
     * Java deserialization via {@code SerializationProxy.readResolve()}) must
     * have its {@code system} field populated after rehydration.
     */
    @Test
    void rehydrate_pid_with_null_system_gets_new_system() {
        Pid nullSystemPid = new Pid("actor-42", null);

        Pid rehydrated = PidRehydrator.rehydrate(nullSystemPid, system);

        assertNotNull(rehydrated, "Rehydrated Pid must not be null");
        assertEquals("actor-42", rehydrated.actorId(), "actorId must be preserved");
        assertSame(system, rehydrated.system(), "system must be the supplied ActorSystem");
    }

    /**
     * A {@link Pid} that already has a non-null {@code system} must NOT be
     * overwritten — the original system reference is preserved even when a
     * different system is passed as the rehydration target.
     */
    @Test
    void rehydrate_pid_with_existing_system_unchanged() {
        ActorSystem originalSystem = new ActorSystem();
        try {
            Pid alreadyHydrated = new Pid("actor-7", originalSystem);
            ActorSystem differentSystem = system;

            Pid result = PidRehydrator.rehydrate(alreadyHydrated, differentSystem);

            assertSame(originalSystem, result.system(),
                    "Existing non-null system must NOT be replaced");
        } finally {
            originalSystem.shutdown();
        }
    }

    /**
     * A record whose field contains a {@link Pid} with a null system must have
     * that Pid rehydrated.  The record itself is recreated with the updated Pid.
     */
    @Test
    void rehydrate_record_state_containing_pid() {
        Pid nullSystemPid = new Pid("worker-1", null);
        ActorRef state = new ActorRef("worker", nullSystemPid);

        ActorRef rehydrated = PidRehydrator.rehydrate(state, system);

        assertNotNull(rehydrated, "Rehydrated state must not be null");
        assertEquals("worker", rehydrated.name(), "Non-Pid field must be preserved");
        assertNotNull(rehydrated.pid().system(),
                "Pid inside record must have system set after rehydration");
        assertSame(system, rehydrated.pid().system(),
                "Pid inside record must use the supplied ActorSystem");
    }

    /**
     * Passing {@code null} state must return {@code null} without throwing.
     */
    @Test
    void rehydrate_null_state_returns_null() {
        Object result = PidRehydrator.rehydrate(null, system);
        assertNull(result, "null state must be returned as-is");
    }

    /**
     * A record with no {@link Pid} fields must pass through unchanged.
     */
    @Test
    void rehydrate_state_with_no_pids_unchanged() {
        SimpleState state = new SimpleState(42);

        SimpleState rehydrated = PidRehydrator.rehydrate(state, system);

        assertNotNull(rehydrated, "Rehydrated state must not be null");
        assertEquals(42, rehydrated.count(), "count must be preserved");
    }

    /**
     * A nested record structure — {@link OuterState} containing {@link InnerRef}
     * and a direct {@link Pid} — must have all null-system Pids rehydrated
     * regardless of nesting depth.
     */
    @Test
    void rehydrate_nested_record_with_multiple_pids() {
        Pid innerPid = new Pid("inner-actor", null);
        Pid directPid = new Pid("direct-actor", null);
        InnerRef innerRef = new InnerRef(innerPid);
        OuterState state = new OuterState("outer", innerRef, directPid);

        OuterState rehydrated = PidRehydrator.rehydrate(state, system);

        assertNotNull(rehydrated, "Rehydrated outer state must not be null");
        assertEquals("outer", rehydrated.label(), "label must be preserved");

        assertNotNull(rehydrated.inner(), "Inner record must not be null");
        assertSame(system, rehydrated.inner().pid().system(),
                "Nested (inner) Pid must be rehydrated");

        assertSame(system, rehydrated.direct().system(),
                "Direct Pid field must be rehydrated");
    }
}
