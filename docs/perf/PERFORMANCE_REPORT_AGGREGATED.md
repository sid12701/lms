# LMS Performance Test Report (Generated)

Reports aggregated: 4

## baseline — 20260614-125536

- Duration: 189.0s
- RPS: 0.339
- Errors: 3 (4.69%)
- Latency p50/p95/p99: 1743 / 3870 / 15737 ms

### Workflows

| Workflow | Count | Error % | p95 ms |
|----------|------:|--------:|-------:|
| origination.approval_poll | 30 | 0.0 | 1846 |
| disbursement.poll | 20 | 0.0 | 2465 |
| origination.upload_doc | 3 | 0.0 | 3468 |
| origination.create | 1 | 0.0 | 7339 |
| origination.status_read | 1 | 0.0 | 2882 |
| disbursement.initiate | 1 | 100.0 | 3870 |
| disbursement.mock_outcome | 1 | 100.0 | 701 |
| admin.dashboard | 1 | 0.0 | 15737 |
| admin.loan_list | 1 | 0.0 | 1375 |
| admin.report_preview | 1 | 0.0 | 1119 |
| admin.alerts | 1 | 100.0 | 586 |
| admin.audit | 1 | 0.0 | 2289 |

## concurrency — 20260614-125740

- Duration: 110.61s
- RPS: 0.262
- Errors: 0 (0.0%)
- Latency p50/p95/p99: 6129 / 8531 / 10534 ms

### Workflows

| Workflow | Count | Error % | p95 ms |
|----------|------:|--------:|-------:|
| origination.upload_doc | 8 | 0.0 | 10534 |
| race.disbursement | 8 | 0.0 | 8531 |
| race.idempotency_create | 5 | 0.0 | 6779 |
| race.idempotency_replay | 5 | 0.0 | 1742 |
| origination.create | 1 | 0.0 | 6421 |
| origination.status_read | 1 | 0.0 | 3224 |
| origination.approval_poll | 1 | 0.0 | 1688 |

## reporting — 20260614-125930

- Duration: 108.9s
- RPS: 1.543
- Errors: 48 (28.57%)
- Latency p50/p95/p99: 1543 / 16114 / 16693 ms

### Workflows

| Workflow | Count | Error % | p95 ms |
|----------|------:|--------:|-------:|
| report.poll | 48 | 0.0 | 1984 |
| report.download | 48 | 75.0 | 1930 |
| admin.dashboard | 12 | 0.0 | 32291 |
| admin.loan_list | 12 | 0.0 | 2231 |
| admin.report_preview | 12 | 0.0 | 2191 |
| admin.alerts | 12 | 100.0 | 1140 |
| admin.audit | 12 | 0.0 | 2181 |
| report.async_create | 12 | 0.0 | 2562 |

## failure — 20260614-125941

- Duration: 10.71s
- RPS: 0.373
- Errors: 2 (50.0%)
- Latency p50/p95/p99: 1275 / 6742 / 6742 ms

### Workflows

| Workflow | Count | Error % | p95 ms |
|----------|------:|--------:|-------:|
| failure.no_auth | 1 | 100.0 | 30 |
| failure.bad_body | 1 | 100.0 | 24 |
| race.idempotency_create | 1 | 0.0 | 6742 |
| race.idempotency_replay | 1 | 0.0 | 1275 |
