package examples;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.cajun.Actor;
import systems.cajun.ActorSystem;
import systems.cajun.Pid;
import systems.cajun.config.BackpressureConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.Serializable;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Example test demonstrating a Cajun actor implementation of the 1 Billion Row Challenge.
 * This implementation uses multiple actors to demonstrate the viability of the actor model
 * for this problem without unsafe operations.
 */
public class BillionRowChallengeTest {
    private static final Logger logger = LoggerFactory.getLogger(BillionRowChallengeTest.class);
    private static final int NUM_WORKERS = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    private static final int BATCH_SIZE = 100000; // Increased batch size for better performance with large files
    private static final int MAILBOX_CAPACITY = 10000; // Increased mailbox capacity for large files
    private static final int PROGRESS_REPORT_LINES = 5000000; // Report progress every 5 million lines

    private ActorSystem system;

    @BeforeEach
    public void setup() {
        system = new ActorSystem();
    }

    @Test
    public void testBillionRowChallenge() throws Exception {
        // Load the weather stations CSV resource
        URL resource = Thread.currentThread().getContextClassLoader()
                .getResource("weather_stations.csv");
        assertNotNull(resource, "weather_stations.csv resource not found");
        File inputFile = new File(resource.toURI());
        logger.info("Input file: {}", inputFile.getAbsolutePath());
        logger.info("Starting 1 Billion Row Challenge test with {} workers", NUM_WORKERS);
        logger.info("File size: {} MB", inputFile.length() / (1024 * 1024));

        // Create a latch to wait for completion
        CountDownLatch latch = new CountDownLatch(1);

        // Create backpressure config
        BackpressureConfig backpressureConfig = new BackpressureConfig();
        backpressureConfig.setEnabled(true);
        backpressureConfig.setHighWatermark(0.9f); // Higher watermark for better throughput
        backpressureConfig.setLowWatermark(0.3f); // Higher low watermark

        // Create the coordinator actor with backpressure
        Pid coordinatorPid = system.register(CoordinatorActor.class, "brc-coordinator", backpressureConfig);
        CoordinatorActor coordinator = (CoordinatorActor) system.getActor(coordinatorPid);
        coordinator.setLatch(latch);

        // Create worker actors with backpressure
        List<Pid> workerPids = new ArrayList<>();
        for (int i = 0; i < NUM_WORKERS; i++) {
            Pid workerPid = system.register(WorkerActor.class, "brc-worker-" + i, backpressureConfig);
            WorkerActor worker = (WorkerActor) system.getActor(workerPid);
            worker.setCoordinatorPid(coordinatorPid);
            workerPids.add(workerPid);
        }
        coordinator.setWorkerPids(workerPids);

        // Start the processing
        long startTime = System.currentTimeMillis();
        coordinator.tell(new StartProcessing(inputFile.getAbsolutePath()));

        // Wait for completion with progress reporting
        boolean completed = false;
        long lastReportTime = System.currentTimeMillis();
        while (!completed && System.currentTimeMillis() - startTime < TimeUnit.MINUTES.toMillis(30)) { // 30 min timeout
            completed = latch.await(10, TimeUnit.SECONDS); // Check every 10 seconds
            
            // Report progress periodically
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastReportTime > 5000) { // Report every 5 seconds
                lastReportTime = currentTime;
                int processed = coordinator.getBatchesProcessed();
                int total = coordinator.getTotalBatches();
                if (total > 0) {
                    logger.info("Progress: {}/{} batches processed ({}%)", 
                        processed, total, String.format("%.2f", (processed * 100.0) / total));
                } else {
                    logger.info("Progress: {} batches processed (total unknown)", processed);
                }
            }
        }
        
        long endTime = System.currentTimeMillis();
        assertTrue(completed, "Processing did not complete within the timeout period");

        // Get and verify the results
        Map<String, MeasurementAggregator> results = coordinator.getResults();
        assertFalse(results.isEmpty(), "Results should not be empty");

        // Print the results in the required format
        printResults(results);

        // Generate and print detailed performance report
        printPerformanceReport(coordinator, endTime - startTime);
    }

    private void printPerformanceReport(CoordinatorActor coordinator, long totalProcessingTime) {
        int totalBatches = coordinator.getTotalBatches();
        int uniqueStations = coordinator.getResults().size();
        int messagesProcessed = coordinator.getBatchesProcessed();
        
        logger.info("\n===== 1 Billion Row Challenge Performance Report =====");
        logger.info("Total processing time: {} ms", totalProcessingTime);
        logger.info("Number of worker actors: {}", NUM_WORKERS);
        logger.info("Batch size: {}", BATCH_SIZE);
        logger.info("Total batches processed: {}", totalBatches);
        logger.info("Unique weather stations: {}", uniqueStations);
        
        if (totalBatches > 0) {
            String avgTimePerBatch = String.format("%.2f", (float) totalProcessingTime / totalBatches);
            logger.info("Average time per batch: {} ms", avgTimePerBatch);
        }
        
        if (uniqueStations > 0) {
            String avgTimePerStation = String.format("%.2f", (float) totalProcessingTime / uniqueStations);
            logger.info("Average time per station: {} ms", avgTimePerStation);
        }
        
        logger.info("Messages processed by coordinator: {}", messagesProcessed);
        
        String batchesPerSecond = String.format("%.2f", (totalBatches * 1000.0) / totalProcessingTime);
        logger.info("Throughput: {} batches/second", batchesPerSecond);
        
        String stationsPerSecond = String.format("%.2f", (uniqueStations * 1000.0) / totalProcessingTime);
        logger.info("Throughput: {} stations/second", stationsPerSecond);
        
        // Memory usage
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        String memoryUsageMB = String.format("%.2f", usedMemory / (1024.0 * 1024.0));
        logger.info("Memory usage: {} MB", memoryUsageMB);
        
        // Backpressure configuration
        logger.info("\n----- Backpressure Configuration -----");
        logger.info("Backpressure enabled: true");
        logger.info("High watermark: 0.9");
        logger.info("Low watermark: 0.3");
        logger.info("Mailbox capacity: {}", MAILBOX_CAPACITY);
        
        logger.info("=================================================\n");
    }

    private void printResults(Map<String, MeasurementAggregator> results) {
        // Sort results by station name
        Map<String, MeasurementAggregator> sortedResults = new TreeMap<>(results);

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;

        for (Map.Entry<String, MeasurementAggregator> entry : sortedResults.entrySet()) {
            if (!first) {
                sb.append(", ");
            }

            MeasurementAggregator agg = entry.getValue();
            sb.append(entry.getKey())
                    .append("=")
                    .append(formatMeasurement(agg.getMin()))
                    .append("/")
                    .append(formatMeasurement(agg.getAvg()))
                    .append("/")
                    .append(formatMeasurement(agg.getMax()));

            first = false;
        }

        sb.append("}");
        logger.info("Results: {}", sb);
    }

    private String formatMeasurement(double value) {
        // Format with one decimal place
        return String.format("%.1f", value);
    }

    // --- Messages ---

    public record StartProcessing(String filePath) implements Serializable {
    }

    public record ProcessBatch(List<String> lines, int batchId) implements Serializable {
    }

    public record BatchResult(Map<String, MeasurementAggregator> partialResults, int batchId)
            implements Serializable {
    }

    public record AllBatchesComplete() implements Serializable {
    }

    // --- Data classes ---

    public static class MeasurementAggregator implements Serializable {
        private double min = Double.MAX_VALUE;
        private double max = Double.MIN_VALUE;
        private double sum = 0;
        private int count = 0;

        public void addMeasurement(double value) {
            min = Math.min(min, value);
            max = Math.max(max, value);
            sum += value;
            count++;
        }

        public void merge(MeasurementAggregator other) {
            if (other.count > 0) {
                min = Math.min(min, other.min);
                max = Math.max(max, other.max);
                sum += other.sum;
                count += other.count;
            }
        }

        public double getMin() {
            return min;
        }

        public double getMax() {
            return max;
        }

        public double getAvg() {
            return count > 0 ? sum / count : 0;
        }

        public int getCount() {
            return count;
        }
    }

    // --- Actor definitions ---

    public static class CoordinatorActor extends Actor<Object> {
        private final Logger logger = LoggerFactory.getLogger(CoordinatorActor.class);
        private CountDownLatch latch;
        private List<Pid> workerPids;
        private final Map<String, MeasurementAggregator> results = new HashMap<>();
        private final AtomicInteger batchesProcessed = new AtomicInteger(0);
        private int totalBatches = 0;
        private long startTime;
        private long totalLines = 0;

        // Standard constructor required by ActorSystem.register()
        public CoordinatorActor(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        // Constructor with backpressure config
        public CoordinatorActor(ActorSystem system, String actorId, BackpressureConfig backpressureConfig) {
            super(system, actorId, backpressureConfig);
        }

        public void setLatch(CountDownLatch latch) {
            this.latch = latch;
        }

        public void setWorkerPids(List<Pid> workerPids) {
            this.workerPids = workerPids;
        }

        public Map<String, MeasurementAggregator> getResults() {
            return results;
        }

        public int getTotalBatches() {
            return totalBatches;
        }

        public int getBatchesProcessed() {
            return batchesProcessed.get();
        }

        @Override
        protected void receive(Object message) {
            if (message instanceof StartProcessing msg) {
                if (workerPids == null || workerPids.isEmpty()) {
                    logger.error("No worker actors configured");
                    if (latch != null) {
                        latch.countDown();
                    }
                    return;
                }

                startTime = System.currentTimeMillis();
                logger.info("Starting to process file: {}", msg.filePath());

                try {
                    // Read the file and distribute batches to workers
                    distributeWork(msg.filePath());
                } catch (Exception e) {
                    logger.error("Error processing file", e);
                    if (latch != null) {
                        latch.countDown();
                    }
                }
            } else if (message instanceof BatchResult result) {
                // Merge partial results from this batch
                for (Map.Entry<String, MeasurementAggregator> entry : result.partialResults().entrySet()) {
                    results.computeIfAbsent(entry.getKey(), k -> new MeasurementAggregator())
                            .merge(entry.getValue());
                }

                int processed = batchesProcessed.incrementAndGet();
                if (processed % 100 == 0 || processed == totalBatches) {
                    logger.info("Processed {}/{} batches", processed, totalBatches);
                }

                // Check if all batches are processed
                if (processed == totalBatches) {
                    long duration = System.currentTimeMillis() - startTime;
                    logger.info("All batches processed in {} ms", duration);

                    // Signal completion
                    if (latch != null) {
                        latch.countDown();
                    }
                }
            }
        }

        private void distributeWork(String filePath) throws Exception {
            List<String> batch = new ArrayList<>(BATCH_SIZE);
            int batchId = 0;
            int workerIndex = 0;
            long lineCount = 0;

            try (BufferedReader reader = new BufferedReader(new FileReader(filePath), 1024 * 1024)) { // 1MB buffer
                String line;
                while ((line = reader.readLine()) != null) {
                    batch.add(line);
                    lineCount++;

                    // Report progress on reading large files
                    if (lineCount % PROGRESS_REPORT_LINES == 0) {
                        logger.info("Read {} million lines from file", lineCount / 1_000_000);
                    }

                    if (batch.size() >= BATCH_SIZE) {
                        // Send this batch to a worker
                        Pid workerPid = workerPids.get(workerIndex);
                        workerPid.tell(new ProcessBatch(new ArrayList<>(batch), batchId++));

                        // Round-robin worker selection
                        workerIndex = (workerIndex + 1) % workerPids.size();

                        batch.clear();
                    }
                }

                // Send any remaining lines in the last batch
                if (!batch.isEmpty()) {
                    Pid workerPid = workerPids.get(workerIndex);
                    workerPid.tell(new ProcessBatch(new ArrayList<>(batch), batchId++));
                }
            }

            totalBatches = batchId;
            totalLines = lineCount;
            logger.info("Read {} total lines from file", totalLines);
            logger.info("Distributed {} batches to {} workers", totalBatches, workerPids.size());

            // If no batches were created (empty file), signal completion
            if (totalBatches == 0) {
                if (latch != null) {
                    latch.countDown();
                }
            }
        }
    }

    public static class WorkerActor extends Actor<Object> {
        private final Logger logger = LoggerFactory.getLogger(WorkerActor.class);
        private Pid coordinatorPid;

        // Standard constructor required by ActorSystem.register()
        public WorkerActor(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        // Constructor with backpressure config
        public WorkerActor(ActorSystem system, String actorId, BackpressureConfig backpressureConfig) {
            super(system, actorId, backpressureConfig);
        }

        public void setCoordinatorPid(Pid coordinatorPid) {
            this.coordinatorPid = coordinatorPid;
        }

        @Override
        protected void receive(Object message) {
            if (message instanceof ProcessBatch batch) {
                if (coordinatorPid == null) {
                    logger.error("Coordinator PID is null, cannot process batch");
                    return;
                }

                // Process the batch and calculate partial results
                Map<String, MeasurementAggregator> partialResults = processBatch(batch.lines());

                // Send results back to coordinator
                coordinatorPid.tell(new BatchResult(partialResults, batch.batchId()));
            }
        }

        private Map<String, MeasurementAggregator> processBatch(List<String> lines) {
            Map<String, MeasurementAggregator> results = new HashMap<>();

            for (String line : lines) {
                // Parse the line: format is "station;temperature"
                String[] parts = line.split(";");
                if (parts.length == 2) {
                    String station = parts[0];
                    try {
                        double temperature = Double.parseDouble(parts[1]);

                        // Update the aggregator for this station
                        results.computeIfAbsent(station, k -> new MeasurementAggregator())
                                .addMeasurement(temperature);
                    } catch (NumberFormatException e) {
                        logger.warn("Invalid temperature value in line: {}", line);
                    }
                } else {
                    logger.warn("Invalid line format: {}", line);
                }
            }

            return results;
        }
    }
}
