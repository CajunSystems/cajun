package com.cajunsystems.benchmarks;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Analyzer for LMDB vs File-based persistence benchmark results.
 * 
 * This class processes JMH benchmark output and generates comprehensive
 * comparison reports showing the performance impact of LMDB integration.
 */
public class LmdbComparisonAnalyzer {
    
    private static final Pattern BENCHMARK_PATTERN = Pattern.compile(
        "(?<test>.*(?:FileBased|LMDB).*)\\s+(?<mode>Throughput|AverageTime)\\s+(?<score>[\\d.]+)\\s+(?<unit>\\S+)"
    );
    
    private static final Map<String, String> TEST_DESCRIPTIONS = Map.of(
        "singleStateUpdate", "Single state update operation",
        "stateRead", "State read operation",
        "batchStateUpdates", "Batch state updates (100 operations)",
        "statefulComputation", "Stateful computation with Fibonacci",
        "multipleStatefulActors", "Multiple concurrent stateful actors (10 actors)",
        "highFrequencyUpdates", "High-frequency state updates (1000 operations)",
        "stateReset", "State reset operation"
    );

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java LmdbComparisonAnalyzer <benchmark-output-file>");
            System.out.println("Example: java LmdbComparisonAnalyzer benchmark-results.txt");
            return;
        }
        
        String inputFile = args[0];
        LmdbComparisonAnalyzer analyzer = new LmdbComparisonAnalyzer();
        
        try {
            analyzer.analyzeBenchmarkResults(inputFile);
        } catch (IOException e) {
            System.err.println("Error analyzing benchmark results: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public void analyzeBenchmarkResults(String inputFile) throws IOException {
        System.out.println("🔍 Analyzing LMDB Benchmark Results");
        System.out.println("=====================================");
        
        List<BenchmarkResult> results = parseBenchmarkFile(inputFile);
        
        if (results.isEmpty()) {
            System.out.println("❌ No benchmark results found in file: " + inputFile);
            return;
        }
        
        // Generate comparison report
        generateComparisonReport(results);
        
        // Save detailed results to CSV
        saveToCsv(results, "lmdb_benchmark_results.csv");
        
        System.out.println("✅ Analysis complete! Results saved to:");
        System.out.println("   - Console output (below)");
        System.out.println("   - CSV file: lmdb_benchmark_results.csv");
    }
    
    private List<BenchmarkResult> parseBenchmarkFile(String inputFile) throws IOException {
        List<BenchmarkResult> results = new ArrayList<>();
        
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(inputFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = BENCHMARK_PATTERN.matcher(line);
                if (matcher.find()) {
                    BenchmarkResult result = new BenchmarkResult(
                        matcher.group("test"),
                        matcher.group("mode"),
                        Double.parseDouble(matcher.group("score")),
                        matcher.group("unit")
                    );
                    results.add(result);
                }
            }
        }
        
        return results;
    }
    
    private void generateComparisonReport(List<BenchmarkResult> results) {
        System.out.println();
        System.out.println("📊 LMDB vs File-Based Persistence Performance Comparison");
        System.out.println("==========================================================");
        System.out.println();
        
        // Group results by test name
        Map<String, List<BenchmarkResult>> groupedResults = groupResultsByTest(results);
        
        // Generate comparison table
        System.out.println("┌─────────────────────────────────┬─────────────────┬─────────────────┬─────────────────┬─────────────────┐");
        System.out.println("│ Test                            │ File-Based      │ LMDB            │ Improvement     │ Impact          │");
        System.out.println("├─────────────────────────────────┼─────────────────┼─────────────────┼─────────────────┼─────────────────┤");
        
        for (String testKey : groupedResults.keySet().stream().sorted().toList()) {
            List<BenchmarkResult> testResults = groupedResults.get(testKey);
            
            BenchmarkResult fileBased = findResult(testResults, "FileBased");
            BenchmarkResult lmdb = findResult(testResults, "LMDB");
            
            if (fileBased != null && lmdb != null) {
                String improvement = calculateImprovement(fileBased, lmdb);
                String impact = assessImpact(fileBased, lmdb);
                
                String testName = TEST_DESCRIPTIONS.getOrDefault(testKey, testKey);
                String paddedTestName = padRight(testName, 32);
                
                System.out.printf("│ %-32s │ %-15s │ %-15s │ %-15s │ %-15s │%n",
                    paddedTestName,
                    formatScore(fileBased),
                    formatScore(lmdb),
                    improvement,
                    impact
                );
            }
        }
        
        System.out.println("└─────────────────────────────────┴─────────────────┴─────────────────┴─────────────────┴─────────────────┘");
        System.out.println();
        
        // Generate summary statistics
        generateSummaryStatistics(groupedResults);
        
        // Generate recommendations
        generateRecommendations(groupedResults);
    }
    
    private Map<String, List<BenchmarkResult>> groupResultsByTest(List<BenchmarkResult> results) {
        Map<String, List<BenchmarkResult>> grouped = new TreeMap<>();
        
        for (BenchmarkResult result : results) {
            String testKey = extractTestKey(result.testName());
            grouped.computeIfAbsent(testKey, k -> new ArrayList<>()).add(result);
        }
        
        return grouped;
    }
    
    private String extractTestKey(String testName) {
        // Extract the base test name (e.g., "singleStateUpdate" from "singleStateUpdate_FileBased")
        if (testName.contains("_FileBased")) {
            return testName.substring(0, testName.indexOf("_FileBased"));
        } else if (testName.contains("_LMDB")) {
            return testName.substring(0, testName.indexOf("_LMDB"));
        }
        return testName;
    }
    
    private BenchmarkResult findResult(List<BenchmarkResult> results, String type) {
        return results.stream()
            .filter(r -> r.testName().contains(type))
            .filter(r -> r.mode().equals("Throughput") || r.mode().equals("AverageTime"))
            .findFirst()
            .orElse(null);
    }
    
    private String calculateImprovement(BenchmarkResult fileBased, BenchmarkResult lmdb) {
        if (fileBased.mode().equals("Throughput")) {
            // For throughput, higher is better
            double improvement = ((lmdb.score() - fileBased.score()) / fileBased.score()) * 100;
            return String.format("%+.1f%%", improvement);
        } else {
            // For average time, lower is better
            double improvement = ((fileBased.score() - lmdb.score()) / fileBased.score()) * 100;
            return String.format("%+.1f%%", improvement);
        }
    }
    
    private String assessImpact(BenchmarkResult fileBased, BenchmarkResult lmdb) {
        double improvementPercent;
        
        if (fileBased.mode().equals("Throughput")) {
            improvementPercent = ((lmdb.score() - fileBased.score()) / fileBased.score()) * 100;
        } else {
            improvementPercent = ((fileBased.score() - lmdb.score()) / fileBased.score()) * 100;
        }
        
        if (Math.abs(improvementPercent) < 5) {
            return "🟡 Neutral";
        } else if (improvementPercent > 0) {
            return "🟢 Positive";
        } else {
            return "🔴 Negative";
        }
    }
    
    private String formatScore(BenchmarkResult result) {
        if (result.mode().equals("Throughput")) {
            return String.format("%.1f ops/s", result.score());
        } else {
            return String.format("%.2f μs", result.score());
        }
    }
    
    private String padRight(String s, int width) {
        if (s.length() >= width) {
            return s.substring(0, width - 3) + "...";
        }
        return String.format("%-" + width + "s", s);
    }
    
    private void generateSummaryStatistics(Map<String, List<BenchmarkResult>> groupedResults) {
        System.out.println("📈 Summary Statistics");
        System.out.println("====================");
        
        int totalTests = groupedResults.size();
        long positiveImprovements = 0;
        long negativeImpacts = 0;
        long neutralImpacts = 0;
        
        double totalImprovement = 0;
        int improvementCount = 0;
        
        for (List<BenchmarkResult> testResults : groupedResults.values()) {
            BenchmarkResult fileBased = findResult(testResults, "FileBased");
            BenchmarkResult lmdb = findResult(testResults, "LMDB");
            
            if (fileBased != null && lmdb != null) {
                double improvementPercent;
                
                if (fileBased.mode().equals("Throughput")) {
                    improvementPercent = ((lmdb.score() - fileBased.score()) / fileBased.score()) * 100;
                } else {
                    improvementPercent = ((fileBased.score() - lmdb.score()) / fileBased.score()) * 100;
                }
                
                totalImprovement += improvementPercent;
                improvementCount++;
                
                if (Math.abs(improvementPercent) < 5) {
                    neutralImpacts++;
                } else if (improvementPercent > 0) {
                    positiveImprovements++;
                } else {
                    negativeImpacts++;
                }
            }
        }
        
        double averageImprovement = improvementCount > 0 ? totalImprovement / improvementCount : 0;
        
        System.out.printf("Total Tests Compared: %d%n", totalTests);
        System.out.printf("Positive Improvements: %d (%.1f%%)%n", 
            positiveImprovements, (positiveImprovements * 100.0 / totalTests));
        System.out.printf("Negative Impacts: %d (%.1f%%)%n", 
            negativeImpacts, (negativeImpacts * 100.0 / totalTests));
        System.out.printf("Neutral Changes: %d (%.1f%%)%n", 
            neutralImpacts, (neutralImpacts * 100.0 / totalTests));
        System.out.printf("Average Performance Change: %+.1f%%%n", averageImprovement);
        System.out.println();
    }
    
    private void generateRecommendations(Map<String, List<BenchmarkResult>> groupedResults) {
        System.out.println("💡 Recommendations");
        System.out.println("==================");
        
        // Analyze specific patterns
        boolean throughputImproved = false;
        boolean latencyImproved = false;
        boolean scalabilityImproved = false;
        
        for (Map.Entry<String, List<BenchmarkResult>> entry : groupedResults.entrySet()) {
            String testKey = entry.getKey();
            List<BenchmarkResult> testResults = entry.getValue();
            
            BenchmarkResult fileBased = findResult(testResults, "FileBased");
            BenchmarkResult lmdb = findResult(testResults, "LMDB");
            
            if (fileBased != null && lmdb != null) {
                double improvementPercent;
                
                if (fileBased.mode().equals("Throughput")) {
                    improvementPercent = ((lmdb.score() - fileBased.score()) / fileBased.score()) * 100;
                    if (improvementPercent > 5) throughputImproved = true;
                } else {
                    improvementPercent = ((fileBased.score() - lmdb.score()) / fileBased.score()) * 100;
                    if (improvementPercent > 5) latencyImproved = true;
                }
                
                if (testKey.contains("multiple") || testKey.contains("batch")) {
                    if (improvementPercent > 5) scalabilityImproved = true;
                }
            }
        }
        
        System.out.println("Based on the benchmark results:");
        
        if (throughputImproved) {
            System.out.println("✅ LMDB shows improved throughput - consider for high-volume workloads");
        }
        
        if (latencyImproved) {
            System.out.println("✅ LMDB shows reduced latency - consider for latency-sensitive applications");
        }
        
        if (scalabilityImproved) {
            System.out.println("✅ LMDB shows better scalability - consider for multi-actor scenarios");
        }
        
        if (!throughputImproved && !latencyImproved && !scalabilityImproved) {
            System.out.println("⚠️  LMDB performance is comparable to file-based - consider other factors:");
            System.out.println("   - Memory usage patterns");
            System.out.println("   - Disk space efficiency");
            System.out.println("   - Future scalability requirements");
        }
        
        System.out.println();
        System.out.println("Next Steps:");
        System.out.println("1. Test with your specific workload patterns");
        System.out.println("2. Monitor memory usage and disk I/O in production");
        System.out.println("3. Consider hybrid approach for different actor types");
        System.out.println("4. Evaluate Phase 2 LMDB optimizations when available");
    }
    
    private void saveToCsv(List<BenchmarkResult> results, String filename) throws IOException {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(Paths.get(filename)))) {
            writer.println("Test,Mode,Score,Unit,Type");
            
            for (BenchmarkResult result : results) {
                String type = result.testName().contains("LMDB") ? "LMDB" : "File-Based";
                writer.printf("%s,%s,%.6f,%s,%s%n",
                    result.testName(),
                    result.mode(),
                    result.score(),
                    result.unit(),
                    type
                );
            }
        }
    }
    
    public record BenchmarkResult(String testName, String mode, double score, String unit) {}
}
