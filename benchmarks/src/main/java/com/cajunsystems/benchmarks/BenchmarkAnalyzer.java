package com.cajunsystems.benchmarks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes JMH benchmark results and generates comparison reports.
 * Parses the JSON output from JMH and creates human-readable comparisons.
 */
public class BenchmarkAnalyzer {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java BenchmarkAnalyzer <path-to-jmh-results.json>");
            System.out.println("\nExample:");
            System.out.println("  java BenchmarkAnalyzer build/reports/jmh/results.json");
            System.exit(1);
        }

        Path resultsPath = Paths.get(args[0]);
        if (!Files.exists(resultsPath)) {
            System.err.println("Error: File not found: " + resultsPath);
            System.exit(1);
        }

        try {
            analyzeResults(resultsPath);
        } catch (IOException e) {
            System.err.println("Error reading results file: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void analyzeResults(Path resultsPath) throws IOException {
        System.out.println("=".repeat(80));
        System.out.println("CAJUN ACTOR SYSTEM BENCHMARK ANALYSIS");
        System.out.println("=".repeat(80));
        System.out.println();

        String content = Files.readString(resultsPath);

        // Note: This is a simplified analyzer. For production use, consider using
        // a JSON library like Jackson or Gson to parse the JMH JSON output properly.

        System.out.println("Results file: " + resultsPath.toAbsolutePath());
        System.out.println("File size: " + Files.size(resultsPath) + " bytes");
        System.out.println();

        printComparisonSummary(content);
        printRecommendations();
    }

    private static void printComparisonSummary(String content) {
        System.out.println("COMPARISON SUMMARY");
        System.out.println("-".repeat(80));
        System.out.println();

        // Extract benchmark names (simplified - in production, parse JSON properly)
        if (content.contains("ActorBenchmark")) {
            System.out.println("✓ Actor benchmarks completed");
        }
        if (content.contains("ThreadBenchmark")) {
            System.out.println("✓ Thread benchmarks completed");
        }
        if (content.contains("StructuredConcurrencyBenchmark")) {
            System.out.println("✓ Structured Concurrency benchmarks completed");
        }
        if (content.contains("ComparisonBenchmark")) {
            System.out.println("✓ Direct comparison benchmarks completed");
        }

        System.out.println();
        System.out.println("For detailed results, see the human-readable report:");
        System.out.println("  build/reports/jmh/human.txt");
        System.out.println();
    }

    private static void printRecommendations() {
        System.out.println("INTERPRETATION GUIDE");
        System.out.println("-".repeat(80));
        System.out.println();

        System.out.println("When comparing results, consider:");
        System.out.println();

        System.out.println("1. THROUGHPUT (ops/ms) - Higher is better");
        System.out.println("   - Measures how many operations complete per millisecond");
        System.out.println("   - Best for comparing message passing and task execution rates");
        System.out.println();

        System.out.println("2. AVERAGE TIME (ms/op) - Lower is better");
        System.out.println("   - Measures time per operation in milliseconds");
        System.out.println("   - Best for comparing latency-sensitive operations");
        System.out.println();

        System.out.println("3. ERROR MARGINS");
        System.out.println("   - ± values show 99.9% confidence intervals");
        System.out.println("   - Overlapping error margins suggest similar performance");
        System.out.println("   - Large error margins indicate high variance");
        System.out.println();

        System.out.println("PATTERN RECOMMENDATIONS:");
        System.out.println();

        System.out.println("Use ACTORS when:");
        System.out.println("  • You need fault isolation and supervision");
        System.out.println("  • State management is complex");
        System.out.println("  • You want location transparency (clustering)");
        System.out.println("  • Message-based thinking fits your domain");
        System.out.println();

        System.out.println("Use THREADS when:");
        System.out.println("  • You need maximum raw throughput");
        System.out.println("  • Shared state with explicit locking is acceptable");
        System.out.println("  • You're integrating with thread-based libraries");
        System.out.println("  • Simplicity is more important than isolation");
        System.out.println();

        System.out.println("Use STRUCTURED CONCURRENCY when:");
        System.out.println("  • Task relationships are hierarchical");
        System.out.println("  • You need guaranteed cleanup on scope exit");
        System.out.println("  • Error propagation scope is important");
        System.out.println("  • Task cancellation is critical");
        System.out.println();

        System.out.println("=".repeat(80));
    }

    /**
     * Simple benchmark result holder for basic analysis.
     */
    static class BenchmarkResult {
        String benchmark;
        String mode;
        double score;
        double error;
        String unit;

        @Override
        public String toString() {
            return String.format("%-50s %s %10.3f ± %.3f %s",
                benchmark, mode, score, error, unit);
        }
    }
}
