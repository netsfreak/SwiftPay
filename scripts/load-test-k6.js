import http from 'k6/http';
import { check, sleep } from 'k6';
import { RateCounter, Trend } from 'k6/metrics';

// Custom metrics
const tpsMetric = new Trend('transactions_per_second');
const latencyMetric = new Trend('transaction_latency_ms');
const errorRate = new RateCounter('error_rate');

// Configuration
const CONFIG = {
  baseUrl: __ENV.BASE_URL || 'http://localhost:8080',
  targetTps: parseInt(__ENV.TPS || '250'),
  totalTransactions: parseInt(__ENV.TOTAL || '1000000'),
  rampUpSeconds: parseInt(__ENV.RAMP_UP || '300'),
  durationSeconds: parseInt(__ENV.DURATION || '4000'),
};

// Test configuration
export const options = {
  scenarios: {
    constant_load: {
      executor: 'constant-vus',
      vus: CONFIG.targetTps * 2, // 2x for concurrency
      duration: `${CONFIG.durationSeconds}s`,
    },
    ramp_up: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: `${CONFIG.rampUpSeconds}s`, target: CONFIG.targetTps * 2 },
        { duration: `${CONFIG.durationSeconds}s`, target: CONFIG.targetTps * 2 },
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    http_req_duration: ['p(99)<50'], // p99 < 50ms
    http_req_failed: ['rate<0.001'], // Error rate < 0.1%
    transactions_per_second: ['avg>=' + CONFIG.targetTps * 0.9], // 90% of target
  },
};

// Test data
const senderWalletId = __ENV.SENDER_WALLET || 'test-sender-' + Date.now();
const receiverWalletId = __ENV.RECEIVER_WALLET || 'test-receiver-' + Date.now();

const transferPayload = JSON.stringify({
  senderWalletId: senderWalletId,
  receiverWalletId: receiverWalletId,
  amount: 100.00,
});

const headers = {
  'Content-Type': 'application/json',
  'X-User-ID': 'load-test-' + __VU,
};

// Setup
export function setup() {
  console.log(`Starting load test:`);
  console.log(`  Target TPS: ${CONFIG.targetTps}`);
  console.log(`  Total Transactions: ${CONFIG.totalTransactions}`);
  console.log(`  Ramp-up: ${CONFIG.rampUpSeconds}s`);
  console.log(`  Base URL: ${CONFIG.baseUrl}`);

  // Warmup request
  const warmupRes = http.post(
    `${CONFIG.baseUrl}/api/v1/transfers`,
    transferPayload,
    { headers }
  );

  return {
    warmupStatus: warmupRes.status,
    startTime: Date.now(),
  };
}

// Main test function
export default function () {
  const startTime = Date.now();

  // Make transfer request
  const res = http.post(
    `${CONFIG.baseUrl}/api/v1/transfers`,
    transferPayload,
    { headers }
  );

  const latency = Date.now() - startTime;

  // Record metrics
  latencyMetric.add(latency);
  tpsMetric.add(1);

  // Check response
  const success = check(res, {
    'status is 200 or 201': (r) => [200, 201].includes(r.status),
    'response time < 500ms': (r) => r.timings.duration < 500,
    'no error response': (r) => !r.body.includes('error'),
  });

  if (!success) {
    errorRate.add(1);
    console.error(`Error: ${res.status} - ${res.body}`);
  } else {
    errorRate.add(0);
  }

  // Small delay to control TPS
  sleep(1 / CONFIG.targetTps);
}

// Summary
export function handleSummary(data) {
  const summary = {
    'Performance Test Results': {
      'Target TPS': CONFIG.targetTps,
      'Total Duration': `${CONFIG.durationSeconds}s`,
      'Test Time': new Date().toISOString(),
    },
    'Throughput': {
      'Average TPS': data.metrics.http_reqs.values?.rate?.toFixed(2) || 'N/A',
      'Total Requests': data.metrics.http_reqs.values?.count || 0,
    },
    'Latency': {
      'p50': `${data.metrics.http_req_duration.values?.p50?.toFixed(2) || 'N/A'}ms`,
      'p95': `${data.metrics.http_req_duration.values?.p95?.toFixed(2) || 'N/A'}ms`,
      'p99': `${data.metrics.http_req_duration.values?.p99?.toFixed(2) || 'N/A'}ms`,
      'max': `${data.metrics.http_req_duration.values?.max?.toFixed(2) || 'N/A'}ms`,
    },
    'Errors': {
      'Failed Requests': data.metrics.http_req_failed.values?.count || 0,
      'Error Rate': `${(data.metrics.http_req_failed.values?.rate * 100 || 0).toFixed(2)}%`,
    },
  };

  // Write JSON report
  return {
    'stdout': 'summary',
    'summary.json': JSON.stringify(summary, null, 2),
  };
}