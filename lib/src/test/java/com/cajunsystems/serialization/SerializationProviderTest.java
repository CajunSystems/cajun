package com.cajunsystems.serialization;

import com.cajunsystems.persistence.JournalEntry;
import com.cajunsystems.persistence.filesystem.FileMessageJournal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the SerializationProvider framework.
 *
 * Key coverage:
 * - Non-Serializable types work with Kryo but fail with Java provider
 * - Sealed interfaces with Kryo
 * - JournalEntry round-trip with Kryo
 * - Concurrent Kryo serialization (ThreadLocal isolation)
 * - FileMessageJournal integration with Kryo and Java providers
 */
class SerializationProviderTest {

    // Non-Serializable types — these are the key proof that non-Serializable works
    record Point(int x, int y) {}
    record Container(String name, Point location) {}

    // Serializable type — control case for Java provider test
    record SerializablePoint(int x, int y) implements java.io.Serializable {}

    // Sealed interface — tests Kryo polymorphic support
    sealed interface Shape permits Shape.Circle, Shape.Rectangle {
        record Circle(double radius) implements Shape {}
        record Rectangle(double width, double height) implements Shape {}
    }

    // ── Kryo tests ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Kryo: basic round-trip with non-Serializable record")
    void kryoRoundTripNonSerializable() {
        var kryo = KryoSerializationProvider.INSTANCE;
        var point = new Point(3, 7);

        byte[] bytes = kryo.serialize(point);
        Point result = kryo.deserialize(bytes, Point.class);

        assertEquals(3, result.x());
        assertEquals(7, result.y());
    }

    @Test
    @DisplayName("Kryo: nested non-Serializable record round-trip")
    void kryoRoundTripNestedRecord() {
        var kryo = KryoSerializationProvider.INSTANCE;
        var container = new Container("origin", new Point(0, 0));

        byte[] bytes = kryo.serialize(container);
        Container result = kryo.deserialize(bytes, Container.class);

        assertEquals("origin", result.name());
        assertEquals(0, result.location().x());
        assertEquals(0, result.location().y());
    }

    @Test
    @DisplayName("Kryo: sealed interface round-trip")
    void kryoSealedInterfaceRoundTrip() {
        var kryo = KryoSerializationProvider.INSTANCE;
        Shape circle = new Shape.Circle(5.0);
        Shape rect = new Shape.Rectangle(3.0, 4.0);

        byte[] circleBytes = kryo.serialize(circle);
        byte[] rectBytes = kryo.serialize(rect);

        Shape deserializedCircle = kryo.deserialize(circleBytes, Shape.class);
        Shape deserializedRect = kryo.deserialize(rectBytes, Shape.class);

        assertInstanceOf(Shape.Circle.class, deserializedCircle);
        assertEquals(5.0, ((Shape.Circle) deserializedCircle).radius());
        assertInstanceOf(Shape.Rectangle.class, deserializedRect);
        assertEquals(3.0, ((Shape.Rectangle) deserializedRect).width());
    }

    @Test
    @DisplayName("Kryo: JournalEntry containing non-Serializable record round-trip")
    void kryoJournalEntryRoundTrip() {
        var kryo = KryoSerializationProvider.INSTANCE;
        var point = new Point(42, 99);
        var entry = new JournalEntry<>(1L, "actor-1", point);

        byte[] bytes = kryo.serialize(entry);
        @SuppressWarnings("unchecked")
        JournalEntry<Point> result = (JournalEntry<Point>) kryo.deserialize(bytes, JournalEntry.class);

        assertEquals(1L, result.getSequenceNumber());
        assertEquals("actor-1", result.getActorId());
        assertEquals(42, result.getMessage().x());
        assertEquals(99, result.getMessage().y());
    }

    @Test
    @DisplayName("Kryo: concurrent serialization with ThreadLocal isolation")
    void kryoConcurrentSerialization() throws InterruptedException, ExecutionException {
        var kryo = KryoSerializationProvider.INSTANCE;
        int threadCount = 10;
        int itersPerThread = 100;
        AtomicInteger errorCount = new AtomicInteger(0);

        ExecutorService exec = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new java.util.ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            futures.add(exec.submit(() -> {
                for (int i = 0; i < itersPerThread; i++) {
                    try {
                        Point p = new Point(threadId * 1000 + i, i);
                        byte[] bytes = kryo.serialize(p);
                        Point result = kryo.deserialize(bytes, Point.class);
                        if (result.x() != threadId * 1000 + i || result.y() != i) {
                            errorCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get();
        }
        exec.shutdown();

        assertEquals(0, errorCount.get(), "No corruption expected across " + threadCount + " threads");
    }

    // ── JSON tests ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("JSON: basic round-trip with non-Serializable record")
    void jsonRoundTripNonSerializable() {
        var json = JsonSerializationProvider.INSTANCE;
        var point = new Point(10, 20);

        byte[] bytes = json.serialize(point);
        Point result = json.deserialize(bytes, Point.class);

        assertEquals(10, result.x());
        assertEquals(20, result.y());
    }

    @Test
    @DisplayName("JSON: nested non-Serializable record round-trip")
    void jsonRoundTripNestedRecord() {
        var json = JsonSerializationProvider.INSTANCE;
        var container = new Container("test", new Point(5, 8));

        byte[] bytes = json.serialize(container);
        Container result = json.deserialize(bytes, Container.class);

        assertEquals("test", result.name());
        assertEquals(5, result.location().x());
        assertEquals(8, result.location().y());
    }

    // ── Java provider tests ──────────────────────────────────────────────────

    @Test
    @DisplayName("Java provider: non-Serializable type throws SerializationException")
    void javaProviderFailsOnNonSerializable() {
        var java = JavaSerializationProvider.INSTANCE;
        var point = new Point(1, 2); // Point does NOT implement Serializable

        assertThrows(SerializationException.class, () -> java.serialize(point));
    }

    @Test
    @DisplayName("Java provider: Serializable type round-trips correctly")
    void javaProviderSerializableTypeWorks() {
        var java = JavaSerializationProvider.INSTANCE;
        var point = new SerializablePoint(7, 14);

        byte[] bytes = java.serialize(point);
        SerializablePoint result = java.deserialize(bytes, SerializablePoint.class);

        assertEquals(7, result.x());
        assertEquals(14, result.y());
    }

    @Test
    @DisplayName("Java provider: JournalEntry (Serializable) round-trips correctly")
    void javaProviderJournalEntryWorks() {
        var java = JavaSerializationProvider.INSTANCE;
        // JournalEntry implements Serializable; payload must also be Serializable
        var entry = new JournalEntry<>(5L, "actor-a", new SerializablePoint(3, 9));

        byte[] bytes = java.serialize(entry);
        @SuppressWarnings("unchecked")
        JournalEntry<SerializablePoint> result = (JournalEntry<SerializablePoint>) java.deserialize(bytes, JournalEntry.class);

        assertEquals(5L, result.getSequenceNumber());
        assertEquals("actor-a", result.getActorId());
        assertEquals(3, result.getMessage().x());
    }

    // ── Provider name tests ──────────────────────────────────────────────────

    @Test
    @DisplayName("Provider names are correct")
    void providerNames() {
        assertEquals("kryo", KryoSerializationProvider.INSTANCE.name());
        assertEquals("json", JsonSerializationProvider.INSTANCE.name());
        assertEquals("java", JavaSerializationProvider.INSTANCE.name());
    }

    // ── FileMessageJournal integration tests ─────────────────────────────────

    @Test
    @DisplayName("FileMessageJournal: write and read back 3 entries with Kryo (non-Serializable payload)")
    void fileJournalKryoIntegration(@TempDir Path tempDir) throws Exception {
        var kryo = KryoSerializationProvider.INSTANCE;
        // Use cajun-persistence FileMessageJournal
        var journal = new FileMessageJournal<Point>(tempDir, kryo);

        Point p1 = new Point(1, 2);
        Point p2 = new Point(3, 4);
        Point p3 = new Point(5, 6);

        long seq0 = journal.append("actor-test", p1).get();
        long seq1 = journal.append("actor-test", p2).get();
        long seq2 = journal.append("actor-test", p3).get();

        assertEquals(0, seq0);
        assertEquals(1, seq1);
        assertEquals(2, seq2);

        List<JournalEntry<Point>> entries = journal.readFrom("actor-test", 0).get();
        assertEquals(3, entries.size());
        assertEquals(1, entries.get(0).getMessage().x());
        assertEquals(2, entries.get(0).getMessage().y());
        assertEquals(3, entries.get(1).getMessage().x());
        assertEquals(5, entries.get(2).getMessage().x());

        journal.close();
    }

    @Test
    @DisplayName("FileMessageJournal: write and read back entries with Java provider (backward compat)")
    void fileJournalJavaIntegration(@TempDir Path tempDir) throws Exception {
        var java = JavaSerializationProvider.INSTANCE;
        var journal = new FileMessageJournal<SerializablePoint>(tempDir, java);

        SerializablePoint p1 = new SerializablePoint(10, 20);
        SerializablePoint p2 = new SerializablePoint(30, 40);

        journal.append("actor-java", p1).get();
        journal.append("actor-java", p2).get();

        List<JournalEntry<SerializablePoint>> entries = journal.readFrom("actor-java", 0).get();
        assertEquals(2, entries.size());
        assertEquals(10, entries.get(0).getMessage().x());
        assertEquals(30, entries.get(1).getMessage().x());

        journal.close();
    }

    @Test
    @DisplayName("FileMessageJournal: default constructor uses Java provider (backward compat)")
    void fileJournalDefaultProviderIsJava(@TempDir Path tempDir) {
        var journal = new FileMessageJournal<>(tempDir);
        assertEquals("java", journal.getSerializationProvider().name());
        journal.close();
    }

    @Test
    @DisplayName("Kryo: round-trip with null field")
    void kryoRoundTripWithNullableField() {
        var kryo = KryoSerializationProvider.INSTANCE;
        // Container with null location — tests null handling
        Container container = new Container("null-test", null);

        byte[] bytes = kryo.serialize(container);
        Container result = kryo.deserialize(bytes, Container.class);

        assertEquals("null-test", result.name());
        assertNull(result.location());
    }
}
