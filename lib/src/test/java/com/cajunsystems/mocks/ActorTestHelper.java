package com.cajunsystems.mocks;

import com.cajunsystems.Actor;
import com.cajunsystems.StatefulActor;
import com.cajunsystems.persistence.*;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Helper class for testing actors with mocks.
 * Provides utility methods for common testing scenarios.
 */
public class ActorTestHelper {

    /**
     * Configure a mock MessageJournal for basic operations.
     * This sets up the mock to handle append operations with sequential sequence numbers.
     *
     * @param mockJournal The mock MessageJournal to configure
     * @param <M> The type of messages
     * @return The configured mock
     */
    public static <M> MessageJournal<M> configureBasicMessageJournal(MessageJournal<M> mockJournal) {
        when(mockJournal.getHighestSequenceNumber(anyString()))
            .thenReturn(CompletableFuture.completedFuture(-1L));
        
        when(mockJournal.append(anyString(), any()))
            .thenReturn(CompletableFuture.completedFuture(1L));
        
        when(mockJournal.readFrom(anyString(), anyLong()))
            .thenReturn(CompletableFuture.completedFuture(new ArrayList<>()));
        
        return mockJournal;
    }
    
    /**
     * Configure a mock SnapshotStore for basic operations.
     * This sets up the mock to handle snapshot operations.
     *
     * @param mockSnapshotStore The mock SnapshotStore to configure
     * @param <T> The type of state
     * @return The configured mock
     */
    public static <T> SnapshotStore<T> configureBasicSnapshotStore(SnapshotStore<T> mockSnapshotStore) {
        when(mockSnapshotStore.getLatestSnapshot(anyString()))
            .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        
        when(mockSnapshotStore.saveSnapshot(anyString(), any(), anyLong()))
            .thenReturn(CompletableFuture.completedFuture(null));
        
        return mockSnapshotStore;
    }
    
    /**
     * Configure a mock SnapshotStore to return a specific snapshot.
     *
     * @param mockSnapshotStore The mock SnapshotStore to configure
     * @param actorId The actor ID to match
     * @param state The state to return in the snapshot
     * @param sequenceNumber The sequence number for the snapshot
     * @param <T> The type of state
     * @return The configured mock
     */
    public static <T> SnapshotStore<T> configureSnapshotStoreWithState(
            SnapshotStore<T> mockSnapshotStore, 
            String actorId, 
            T state, 
            long sequenceNumber) {
        
        SnapshotEntry<T> snapshotEntry = new SnapshotEntry<>(actorId, state, sequenceNumber);
        
        when(mockSnapshotStore.getLatestSnapshot(eq(actorId)))
            .thenReturn(CompletableFuture.completedFuture(Optional.of(snapshotEntry)));
        
        when(mockSnapshotStore.saveSnapshot(eq(actorId), any(), anyLong()))
            .thenReturn(CompletableFuture.completedFuture(null));
        
        return mockSnapshotStore;
    }
    
    /**
     * Configure a mock MessageJournal to return specific journal entries.
     *
     * @param mockJournal The mock MessageJournal to configure
     * @param actorId The actor ID to match
     * @param fromSequence The sequence number to start from
     * @param messages The messages to return
     * @param <M> The type of messages
     * @return The configured mock
     */
    public static <M> MessageJournal<M> configureMessageJournalWithEntries(
            MessageJournal<M> mockJournal, 
            String actorId, 
            long fromSequence, 
            List<M> messages) {
        
        List<JournalEntry<M>> entries = new ArrayList<>();
        long sequence = fromSequence;
        
        for (M message : messages) {
            entries.add(new JournalEntry<>(sequence++, actorId, message));
        }
        
        when(mockJournal.readFrom(eq(actorId), eq(fromSequence)))
            .thenReturn(CompletableFuture.completedFuture(entries));
        
        when(mockJournal.getHighestSequenceNumber(eq(actorId)))
            .thenReturn(CompletableFuture.completedFuture(sequence - 1));
        
        return mockJournal;
    }
    
    /**
     * Create a spy on an actor to verify method calls.
     *
     * @param actor The actor to spy on
     * @param <T> The type of messages the actor processes
     * @return A spy on the actor
     */
    public static <T> Actor<T> spyOnActor(Actor<T> actor) {
        return Mockito.spy(actor);
    }
    
    /**
     * Create a spy on a stateful actor to verify method calls.
     *
     * @param actor The stateful actor to spy on
     * @param <S> The type of state
     * @param <M> The type of messages
     * @return A spy on the stateful actor
     */
    public static <S, M extends OperationAwareMessage> StatefulActor<S, M> spyOnStatefulActor(StatefulActor<S, M> actor) {
        return Mockito.spy(actor);
    }
}
