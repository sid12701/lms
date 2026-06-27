/**
 * k6 load test — LMS loan origination + admin reads
 *
 * Install: https://k6.io/docs/get-started/installation/
 * Run:
 *   k6 run scripts/perf/k6/loan_lifecycle.js \
 *     -e BASE_URL=http://localhost:8080 \
 *     -e CLIENT_ID=... -e CLIENT_SECRET=... \
 *     -e LSP_ID=... -e PRODUCT_ID=...
 *
 * Scenarios via K6_SCENARIO: baseline | load | spike | stress
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const errorRate = new Rate('errors');
const createLatency = new Trend('loan_create_latency', true);

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const CLIENT_ID = __ENV.CLIENT_ID;
const CLIENT_SECRET = __ENV.CLIENT_SECRET;
const LSP_ID = __ENV.LSP_ID;
const PRODUCT_ID = __ENV.PRODUCT_ID;
const SCENARIO = __ENV.K6_SCENARIO || 'load';

const scenarios = {
  baseline: {
    vus: 1,
    duration: '2m',
  },
  load: {
    stages: [
      { duration: '2m', target: 10 },
      { duration: '5m', target: 25 },
      { duration: '2m', target: 0 },
    ],
  },
  spike: {
    stages: [
      { duration: '30s', target: 5 },
      { duration: '10s', target: 80 },
      { duration: '1m', target: 80 },
      { duration: '30s', target: 5 },
      { duration: '2m', target: 0 },
    ],
  },
  stress: {
    stages: [
      { duration: '2m', target: 20 },
      { duration: '3m', target: 50 },
      { duration: '3m', target: 100 },
      { duration: '2m', target: 0 },
    ],
  },
  soak: {
    stages: [
      { duration: '5m', target: 15 },
      { duration: '4h', target: 15 },
      { duration: '5m', target: 0 },
    ],
  },
};

export const options = {
  scenarios: {
    main: scenarios[SCENARIO] || scenarios.load,
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<3000'],
    errors: ['rate<0.05'],
  },
};

function lspToken() {
  const res = http.post(
    `${BASE}/api/v1/auth/token`,
    JSON.stringify({ clientId: CLIENT_ID, clientSecret: CLIENT_SECRET }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'auth_token' } }
  );
  errorRate.add(res.status !== 200);
  return res.json('accessToken');
}

function loanBody(sfx) {
  return {
    lspId: LSP_ID,
    productId: PRODUCT_ID,
    lspLoanId: `K6-${sfx}`,
    fullName: `K6 Borrower ${sfx}`,
    emailAddress: `k6${sfx}@example.com`,
    mobileNumber: `9${String(Math.floor(Math.random() * 1e9)).padStart(9, '0')}`,
    dob: '1990-05-15',
    gender: 'MALE',
    maritalStatus: 'SINGLE',
    fatherName: 'Parent',
    aadharNumber: String(Math.floor(Math.random() * 1e12)).padStart(12, '0'),
    panNumber: 'ABCDE1234F',
    loanAmount: 150000,
    interestRate: 14.5,
    loanTenure: 12,
    addressLine1: '1 K6 Street',
    addressCity: 'Mumbai',
    addressState: 'MH',
    addressZipcode: '400001',
    employmentStatus: 'SALARIED',
    organizationName: 'K6 Corp',
    monthlyIncome: 60000,
    annualIncome: 720000,
    bankAccountNumber: '1234567890',
    bankName: 'HDFC Bank',
    ifscCode: 'HDFC0001234',
    accountHolderName: `K6 Borrower ${sfx}`,
    referencePersonName: 'Ref',
    referencePersonNumber: '9123456780',
  };
}

export default function () {
  if (!CLIENT_ID || !PRODUCT_ID || !LSP_ID) {
    console.warn('Set CLIENT_ID, CLIENT_SECRET, LSP_ID, PRODUCT_ID env vars');
    sleep(1);
    return;
  }

  const token = lspToken();
  const sfx = uuidv4().slice(0, 8);
  const headers = {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
    'Idempotency-Key': uuidv4(),
  };

  const createRes = http.post(
    `${BASE}/api/v1/lsp/loan-applications`,
    JSON.stringify(loanBody(sfx)),
    { headers, tags: { name: 'loan_create' } }
  );
  createLatency.add(createRes.timings.duration);
  const ok = check(createRes, { 'create 2xx': (r) => r.status === 200 || r.status === 201 });
  errorRate.add(!ok);

  if (ok && createRes.json('id')) {
    const appId = createRes.json('id');
    http.get(`${BASE}/api/v1/lsp/loan-applications/${appId}`, {
      headers: { Authorization: `Bearer ${token}` },
      tags: { name: 'loan_status' },
    });
  }

  sleep(Math.random() * 2 + 0.5);
}
