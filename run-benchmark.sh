#!/bin/bash

# Flexible Cajun Benchmark Runner using Docker
# Can run any benchmark with custom parameters

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${BLUE}🚀 Flexible Cajun Benchmark Runner${NC}"
echo -e "${BLUE}===================================${NC}"

# Create results directory
mkdir -p benchmark-results
echo -e "${GREEN}📁 Created benchmark-results directory${NC}"

# Check if Docker is available
if ! command -v docker &> /dev/null; then
    echo -e "${RED}❌ Docker is not installed or not in PATH${NC}"
    exit 1
fi

# Check if Docker Compose is available
if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
    echo -e "${RED}❌ Docker Compose is not installed${NC}"
    exit 1
fi

# Determine Docker Compose command
DOCKER_COMPOSE="docker-compose"
if docker compose version &> /dev/null; then
    DOCKER_COMPOSE="docker compose"
fi

echo -e "${GREEN}✅ Using Docker Compose command: $DOCKER_COMPOSE${NC}"

# Function to show usage
show_usage() {
    echo -e "${YELLOW}Usage: $0 [benchmark-type] [options]${NC}"
    echo -e "${YELLOW}Benchmark types:${NC}"
    echo -e "  ${BLUE}help${NC}         - Show this help message"
    echo -e "  ${BLUE}list${NC}         - List all available benchmarks"
    echo -e "  ${BLUE}lmdb${NC}         - Run full LMDB benchmarks"
    echo -e "  ${BLUE}quick${NC}        - Run quick LMDB benchmarks"
    echo -e "  ${BLUE}all${NC}          - Run all benchmarks"
    echo -e "  ${BLUE}lightweight${NC}  - Run lightweight stateful benchmarks"
    echo -e "  ${BLUE}actor${NC}        - Run actor benchmarks"
    echo -e "  ${BLUE}mailbox${NC}      - Run mailbox benchmarks"
    echo -e "  ${BLUE}persistence${NC}  - Run filesystem vs LMDB comparison benchmarks"
    echo -e "  ${BLUE}persistence-quick${NC} - Run quick persistence comparison"
    echo -e ""
    echo -e "${YELLOW}Custom benchmark execution:${NC}"
    echo -e "  ${PURPLE}$0 custom <benchmark-name> [jmh-options]${NC}"
    echo -e "  ${CYAN}Example: $0 custom LmdbStatefulBenchmark.benchmarkMessageJournalOperations -wi 2 -i 3 -f 1${NC}"
    echo -e ""
    echo -e "${YELLOW}Interactive mode:${NC}"
    echo -e "  ${BLUE}interactive${NC}  - Run in interactive mode with prompts"
    echo -e ""
    echo -e "${YELLOW}Utility commands:${NC}"
    echo -e "  ${BLUE}clean${NC}        - Clean up Docker images and containers"
    echo -e "  ${BLUE}results${NC}      - Show latest benchmark results"
    echo -e ""
    echo -e "${YELLOW}JMH Options:${NC}"
    echo -e "  ${CYAN}-wi N${NC}         - Warmup iterations (default: 3)"
    echo -e "  ${CYAN}-i N${NC}          - Measurement iterations (default: 5)"
    echo -e "  ${CYAN}-f N${NC}          - Forks (default: 1)"
    echo -e "  ${CYAN}-t N${NC}          - Threads (default: 1)"
    echo -e "  ${CYAN}-rf json${NC}     - Output format (JSON)"
    echo -e "  ${CYAN}-rff file.json${NC} - Results file"
}

# Function to list available benchmarks
list_benchmarks() {
    echo -e "${BLUE}📋 Available Benchmarks:${NC}"
    echo -e "${CYAN}Building container to list benchmarks...${NC}"
    
    # Build and run container to list benchmarks
    $DOCKER_COMPOSE --profile runner build benchmark-runner
    $DOCKER_COMPOSE --profile runner run --rm benchmark-runner
    
    echo -e ""
    echo -e "${YELLOW}Common benchmark classes:${NC}"
    echo -e "  ${GREEN}LmdbStatefulBenchmark${NC}           - LMDB persistence benchmarks"
    echo -e "  ${GREEN}LightweightStatefulBenchmark${NC}    - Lightweight stateful actor benchmarks"
    echo -e "  ${GREEN}ActorBenchmark${NC}                  - General actor benchmarks"
    echo -e "  ${GREEN}MailboxTypeBenchmark${NC}            - Mailbox implementation benchmarks"
    echo -e "  ${GREEN}ThreadBenchmark${NC}                 - Thread performance benchmarks"
    echo -e "  ${GREEN}StructuredConcurrencyBenchmark${NC}  - Structured concurrency benchmarks"
}

# Function to run predefined benchmarks
run_benchmark() {
    local profile=$1
    local description=$2
    
    echo -e "${BLUE}🏃 Running $description...${NC}"
    echo -e "${YELLOW}⚠️  This may take several minutes...${NC}"
    
    # Build and run the benchmark
    $DOCKER_COMPOSE --profile $profile up --build
    
    echo -e "${GREEN}✅ $description completed!${NC}"
    echo -e "${GREEN}📊 Results saved to benchmark-results/${NC}"
}

# Function to run custom benchmark
run_custom_benchmark() {
    local benchmark_name=$1
    shift
    local jmh_options="$@"
    
    if [ -z "$benchmark_name" ]; then
        echo -e "${RED}❌ Benchmark name is required for custom execution${NC}"
        echo -e "${CYAN}Usage: $0 custom <benchmark-name> [jmh-options]${NC}"
        exit 1
    fi
    
    # Generate results filename
    local safe_name=$(echo "$benchmark_name" | sed 's/[^a-zA-Z0-9]/_/g')
    local results_file="/app/results/custom_${safe_name}_$(date +%Y%m%d_%H%M%S).json"
    
    echo -e "${BLUE}🎯 Running Custom Benchmark: ${PURPLE}$benchmark_name${NC}"
    if [ -n "$jmh_options" ]; then
        echo -e "${CYAN}JMH Options: $jmh_options${NC}"
    fi
    echo -e "${YELLOW}⚠️  This may take several minutes...${NC}"
    
    # Build and run custom benchmark
    $DOCKER_COMPOSE --profile runner build benchmark-runner
    $DOCKER_COMPOSE --profile runner run --rm benchmark-runner \
        java --enable-preview --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
        -jar benchmarks/benchmarks-jmh.jar \
        "$benchmark_name" \
        -rf json -rff "$results_file" \
        $jmh_options
    
    echo -e "${GREEN}✅ Custom benchmark completed!${NC}"
    echo -e "${GREEN}📊 Results saved to benchmark-results/${NC}"
}

# Function for interactive mode
interactive_mode() {
    echo -e "${PURPLE}🎮 Interactive Benchmark Mode${NC}"
    echo -e "${PURPLE}==============================${NC}"
    
    echo -e "${CYAN}Step 1: Choose benchmark type${NC}"
    echo -e "  1) LmdbStatefulBenchmark"
    echo -e "  2) LightweightStatefulBenchmark"
    echo -e "  3) ActorBenchmark"
    echo -e "  4) MailboxTypeBenchmark"
    echo -e "  5) Custom benchmark name"
    echo -e "  6) List all available benchmarks"
    
    read -p "Enter choice (1-6): " choice
    
    case $choice in
        1) benchmark_name="LmdbStatefulBenchmark" ;;
        2) benchmark_name="LightweightStatefulBenchmark" ;;
        3) benchmark_name="ActorBenchmark" ;;
        4) benchmark_name="MailboxTypeBenchmark" ;;
        5) 
            read -p "Enter benchmark name: " benchmark_name
            if [ -z "$benchmark_name" ]; then
                echo -e "${RED}❌ Benchmark name cannot be empty${NC}"
                exit 1
            fi
            ;;
        6) 
            list_benchmarks
            read -p "Enter benchmark name: " benchmark_name
            if [ -z "$benchmark_name" ]; then
                echo -e "${RED}❌ Benchmark name cannot be empty${NC}"
                exit 1
            fi
            ;;
        *) 
            echo -e "${RED}❌ Invalid choice${NC}"
            exit 1
            ;;
    esac
    
    echo -e "${CYAN}Step 2: Configure JMH options (press Enter for defaults)${NC}"
    
    read -p "Warmup iterations (default: 1): " wi
    wi=${wi:-1}
    
    read -p "Measurement iterations (default: 2): " i
    i=${i:-2}
    
    read -p "Forks (default: 1): " f
    f=${f:-1}
    
    read -p "Threads (default: 1): " t
    t=${t:-1}
    
    # Generate results filename
    local safe_name=$(echo "$benchmark_name" | sed 's/[^a-zA-Z0-9]/_/g')
    local results_file="/app/results/interactive_${safe_name}_$(date +%Y%m%d_%H%M%S).json"
    
    echo -e "${BLUE}🏃 Running interactive benchmark...${NC}"
    echo -e "${PURPLE}Benchmark: $benchmark_name${NC}"
    echo -e "${CYAN}Options: -wi $wi -i $i -f $f -t $t${NC}"
    
    # Run the benchmark
    $DOCKER_COMPOSE --profile runner build benchmark-runner
    $DOCKER_COMPOSE --profile runner run --rm benchmark-runner \
        java --enable-preview --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
        -jar benchmarks/benchmarks-jmh.jar \
        "$benchmark_name" \
        -rf json -rff "$results_file" \
        -wi $wi -i $i -f $f -t $t
    
    echo -e "${GREEN}✅ Interactive benchmark completed!${NC}"
    echo -e "${GREEN}📊 Results saved to benchmark-results/${NC}"
}

# Function to show results
show_results() {
    echo -e "${BLUE}📊 Latest Benchmark Results:${NC}"
    echo -e "${BLUE}============================${NC}"
    
    if [ -d "benchmark-results" ] && [ "$(ls -A benchmark-results)" ]; then
        echo -e "${CYAN}Available result files:${NC}"
        ls -la benchmark-results/*.json 2>/dev/null | tail -5
        
        echo -e ""
        echo -e "${CYAN}Most recent results:${NC}"
        latest_file=$(ls -t benchmark-results/*.json 2>/dev/null | head -1)
        if [ -n "$latest_file" ]; then
            echo -e "${GREEN}File: $latest_file${NC}"
            
            # Show summary using jq if available
            if command -v jq &> /dev/null; then
                echo -e "${CYAN}Benchmark Summary:${NC}"
                jq -r '.[] | "Benchmark: \(.benchmark | split(".") | .[-1])\nThroughput: \(.primaryMetric.score) \(.primaryMetric.scoreUnit)\n"' "$latest_file" 2>/dev/null | head -20
            else
                echo -e "${YELLOW}Install jq for better result formatting${NC}"
            fi
        fi
    else
        echo -e "${YELLOW}No benchmark results found. Run a benchmark first!${NC}"
    fi
}

# Function to cleanup
cleanup() {
    echo -e "${BLUE}🧹 Cleaning up Docker resources...${NC}"
    $DOCKER_COMPOSE down --rmi all --volumes --remove-orphans 2>/dev/null || true
    docker system prune -f
    echo -e "${GREEN}✅ Cleanup completed!${NC}"
}

# Parse command line arguments
case "${1:-help}" in
    "help"|"-h"|"--help")
        show_usage
        exit 0
        ;;
    "list")
        list_benchmarks
        ;;
    "lmdb")
        run_benchmark "lmdb" "Full LMDB Benchmarks"
        ;;
    "quick")
        run_benchmark "quick" "Quick LMDB Benchmarks"
        ;;
    "all")
        run_benchmark "all" "All Benchmarks"
        ;;
    "lightweight")
        run_benchmark "lightweight" "Lightweight Stateful Benchmarks"
        ;;
    "actor")
        run_benchmark "actor" "Actor Benchmarks"
        ;;
    "mailbox")
        run_benchmark "mailbox" "Mailbox Benchmarks"
        ;;
    "persistence")
        run_benchmark "persistence" "Filesystem vs LMDB Comparison Benchmarks"
        ;;
    "persistence-quick")
        run_benchmark "persistence-quick" "Quick Filesystem vs LMDB Comparison"
        ;;
    "custom")
        shift
        run_custom_benchmark "$@"
        ;;
    "interactive")
        interactive_mode
        ;;
    "results")
        show_results
        ;;
    "clean")
        cleanup
        ;;
    *)
        echo -e "${RED}❌ Unknown command: $1${NC}"
        show_usage
        exit 1
        ;;
esac

echo -e ""
echo -e "${BLUE}📈 Benchmark Results:${NC}"
echo -e "${GREEN}📂 Check the benchmark-results/ directory for JSON output files${NC}"
echo -e "${GREEN}🔍 Use '$0 results' to see latest results summary${NC}"
echo -e "${GREEN}📊 Analyze results with JMH tools or import into spreadsheets${NC}"

echo -e ""
echo -e "${BLUE}🎉 Flexible Cajun Benchmark Runner completed successfully!${NC}"
