#!/bin/bash

# ============================================================
# Load Test Script for 250 TPS - 1 Million Transactions
# ============================================================
# Usage: ./load-test.sh [options]
# Options:
#   -t, --tps          Target TPS (default: 250)
#   -n, --total        Total transactions (default: 1000000)
#   -r, --ramp         Ramp-up time in seconds (default: 300)
#   -u, --url          Base URL (default: http://localhost:8080)
#   -h, --help         Show help
#
# Examples:
#   ./load-test.sh                           # Full test: 250 TPS, 1M transactions
#   ./load-test.sh -t 100 -n 100000          # Quick test: 100 TPS, 100K transactions
#   ./load-test.sh -u https://api.example.com # Custom URL
# ============================================================

set -e

# Default configuration
TPS=250
TOTAL_TRANSACTIONS=1000000
RAMP_UP_SECONDS=300
BASE_URL="http://localhost:8080"
WARMUP_REQUESTS=1000

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -t|--tps)
            TPS="$2"
            shift 2
            ;;
        -n|--total)
            TOTAL_TRANSACTIONS="$2"
            shift 2
            ;;
        -r|--ramp)
            RAMP_UP_SECONDS="$2"
            shift 2
            ;;
        -u|--url)
            BASE_URL="$2"
            shift 2
            ;;
        -h|--help)
            echo "Usage: $0 [options]"
            echo "Options:"
            echo "  -t, --tps          Target TPS (default: 250)"
            echo "  -n, --total        Total transactions (default: 1000000)"
            echo "  -r, --ramp         Ramp-up time in seconds (default: 300)"
            echo "  -u, --url          Base URL (default: http://localhost:8080)"
            echo "  -h, --help         Show this help"
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            exit 1
            ;;
    esac
done

echo -e "${BLUE}=========================================="
echo "  P2P Payment System Load Test"
echo "==========================================${NC}"
echo ""
echo -e "${GREEN}Configuration:${NC}"
echo "  Target TPS:          ${TPS}"
echo "  Total Transactions: ${TOTAL_TRANSACTIONS}"
echo "  Ramp-up Time:       ${RAMP_UP_SECONDS}s"
echo "  Base URL:           ${BASE_URL}"
echo ""

# Calculate test duration
DURATION=$((TOTAL_TRANSACTIONS / TPS))
echo -e "${YELLOW}Estimated test duration: $((DURATION / 60)) minutes${NC}"
echo ""

# Check if hey is installed
if ! command -v hey &> /dev/null; then
    echo -e "${YELLOW}Installing hey...${NC}"
    brew install hey 2>/dev/null || (
        echo "Downloading hey..."
        curl -sL https://github.com/rakyll/hey/releases/download/v0.1.2/hey_darwin_amd64 -o hey
        chmod +x hey
    )
fi

# Generate test data
echo -e "${GREEN}Generating test data...${NC}"

# Create test wallets (simulated)
SENDER_WALLET_ID=$(uuidgen 2>/dev/null || echo "sender-$(date +%s)")
RECEIVER_WALLET_ID=$(uuidgen 2>/dev/null || echo "receiver-$(date +%s)")

# Create JSON payload
PAYLOAD=$(cat <<EOF
{
    "senderWalletId": "${SENDER_WALLET_ID}",
    "receiverWalletId": "${RECEIVER_WALLET_ID}",
    "amount": 100.00
}
EOF
)

# Write payload to file
echo "$PAYLOAD" > /tmp/transfer_payload.json

echo -e "${GREEN}✓ Test data generated${NC}"
echo ""

# Pre-warm the system
echo -e "${GREEN}Warming up system with ${WARMUP_REQUESTS} requests...${NC}"
hey -n $WARMUP_REQUESTS -c 10 -q \
    -H "Content-Type: application/json" \
    -D /tmp/transfer_payload.json \
    "${BASE_URL}/api/v1/transfers" \
    > /dev/null 2>&1

echo -e "${GREEN}✓ Warm-up complete${NC}"
echo ""

# Run the load test
echo -e "${BLUE}Starting load test...${NC}"
echo -e "${YELLOW}Target: ${TPS} TPS for ${TOTAL_TRANSACTIONS} transactions${NC}"
echo ""

# Calculate concurrency based on TPS
# For 250 TPS, we need ~50-100 concurrent connections
CONCURRENCY=$((TPS * 2))

# Run hey with specified parameters
hey \
    -n $TOTAL_TRANSACTIONS \
    -c $CONCURRENCY \
    -q \
    -H "Content-Type: application/json" \
    -D /tmp/transfer_payload.json \
    -timeout 30s \
    "${BASE_URL}/api/v1/transfers" \
    2>&1 | tee /tmp/load_test_results.txt

echo ""
echo -e "${BLUE}=========================================="
echo "  Test Complete"
echo "==========================================${NC}"

# Extract and display key metrics
echo ""
echo -e "${GREEN}Key Metrics:${NC}"

# Total requests
TOTAL=$(grep "requests in" /tmp/load_test_results.txt | awk '{print $1}')
echo "  Total Requests:    $TOTAL"

# Requests per second
RPS=$(grep -E "Requests\/sec:" /tmp/load_test_results.txt | awk '{print $NF}')
echo "  Avg RPS:          $RPS"

# Latency p50
P50=$(grep -E "50%" /tmp/load_test_results.txt | awk '{print $2}')
echo "  Latency p50:      ${P50}ms"

# Latency p99
P99=$(grep -E "99%" /tmp/load_test_results.txt | awk '{print $2}')
echo "  Latency p99:      ${P99}ms"

# Error rate
ERRORS=$(grep "Failed requests:" /tmp/load_test_results.txt | awk '{print $3}')
echo "  Failed Requests:  $ERRORS"

# Success rate
if [ -n "$TOTAL" ] && [ -n "$ERRORS" ]; then
    SUCCESS=$((TOTAL - ERRORS))
    SUCCESS_RATE=$(echo "scale=2; $SUCCESS * 100 / $TOTAL" | bc 2>/dev/null || echo "N/A")
    echo "  Success Rate:     ${SUCCESS_RATE}%"
fi

echo ""
echo -e "${GREEN}Results saved to: /tmp/load_test_results.txt${NC}"

# Validation
echo ""
echo -e "${BLUE}Validation:${NC}"

# Check if TPS target was met
ACTUAL_RPS=$(echo "$RPS" | bc -l 2>/dev/null || echo "0")
TARGET_TPS=$TPS

if (( $(echo "$ACTUAL_RPS >= $TARGET_TPS * 0.9" | bc -l 2>/dev/null || echo 0) )); then
    echo -e "  ✅ Throughput: ${ACTUAL_RPS} TPS (target: ${TARGET_TPS} TPS) - ${GREEN}PASSED${NC}"
else
    echo -e "  ❌ Throughput: ${ACTUAL_RPS} TPS (target: ${TARGET_TPS} TPS) - ${RED}FAILED${NC}"
fi

# Check latency
if [ -n "$P99" ]; then
    P99_NUM=$(echo "$P99" | tr -d 'ms')
    if [ "$P99_NUM" -lt 50 ]; then
        echo -e "  ✅ Latency p99: ${P99} (target: <50ms) - ${GREEN}PASSED${NC}"
    else
        echo -e "  ❌ Latency p99: ${P99} (target: <50ms) - ${RED}FAILED${NC}"
    fi
fi

# Check error rate
if [ -n "$ERRORS" ] && [ "$ERRORS" -gt 0 ] && [ -n "$TOTAL" ]; then
    ERROR_RATE=$(echo "scale=4; $ERRORS * 100 / $TOTAL" | bc -l 2>/dev/null || echo "0")
    if (( $(echo "$ERROR_RATE < 0.1" | bc -l 2>/dev/null || echo 0) )); then
        echo -e "  ✅ Error Rate: ${ERROR_RATE}% (target: <0.1%) - ${GREEN}PASSED${NC}"
    else
        echo -e "  ❌ Error Rate: ${ERROR_RATE}% (target: <0.1%) - ${RED}FAILED${NC}"
    fi
else
    echo -e "  ✅ Error Rate: 0% - ${GREEN}PASSED${NC}"
fi

echo ""
echo -e "${GREEN}Load test complete!${NC}"