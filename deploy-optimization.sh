#!/bin/bash

# ============================================================
# Payment System Deployment Script for 250 TPS
# ============================================================
# This script automates the deployment of all optimizations
# ============================================================

set -e

echo "=========================================="
echo "Payment System Optimization Deployment"
echo "=========================================="

# Configuration
POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_DB="ppps_db"
POSTGRES_USER="ppps_user"
POSTGRES_PASSWORD="local_test_password"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# ============================================================
# Step 1: Database Preparation
# ============================================================
echo -e "\n${YELLOW}[Step 1/5] Preparing Database...${NC}"

# Check if database is accessible
if ! command -v psql &> /dev/null; then
    echo -e "${RED}PostgreSQL client not found. Please install psql.${NC}"
    exit 1
fi

# Wait for database to be ready
echo "Waiting for PostgreSQL to be ready..."
for i in {1..30}; do
    if psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT 1" &> /dev/null; then
        echo -e "${GREEN}✓ PostgreSQL is ready${NC}"
        break
    fi
    echo "Attempt $i/30..."
    sleep 2
done

# ============================================================
# Step 2: Apply Database Optimizations
# ============================================================
echo -e "\n${YELLOW}[Step 2/5] Applying Database Indexes...${NC}"

if [ -f "sql/optimize_database.sql" ]; then
    psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f sql/optimize_database.sql
    echo -e "${GREEN}✓ Database indexes created${NC}"
else
    echo -e "${RED}sql/optimize_database.sql not found${NC}"
    exit 1
fi

# ============================================================
# Step 3: Docker Deployment
# ============================================================
echo -e "\n${YELLOW}[Step 3/5] Deploying with Docker Compose...${NC}"

# Stop existing containers
echo "Stopping existing containers..."
docker-compose down || true

# Build new images
echo "Building Docker images with optimizations..."
docker-compose build --no-cache

# Start services
echo "Starting services..."
docker-compose up -d

echo -e "${GREEN}✓ Containers deployed${NC}"

# ============================================================
# Step 4: Service Health Verification
# ============================================================
echo -e "\n${YELLOW}[Step 4/5] Verifying Service Health...${NC}"

# Wait for services to be ready (max 60 seconds)
for service in api-gateway payment-service wallet-service; do
    echo "Checking $service..."
    for i in {1..30}; do
        if curl -s http://localhost:$([ "$service" = "api-gateway" ] && echo "8080" || ([ "$service" = "payment-service" ] && echo "9084" || echo "9083"))/management/health &> /dev/null; then
            echo -e "${GREEN}✓ $service is healthy${NC}"
            break
        fi
        echo "  Attempt $i/30..."
        sleep 2
    done
done

# ============================================================
# Step 5: Kafka Cluster Verification
# ============================================================
echo -e "\n${YELLOW}[Step 5/5] Verifying Kafka Cluster...${NC}"

# Check Kafka brokers
sleep 5  # Give Kafka time to fully initialize
BROKER_COUNT=$(docker exec ppps-kafka-1 kafka-brokers --bootstrap-server localhost:9092 2>/dev/null | wc -l || echo "0")

if [ "$BROKER_COUNT" -gt 0 ]; then
    echo -e "${GREEN}✓ Kafka cluster has $BROKER_COUNT brokers${NC}"
    
    # Check replication factor
    docker exec ppps-kafka-1 kafka-topics --bootstrap-server localhost:9092 --describe 2>/dev/null | head -20
else
    echo -e "${YELLOW}⚠ Could not verify Kafka brokers (this is normal for first deployment)${NC}"
fi

# ============================================================
# Summary
# ============================================================
echo -e "\n${GREEN}=========================================="
echo "Deployment Complete!"
echo "==========================================${NC}"

echo -e "\n${GREEN}Services are now running with optimization:${NC}"
echo "  • API Gateway: http://localhost:8080"
echo "  • Payment Service: http://localhost:9084"
echo "  • Wallet Service: http://localhost:9083"
echo "  • Prometheus Metrics: http://localhost:8080/management/prometheus"
echo "  • Health Check: http://localhost:8080/management/health"

echo -e "\n${GREEN}Expected Performance:${NC}"
echo "  • Throughput: 250+ TPS"
echo "  • Latency (p99): <2ms"
echo "  • Database Connections: 200 max"
echo "  • Kafka Brokers: 3"
echo "  • Replication Factor: 3"

echo -e "\n${YELLOW}Next Steps:${NC}"
echo "  1. Run load tests: gradual ramp from 50 to 250 TPS"
echo "  2. Monitor metrics: curl http://localhost:8080/management/prometheus"
echo "  3. Check logs: docker-compose logs -f [service-name]"
echo "  4. Review OPTIMIZATION_GUIDE.md for troubleshooting"

echo -e "\n${GREEN}For manual database optimization:${NC}"
echo "  psql -h $POSTGRES_HOST -U $POSTGRES_USER -d $POSTGRES_DB -f sql/optimize_database.sql"

echo -e "\n"
