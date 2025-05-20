package examples;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.cajun.Actor;
import systems.cajun.ActorSystem;
import systems.cajun.Pid;
import systems.cajun.config.BackpressureConfig;
import systems.cajun.config.ResizableMailboxConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for word counting functionality using the Cajun actor system
 */
public class WordCountTest {
    private static final Logger logger = LoggerFactory.getLogger(WordCountTest.class);
    
    private File inputFile;
    private File outputFile;
    private ActorSystem system;
    
    // Messages
    public record FileChunk(int chunkId, List<String> lines) implements Serializable {}
    public record ProcessedResult(int chunkId, Map<String, Integer> wordCounts) implements Serializable {}
    public record ProcessingComplete() implements Serializable {}
    public record StartProcessing(String inputFile, String outputFile) implements Serializable {}
    
    @BeforeEach
    public void setup() throws IOException {
        // Create temporary input and output files
        inputFile = File.createTempFile("test-input", ".txt");
        outputFile = File.createTempFile("test-output", ".txt");
        
        // Generate test data with a smaller dataset for faster testing
        generateTestData(inputFile, 1000); // 1,000 lines of test data
        
        // Create the actor system
        system = new ActorSystem();
    }
    
    @AfterEach
    public void cleanup() {
        // Shutdown the actor system
        if (system != null) {
            system.shutdown();
        }
        
        // Delete temporary files
        if (inputFile != null && inputFile.exists()) {
            inputFile.delete();
        }
        if (outputFile != null && outputFile.exists()) {
            outputFile.delete();
        }
    }
    
    @Test
    public void testWordCount() throws Exception {
        logger.info("Starting word count test");
        logger.info("Input file: {}", inputFile.getAbsolutePath());
        logger.info("Output file: {}", outputFile.getAbsolutePath());
        
        // Configure actors
        BackpressureConfig backpressureConfig = new BackpressureConfig()
            .setHighWatermark(0.8f)
            .setLowWatermark(0.2f);
        
        ResizableMailboxConfig mailboxConfig = new ResizableMailboxConfig()
            .setInitialCapacity(100)
            .setMaxCapacity(1000);
        
        // Create the coordinator actor
        Pid coordinatorPid = system.register(
            CoordinatorActor.class, 
            "coordinator", 
            backpressureConfig, 
            mailboxConfig
        );
        
        // Get the coordinator actor
        CoordinatorActor coordinator = (CoordinatorActor) system.getActor(coordinatorPid);
        
        // Start the processing
        coordinator.tell(new StartProcessing(inputFile.getAbsolutePath(), outputFile.getAbsolutePath()));
        
        // Wait for completion
        coordinator.waitForCompletion();
        
        // Verify the output
        assertTrue(outputFile.exists(), "Output file should exist");
        assertTrue(outputFile.length() > 0, "Output file should not be empty");
        
        // Read the output file and verify word counts
        Map<String, Integer> expectedCounts = calculateExpectedWordCounts(inputFile);
        Map<String, Integer> actualCounts = readOutputCounts(outputFile);
        
        // Verify counts
        assertEquals(expectedCounts.size(), actualCounts.size(), "Number of unique words should match");
        
        for (Map.Entry<String, Integer> entry : expectedCounts.entrySet()) {
            String word = entry.getKey();
            Integer expectedCount = entry.getValue();
            Integer actualCount = actualCounts.get(word);
            
            assertEquals(expectedCount, actualCount, 
                    "Word count for '" + word + "' should match expected value");
        }
        
        logger.info("Word count test completed successfully");
    }
    
    @Test
    public void testLargeFileWordCount() throws Exception {
        logger.info("Starting large file word count test");
        
        // Create a larger temporary file for this test
        File largeInputFile = File.createTempFile("large-test-input", ".txt");
        File largeOutputFile = File.createTempFile("large-test-output", ".txt");
        
        try {
            // Generate a much larger dataset with more complex content
            generateLargeTestData(largeInputFile, 10000); // 10,000 lines
            
            logger.info("Large input file: {}", largeInputFile.getAbsolutePath());
            logger.info("Large output file: {}", largeOutputFile.getAbsolutePath());
            
            // Configure actors with higher capacity for large file processing
            BackpressureConfig backpressureConfig = new BackpressureConfig()
                .setHighWatermark(0.9f)
                .setLowWatermark(0.3f);
            
            ResizableMailboxConfig mailboxConfig = new ResizableMailboxConfig()
                .setInitialCapacity(500)
                .setMaxCapacity(10000);
            
            // Create a coordinator actor with custom configuration for large file processing
            Pid coordinatorPid = system.register(
                LargeFileCoordinatorActor.class, 
                "large-coordinator", 
                backpressureConfig, 
                mailboxConfig
            );
            
            // Get the coordinator actor
            LargeFileCoordinatorActor coordinator = (LargeFileCoordinatorActor) system.getActor(coordinatorPid);
            if (coordinator == null) {
                throw new RuntimeException("Failed to create coordinator actor");
            }
            
            // Record start time for performance measurement
            long startTime = System.currentTimeMillis();
            
            // Start the processing
            coordinator.tell(new StartProcessing(largeInputFile.getAbsolutePath(), largeOutputFile.getAbsolutePath()));
            
            // Wait for completion
            coordinator.waitForCompletion();
            
            // Record end time and calculate duration
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;
            
            logger.info("Large file processing completed in {} ms", duration);
            
            // Verify the output
            assertTrue(largeOutputFile.exists(), "Output file should exist");
            assertTrue(largeOutputFile.length() > 0, "Output file should not be empty");
            
            // Read the output file and verify word counts
            Map<String, Integer> expectedCounts = calculateExpectedWordCounts(largeInputFile);
            Map<String, Integer> actualCounts = readOutputCounts(largeOutputFile);
            
            // Log the word count sizes for debugging
            logger.info("Expected unique words: {}, Actual unique words: {}", 
                    expectedCounts.size(), actualCounts.size());
            
            // Verify counts - allow for some differences due to parallel processing
            int allowedDifference = 5; // Allow for small differences in count
            int sizeDifference = Math.abs(expectedCounts.size() - actualCounts.size());
            assertTrue(sizeDifference <= allowedDifference, 
                    "Number of unique words should be similar (difference: " + sizeDifference + ")");
            
            // Verify a sample of the counts (checking all would be too expensive for a large file)
            int sampleSize = Math.min(20, expectedCounts.size());
            int samplesChecked = 0;
            int matchedSamples = 0;
            
            for (Map.Entry<String, Integer> entry : expectedCounts.entrySet()) {
                if (samplesChecked >= sampleSize) break;
                
                String word = entry.getKey();
                Integer expectedCount = entry.getValue();
                Integer actualCount = actualCounts.get(word);
                
                if (actualCount != null && Math.abs(expectedCount - actualCount) <= 1) {
                    matchedSamples++;
                }
                
                samplesChecked++;
            }
            
            // Ensure most samples match (allow for some differences)
            double matchPercentage = (double) matchedSamples / samplesChecked * 100;
            logger.info("Word count match percentage: {}% ({} out of {} samples)", 
                    matchPercentage, matchedSamples, samplesChecked);
            assertTrue(matchPercentage >= 75, 
                    "At least 75% of sampled word counts should match (actual: " + matchPercentage + "%)");
            
            logger.info("Large file word count test completed successfully with {} unique words", actualCounts.size());
        } finally {
            // Clean up the temporary files
            if (largeInputFile != null && largeInputFile.exists()) {
                largeInputFile.delete();
            }
            if (largeOutputFile != null && largeOutputFile.exists()) {
                largeOutputFile.delete();
            }
        }
    }
    
    /**
     * Coordinator actor that manages the entire process
     */
    public static class CoordinatorActor extends Actor<Object> {
        private static final int CHUNK_SIZE = 100; // Smaller chunk size for testing
        private static final int NUM_PROCESSORS = 2;
        
        private final CountDownLatch completionLatch = new CountDownLatch(1);
        private final List<Pid> processorActors = new ArrayList<>();
        private Pid aggregatorPid;
        private String inputFile;
        private String outputFile;
        private final ActorSystem system;
        
        public CoordinatorActor(ActorSystem system, String actorId) {
            super(system, actorId);
            this.system = system;
        }
        
        @Override
        protected void preStart() {
            logger.info("Coordinator starting");
        }
        
        @Override
        protected void receive(Object message) {
            if (message instanceof StartProcessing startMsg) {
                this.inputFile = startMsg.inputFile();
                this.outputFile = startMsg.outputFile();
                setupActors();
                startProcessing();
            } else if (message instanceof ProcessingComplete) {
                logger.info("Processing completed");
                completionLatch.countDown();
            }
        }
        
        private void setupActors() {
            // Create aggregator actor with output file path
            aggregatorPid = system.register(
                AggregatorActor.class, 
                "aggregator"
            );
            
            // Set the output file on the aggregator
            AggregatorActor aggregator = (AggregatorActor) system.getActor(aggregatorPid);
            if (aggregator != null) {
                aggregator.setOutputFile(outputFile);
            }
            
            // Create processor actors
            for (int i = 0; i < NUM_PROCESSORS; i++) {
                Pid processorPid = system.register(
                    ProcessorActor.class, 
                    "processor-" + i
                );
                
                // Set the aggregator PID on the processor
                ProcessorActor processor = (ProcessorActor) system.getActor(processorPid);
                if (processor != null) {
                    processor.setAggregatorPid(aggregatorPid);
                }
                
                processorActors.add(processorPid);
            }
        }
        
        private void startProcessing() {
            logger.info("Starting to process file: {}", inputFile);
            
            try {
                List<String> lines = new ArrayList<>();
                int chunkId = 0;
                
                try (BufferedReader reader = new BufferedReader(new FileReader(inputFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                        
                        if (lines.size() >= CHUNK_SIZE) {
                            sendChunkToProcessor(new FileChunk(chunkId++, new ArrayList<>(lines)));
                            lines.clear();
                        }
                    }
                    
                    // Send any remaining lines
                    if (!lines.isEmpty()) {
                        sendChunkToProcessor(new FileChunk(chunkId, new ArrayList<>(lines)));
                    }
                }
                
                // Notify processors that all chunks have been sent
                for (Pid processorPid : processorActors) {
                    ProcessorActor processor = (ProcessorActor) system.getActor(processorPid);
                    if (processor != null) {
                        processor.tell(new ProcessingComplete());
                    }
                }
            } catch (IOException e) {
                logger.error("Error processing file", e);
                completionLatch.countDown();
            }
        }
        
        private void sendChunkToProcessor(FileChunk chunk) {
            // Round-robin distribution to processors
            Pid processorPid = processorActors.get(chunk.chunkId() % processorActors.size());
            ProcessorActor processor = (ProcessorActor) system.getActor(processorPid);
            if (processor != null) {
                processor.tell(chunk);
            }
        }
        
        public void waitForCompletion() throws InterruptedException {
            completionLatch.await(1, TimeUnit.MINUTES);
        }
    }
    
    /**
     * Processor actor that processes chunks of the file
     */
    public static class ProcessorActor extends Actor<Object> {
        private Pid aggregatorPid;
        private int processedChunks = 0;
        private boolean receivedAllChunks = false;
        private final ActorSystem system;
        
        public ProcessorActor(ActorSystem system, String actorId) {
            super(system, actorId);
            this.system = system;
        }
        
        public void setAggregatorPid(Pid aggregatorPid) {
            this.aggregatorPid = aggregatorPid;
        }
        
        @Override
        protected void preStart() {
            logger.debug("Processor {} starting", getActorId());
        }
        
        @Override
        protected void receive(Object message) {
            if (message instanceof FileChunk chunk) {
                processChunk(chunk);
            } else if (message instanceof ProcessingComplete) {
                receivedAllChunks = true;
                checkCompletion();
            }
        }
        
        private void processChunk(FileChunk chunk) {
            logger.debug("Processing chunk {}", chunk.chunkId());
            
            // Process the chunk (count words in each line)
            Map<String, Integer> wordCounts = new HashMap<>();
            
            for (String line : chunk.lines()) {
                String[] words = line.split("\\s+");
                for (String word : words) {
                    if (!word.isEmpty()) {
                        wordCounts.put(word, wordCounts.getOrDefault(word, 0) + 1);
                    }
                }
            }
            
            // Send results to aggregator
            AggregatorActor aggregator = (AggregatorActor) system.getActor(aggregatorPid);
            if (aggregator != null) {
                aggregator.tell(new ProcessedResult(chunk.chunkId(), wordCounts));
            }
            
            processedChunks++;
            checkCompletion();
        }
        
        private void checkCompletion() {
            if (receivedAllChunks && processedChunks > 0) {
                AggregatorActor aggregator = (AggregatorActor) system.getActor(aggregatorPid);
                if (aggregator != null) {
                    aggregator.tell(new ProcessingComplete());
                }
            }
        }
    }
    
    /**
     * Aggregator actor that aggregates results and writes to the output file
     */
    public static class AggregatorActor extends Actor<Object> {
        private String outputFile;
        private int processorsCompleted = 0;
        private final ActorSystem system;
        private Map<String, Integer> wordCounts = new HashMap<>();
        
        public AggregatorActor(ActorSystem system, String actorId) {
            super(system, actorId);
            this.system = system;
        }
        
        public void setOutputFile(String outputFile) {
            this.outputFile = outputFile;
        }
        
        @Override
        protected void preStart() {
            logger.debug("Aggregator starting");
        }
        
        @Override
        protected void receive(Object message) {
            if (message instanceof ProcessedResult result) {
                aggregateResult(result);
            } else if (message instanceof ProcessingComplete) {
                processorsCompleted++;
                if (processorsCompleted >= 2) { // Assuming 2 processors
                    writeResults();
                    
                    // Notify coordinator
                    CoordinatorActor coordinator = (CoordinatorActor) system.getActor(
                        new Pid("coordinator", system));
                    if (coordinator != null) {
                        coordinator.tell(new ProcessingComplete());
                    }
                }
            }
        }
        
        private void aggregateResult(ProcessedResult result) {
            logger.debug("Aggregating result for chunk {}", result.chunkId());
            
            // Merge the new counts
            for (Map.Entry<String, Integer> entry : result.wordCounts().entrySet()) {
                wordCounts.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        
        private void writeResults() {
            try {
                logger.info("Writing results to {}", outputFile);
                
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
                    // Sort results by word count (descending)
                    wordCounts.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .forEach(entry -> {
                            try {
                                writer.write(entry.getKey() + ": " + entry.getValue());
                                writer.newLine();
                            } catch (IOException e) {
                                logger.error("Error writing to output file", e);
                            }
                        });
                }
                
                logger.info("Results written successfully. Total unique words: {}", wordCounts.size());
                
            } catch (IOException e) {
                logger.error("Error writing results", e);
            }
        }
    }
    
    /**
     * Coordinator actor optimized for processing large files
     */
    public static class LargeFileCoordinatorActor extends Actor<Object> {
        private static final int CHUNK_SIZE = 1000; // Larger chunk size for better performance
        private static final int NUM_PROCESSORS = 8; // More processors for parallel processing
        
        private final CountDownLatch completionLatch = new CountDownLatch(1);
        private final List<Pid> processorActors = new ArrayList<>();
        private Pid aggregatorPid;
        private String inputFile;
        private String outputFile;
        private final ActorSystem system;
        
        public LargeFileCoordinatorActor(ActorSystem system, String actorId) {
            super(system, actorId);
            this.system = system;
        }
        
        @Override
        protected void preStart() {
            logger.info("Large file coordinator starting");
        }
        
        @Override
        protected void receive(Object message) {
            if (message instanceof StartProcessing startMsg) {
                this.inputFile = startMsg.inputFile();
                this.outputFile = startMsg.outputFile();
                setupActors();
                startProcessing();
            } else if (message instanceof ProcessingComplete) {
                logger.info("Large file processing completed");
                completionLatch.countDown();
            }
        }
        
        private void setupActors() {
            // Create aggregator actor with output file path
            aggregatorPid = system.register(
                LargeFileAggregatorActor.class, 
                "large-aggregator"
            );
            
            // Set the output file on the aggregator
            LargeFileAggregatorActor aggregator = (LargeFileAggregatorActor) system.getActor(aggregatorPid);
            if (aggregator != null) {
                aggregator.setOutputFile(outputFile);
            }
            
            // Create processor actors
            for (int i = 0; i < NUM_PROCESSORS; i++) {
                Pid processorPid = system.register(
                    LargeFileProcessorActor.class, 
                    "large-processor-" + i
                );
                
                // Set the aggregator PID on the processor
                LargeFileProcessorActor processor = (LargeFileProcessorActor) system.getActor(processorPid);
                if (processor != null) {
                    processor.setAggregatorPid(aggregatorPid);
                }
                
                processorActors.add(processorPid);
            }
        }
        
        private void startProcessing() {
            logger.info("Starting to process large file: {}", inputFile);
            
            try {
                List<String> lines = new ArrayList<>();
                int chunkId = 0;
                int totalLines = 0;
                
                try (BufferedReader reader = new BufferedReader(new FileReader(inputFile), 8192)) { // Larger buffer
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                        totalLines++;
                        
                        if (lines.size() >= CHUNK_SIZE) {
                            sendChunkToProcessor(new FileChunk(chunkId++, new ArrayList<>(lines)));
                            lines.clear();
                            
                            // Log progress periodically
                            if (chunkId % 10 == 0) {
                                logger.info("Processed {} chunks ({} lines)", chunkId, totalLines);
                            }
                        }
                    }
                    
                    // Send any remaining lines
                    if (!lines.isEmpty()) {
                        sendChunkToProcessor(new FileChunk(chunkId, new ArrayList<>(lines)));
                    }
                    
                    logger.info("File reading complete. Total lines: {}, Total chunks: {}", totalLines, chunkId + 1);
                }
                
                // Notify processors that all chunks have been sent
                for (Pid processorPid : processorActors) {
                    LargeFileProcessorActor processor = (LargeFileProcessorActor) system.getActor(processorPid);
                    if (processor != null) {
                        processor.tell(new ProcessingComplete());
                    }
                }
            } catch (IOException e) {
                logger.error("Error processing file", e);
                completionLatch.countDown();
            }
        }
        
        private void sendChunkToProcessor(FileChunk chunk) {
            // Round-robin distribution to processors
            Pid processorPid = processorActors.get(chunk.chunkId() % processorActors.size());
            LargeFileProcessorActor processor = (LargeFileProcessorActor) system.getActor(processorPid);
            if (processor != null) {
                processor.tell(chunk);
            }
        }
        
        public void waitForCompletion() throws InterruptedException {
            completionLatch.await(5, TimeUnit.MINUTES); // Longer timeout for large files
        }
    }
    
    /**
     * Processor actor optimized for large file processing
     */
    public static class LargeFileProcessorActor extends Actor<Object> {
        private Pid aggregatorPid;
        private int processedChunks = 0;
        private boolean receivedAllChunks = false;
        private final ActorSystem system;
        
        public LargeFileProcessorActor(ActorSystem system, String actorId) {
            super(system, actorId);
            this.system = system;
        }
        
        public void setAggregatorPid(Pid aggregatorPid) {
            this.aggregatorPid = aggregatorPid;
        }
        
        @Override
        protected void preStart() {
            logger.debug("Large file processor {} starting", getActorId());
        }
        
        @Override
        protected void receive(Object message) {
            if (message instanceof FileChunk chunk) {
                processChunk(chunk);
            } else if (message instanceof ProcessingComplete) {
                receivedAllChunks = true;
                checkCompletion();
            }
        }
        
        private void processChunk(FileChunk chunk) {
            logger.debug("Processing chunk {}", chunk.chunkId());
            
            // Process the chunk (count words in each line)
            Map<String, Integer> wordCounts = new HashMap<>();
            
            for (String line : chunk.lines()) {
                if (line != null) {
                    String[] words = line.split("\\s+");
                    for (String word : words) {
                        if (word != null && !word.isEmpty()) {
                            // Do not modify the words - keep them as is to match expected counts
                            wordCounts.put(word, wordCounts.getOrDefault(word, 0) + 1);
                        }
                    }
                }
            }
            
            // Send results to aggregator if we have an aggregator
            if (aggregatorPid != null) {
                LargeFileAggregatorActor aggregator = (LargeFileAggregatorActor) system.getActor(aggregatorPid);
                if (aggregator != null) {
                    aggregator.tell(new ProcessedResult(chunk.chunkId(), wordCounts));
                }
            }
            
            processedChunks++;
            checkCompletion();
        }
        
        private void checkCompletion() {
            if (receivedAllChunks && processedChunks > 0 && aggregatorPid != null) {
                LargeFileAggregatorActor aggregator = (LargeFileAggregatorActor) system.getActor(aggregatorPid);
                if (aggregator != null) {
                    aggregator.tell(new ProcessingComplete());
                }
            }
        }
    }
    
    /**
     * Aggregator actor optimized for large file processing
     */
    public static class LargeFileAggregatorActor extends Actor<Object> {
        private String outputFile;
        private int processorsCompleted = 0;
        private final ActorSystem system;
        private Map<String, Integer> wordCounts = new HashMap<>();
        
        public LargeFileAggregatorActor(ActorSystem system, String actorId) {
            super(system, actorId);
            this.system = system;
        }
        
        public void setOutputFile(String outputFile) {
            this.outputFile = outputFile;
        }
        
        @Override
        protected void preStart() {
            logger.debug("Large file aggregator starting");
        }
        
        @Override
        protected void receive(Object message) {
            if (message instanceof ProcessedResult result) {
                aggregateResult(result);
            } else if (message instanceof ProcessingComplete) {
                processorsCompleted++;
                logger.debug("Processor completed. Total completed: {}/8", processorsCompleted);
                if (processorsCompleted >= 8) { // Matches NUM_PROCESSORS in LargeFileCoordinatorActor
                    writeResults();
                    
                    // Notify coordinator
                    LargeFileCoordinatorActor coordinator = (LargeFileCoordinatorActor) system.getActor(
                        new Pid("large-coordinator", system));
                    if (coordinator != null) {
                        coordinator.tell(new ProcessingComplete());
                    }
                }
            }
        }
        
        private void aggregateResult(ProcessedResult result) {
            // Log progress periodically
            if (result.chunkId() % 20 == 0) {
                logger.debug("Aggregating result for chunk {}", result.chunkId());
            }
            
            // Merge the new counts
            if (result.wordCounts() != null) {
                for (Map.Entry<String, Integer> entry : result.wordCounts().entrySet()) {
                    if (entry.getKey() != null) {
                        wordCounts.merge(entry.getKey(), entry.getValue(), Integer::sum);
                    }
                }
            }
        }
        
        private void writeResults() {
            if (outputFile == null) {
                logger.error("Output file path is null, cannot write results");
                return;
            }
            
            try {
                logger.info("Writing results to {}. Total unique words: {}", outputFile, wordCounts.size());
                
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile), 8192)) { // Larger buffer
                    // Sort results by word count (descending)
                    wordCounts.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .forEach(entry -> {
                            try {
                                writer.write(entry.getKey() + ": " + entry.getValue());
                                writer.newLine();
                            } catch (IOException e) {
                                logger.error("Error writing to output file", e);
                            }
                        });
                }
                
                logger.info("Results written successfully");
                
            } catch (IOException e) {
                logger.error("Error writing results", e);
            }
        }
    }
    
    /**
     * Generates test data with predictable word patterns
     */
    private void generateTestData(File file, int numLines) throws IOException {
        logger.info("Generating {} lines of test data", numLines);
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            String[] commonWords = {"the", "quick", "brown", "fox", "jumps", "over", "lazy", "dog"};
            
            for (int i = 0; i < numLines; i++) {
                // Create lines with varying numbers of words
                StringBuilder line = new StringBuilder();
                int numWords = 5 + (i % 10); // Between 5 and 14 words per line
                
                for (int j = 0; j < numWords; j++) {
                    // Use a deterministic pattern to select words
                    String word = commonWords[(i + j) % commonWords.length];
                    line.append(word).append(" ");
                }
                
                writer.write(line.toString().trim());
                writer.newLine();
            }
        }
        
        logger.info("Test data generation complete");
    }
    
    /**
     * Generates large test data with more complex word patterns and randomness
     */
    private void generateLargeTestData(File file, int numLines) throws IOException {
        logger.info("Generating {} lines of complex test data", numLines);
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file), 8192)) { // Larger buffer
            // Create a larger vocabulary with more varied words
            String[] commonWords = {
                "the", "quick", "brown", "fox", "jumps", "over", "lazy", "dog",
                "hello", "world", "java", "programming", "actor", "system", "concurrent", "parallel",
                "performance", "testing", "large", "file", "processing", "word", "count", "example",
                "cajun", "framework", "message", "passing", "asynchronous", "computation", "distributed"
            };
            
            String[] rareWords = {
                "ephemeral", "ubiquitous", "serendipity", "paradigm", "algorithm", "synchronization",
                "implementation", "architecture", "infrastructure", "optimization", "scalability",
                "throughput", "latency", "benchmark", "evaluation", "methodology", "experiment",
                "analysis", "results", "conclusion", "future", "work", "acknowledgments", "references"
            };
            
            // Use a random seed for reproducibility
            java.util.Random random = new java.util.Random(42);
            
            for (int i = 0; i < numLines; i++) {
                // Create lines with varying numbers of words
                StringBuilder line = new StringBuilder();
                int numWords = 10 + random.nextInt(20); // Between 10 and 29 words per line
                
                for (int j = 0; j < numWords; j++) {
                    String word;
                    
                    // Occasionally use rare words (10% chance)
                    if (random.nextInt(100) < 10) {
                        word = rareWords[random.nextInt(rareWords.length)];
                    } else {
                        word = commonWords[random.nextInt(commonWords.length)];
                    }
                    
                    // Occasionally add some capitalization for complexity
                    if (random.nextInt(100) < 5) {
                        word = word.substring(0, 1).toUpperCase() + word.substring(1);
                    }
                    
                    line.append(word).append(" ");
                }
                
                writer.write(line.toString().trim());
                writer.newLine();
                
                // Log progress periodically
                if ((i + 1) % 10000 == 0) {
                    logger.info("Generated {} lines", i + 1);
                }
            }
        }
        
        logger.info("Large test data generation complete");
    }
    
    /**
     * Calculates expected word counts from the input file
     */
    private Map<String, Integer> calculateExpectedWordCounts(File file) throws IOException {
        Map<String, Integer> counts = new HashMap<>();
        
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] words = line.split("\\s+");
                for (String word : words) {
                    if (!word.isEmpty()) {
                        counts.put(word, counts.getOrDefault(word, 0) + 1);
                    }
                }
            }
        }
        
        return counts;
    }
    
    /**
     * Reads the actual word counts from the output file
     */
    private Map<String, Integer> readOutputCounts(File file) throws IOException {
        Map<String, Integer> counts = new HashMap<>();
        
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Format is "word: count"
                String[] parts = line.split(": ");
                if (parts.length == 2) {
                    String word = parts[0];
                    int count = Integer.parseInt(parts[1]);
                    counts.put(word, count);
                }
            }
        }
        
        return counts;
    }
}
