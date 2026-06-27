# Bhawana LMS — Cloud-Agnostic Deployment Strategy

**Date:** 2026-06-14
**Status:** Proposed (supports the v2 capacity plan).
**Supports:** `scalability-audit-360-2026-06-14.md` (the 100K loans/day re-scope) and the Phase 1A capacity gate (GitHub #247).
**Supersedes for sizing:** Decision D1 in `scalability-assessment-2026-06-10.md` ("2 API + 1 worker pod"), which was sized for ~8K loans/day. The topology shape is unchanged; the counts and autoscaling below replace the static D1 numbers at the higher volume. Feeds the topology ADR tracked in #226.

---

## 1. Goal and the portability principle

Run the platform at the **decided volume — ~100K loans/day, 1M+/month, 10+ concurrent LSP tenants, spike-tolerant** — on **any** cloud (AWS, GCP, Azure) or on-prem, with **no rewrite to move between them**.

We achieve provider-agnosticism by depending only on **portable interfaces**, never on a provider SDK in the application:

| Concern | Portable interface the app depends on |
|---|---|
| Compute / orchestration | **OCI containers on Kubernetes** (the app is a plain container; no provider runtime APIs) |
| Database | **PostgreSQL wire protocol** (JDBC) — no provider-proprietary DB features |
| Cache / rate limit | **Redis protocol** (Lettuce) |
| Object storage | **S3-compatible API** (already abstracted behind the storage service interface) |
| Messaging | **None** — DB-table queues + `SKIP LOCKED` (RabbitMQ retired per D10); no broker to port |
| Email | **SMTP** |
| Metrics | **Prometheus exposition** (Micrometer) |
| Logs | **JSON to stdout** → any collector |
| Config / secrets | **12-factor env vars** (provider secret store injects them) |
| Provisioning | **Terraform + Helm** (provider differences isolated in modules) |

**The entire provider-specific surface is confined to two places:** (1) Terraform modules that provision the managed services, and (2) at most **two thin code adapters** — the object-storage adapter (exists) and a secrets/env loader. Everything else is identical config across clouds. If a provider ever needs a native client (e.g. an Azure Blob adapter instead of the S3 interop path), it is one class behind the existing storage interface — the pattern already used for `R2`/`MinIO`.

---

## 2. Logical topology (identical on every provider)

```
                         ┌────────────────────────────────────────────┐
   LSP partners ───►      │  L7 Ingress / Load Balancer (TLS terminate) │
   Ops frontend ───►      │  WAF + rate-limit-at-edge (coarse)          │
                          └───────────────┬────────────────────────────┘
                                          │
                 ┌────────────────────────┴───────────────────────────┐
                 │  API tier — stateless, HPA-autoscaled (2 → 12)      │
                 │  - LSP API + internal/ops API + auth                │
                 │  - @Scheduled workers DISABLED (flag)               │
                 │  - per-tenant connection bulkhead (#244)            │
                 └───────┬─────────────────────────────┬──────────────┘
                         │                              │
                         │                              │  (DB-table queues; no broker)
                 ┌───────┴──────────┐         ┌─────────┴───────────────────────┐
                 │  Redis (rate     │         │  Worker tier — 2 → N pods        │
                 │  limit only;     │         │  - disbursement (claim-safe #203) │
                 │  not money path) │         │  - webhook dispatch (#245)        │
                 └──────────────────┘         │  - reports / KPI snapshot         │
                                              │  - alert eval + fan-out           │
                                              │  - retention / partition sweeps   │
                                              │  - multi-thread scheduler (#243)  │
                                              └─────────┬─────────────────────────┘
                                                        │
              ┌─────────────────────────────────────────┴──────────────────────┐
              │  Connection pooler (transaction mode) — PgBouncer / equivalent   │
              └─────────────────────────────────────────┬──────────────────────┘
                          ┌───────────────────────────────┴──────────────┐
                          │  PostgreSQL primary (Multi-AZ)                 │
                          │  - RLS + tenant app role; partitioned big-six  │
                          │  + read replica(s) for ops/reporting (trigger) │
                          └────────────────────────────────────────────────┘

   Object storage (S3 API): documents + reports, served via presigned URLs (#228)
   Observability: Prometheus + Grafana + Alertmanager; JSON logs → collector
```

**Two deployment roles from one image.** API and worker pods run the **same container image**; they differ only by environment flags (`app.disbursement.worker.enabled`, `app.webhooks.delivery.enabled`, `app.reports.processing.enabled`, `app.alert-rules.scheduler-enabled`) — workers ON in the worker Deployment, OFF in the API Deployment. This is why the scheduler/worker fixes (#243, #203) are prerequisites: the worker tier cannot scale past one pod until the scheduled jobs are claim-safe and not serialized on one thread.

---

## 3. Provider mapping (pick a column; the app does not change)

| Capability | Portable interface | AWS | GCP | Azure | On-prem / other |
|---|---|---|---|---|---|
| Orchestration | Kubernetes | EKS | GKE | AKS | k3s / kubeadm / Nomad |
| Image registry | OCI registry | ECR | Artifact Registry | ACR | Harbor |
| Managed Postgres | PG 15+ wire | RDS / Aurora PG | Cloud SQL PG | Azure DB for PostgreSQL (Flexible) | Patroni / CloudNativePG |
| Connection pooler | PgBouncer (txn mode) | RDS Proxy or PgBouncer sidecar | PgBouncer (sidecar) | PgBouncer (sidecar) | PgBouncer / Supavisor |
| Managed Redis | Redis protocol | ElastiCache | Memorystore | Azure Cache for Redis | Redis / KeyDB |
| Object storage | S3 API | S3 | GCS (S3 interop) | Blob via S3 adapter | MinIO / Cloudflare R2 |
| Ingress / LB | K8s Ingress (L7) | ALB + LB Controller | GCLB + GKE Ingress | App Gateway + AGIC | NGINX Ingress / Traefik |
| TLS certs | ACME / cert-manager | ACM | Google-managed certs | App Gateway certs | cert-manager + Let's Encrypt |
| DNS | standard DNS | Route 53 | Cloud DNS | Azure DNS | external-dns + any DNS |
| Secrets | env injection | Secrets Manager / SSM | Secret Manager | Key Vault | Vault / sealed-secrets |
| Email (SMTP) | SMTP | SES | SendGrid/SMTP | Azure Comm. Services | any SMTP relay |
| Metrics | Prometheus | AMP or self-host | GMP or self-host | Azure Monitor or self-host | Prometheus + Grafana |
| Logs | JSON stdout | CloudWatch / OpenSearch | Cloud Logging | Azure Monitor | Loki / ELK |
| IaC | Terraform + Helm | Terraform AWS modules | Terraform GCP modules | Terraform Azure modules | Terraform + Helm |

**Rule:** anything in columns 3–6 lives only in Terraform + Helm values. Nothing in those columns appears in application code.

---

## 4. Capacity sizing for ~100K loans/day (the plan this supports)

Demand model (business-hours concentrated, ~12h active): ~100K creates/day + ~100K disbursements/day + ~300–500K repayments/day + heavy read/poll traffic. Sustained **~50–100 rps**, spike (settlement mornings, all-tenant spike) **300–500+ rps**.

| Tier / resource | Baseline | Spike (autoscale to) | Sizing rationale |
|---|---|---|---|
| **API pods** | 3 | 12 | I/O-bound; connection-limited. ~50–100 rps/pod with sized pools. HPA on CPU + p95 latency + in-flight. |
| **Worker pods** | 2 | 4–6 | Disbursement parallelism + webhook throughput (#245). Each disbursement pod: pool of 10 × ~1.5s/provider call ≈ 6/s ≈ ~500K/day theoretical; bank rate-limits bind first. |
| **API pod DB pool** | tenant 20–25 + admin 5–10 | — | Per-role Hikari sizing (#201). |
| **Worker pod DB pool** | tenant 10 + admin 10 | — | Workers run as admin scope mostly; sized smaller. |
| **Connection pooler** | transaction mode | — | Fronts all app pools; multiplexes ~300 client conns onto ~100 backend. |
| **Postgres backend conns** | `max_connections` ≈ 200 | — | App pools × pods must fit *behind the pooler*, not 1:1 to the DB. |
| **Postgres primary** | 8–16 vCPU, NVMe, Multi-AZ | vertical first | OLTP; scale reads to replica before scaling the primary. |
| **Read replica** | 0 (trigger-based) | 1–2 | Add when ops/reporting read load or sustained primary CPU >60% appears. Dashboard snapshot (#211) + presigned reports (#228) remove most read pressure first. |
| **Redis** | 1 small node | + replica (HA) | Rate-limit keys only (10-min TTL). Not on the money path; sized for ops, not data. |
| **Object storage** | managed bucket | n/a | Elastic; presigned URLs keep bytes out of pods. |

**Connection arithmetic (the constraint that bites first).** With the pooler in **transaction mode**, app-side pools do **not** map 1:1 to Postgres backends. 12 API pods × ~30 + 6 worker pods × ~20 ≈ **480 client connections → pooler → ~100–150 backend connections**. Without a pooler this configuration exceeds any single-instance `max_connections` and is also why the prior Supabase pooler 15-session cap broke (see #201/N4). **A transaction-mode pooler is mandatory at this scale on every provider.**

**Pooler compatibility note (cloud-agnostic, important).** The generic tenant path applies only `select set_config('app.current_lsp_id', …, true)` — **transaction-scoped (`SET LOCAL`)**, which is safe under transaction pooling. The Supabase-only path additionally issues a session-level `SET ROLE`, which is **not** transaction-scoped and is incompatible with plain PgBouncer transaction mode (the role would leak to the next borrower of that backend). On a non-Supabase provider, keep the single tenant app role + RLS-on-GUC model (no per-request `SET ROLE`); if a per-request role is ever required, use `SET LOCAL ROLE` inside the transaction so it stays pooler-safe.

---

## 5. Multi-tenancy at the infrastructure layer

The model is **shared everything, isolation in software** (shared schema + RLS), which is correct and cost-efficient for 10+ tenants — provided the compute-isolation gaps are closed:

- **Row isolation:** RLS + dedicated tenant DB role (exists, strong).
- **Connection/compute isolation:** per-tenant bulkhead (#244) — the missing half; without it one tenant's spike drains the shared pool for all.
- **Request isolation:** per-LSP DB-backed rate limits + read lane (#229).
- **Webhook isolation:** per-LSP delivery cap (#230).
- **Whale-tenant escape hatch (optional, deferred):** because API and worker tiers are stateless and selected by config, a single very-high-volume partner can later be pinned to a **dedicated node pool / Deployment** (same image, separate HPA) without any code change — a pure infra move if commercial terms ever require hard isolation. Document the trigger; do not build pre-emptively.

---

## 6. Scaling & autoscaling policy

- **API tier:** Horizontal Pod Autoscaler on CPU **and** a custom signal (p95 latency or in-flight requests from #217 metrics). Scale-out fast, scale-in slow (stabilization window) to absorb spikes. Min 3 for AZ spread, max set by the pooler/backend ceiling.
- **Worker tier:** scale on **queue depth / oldest-pending-age** (#234 metrics) — the right signal for DB-table queues. Disbursement and webhook backlog age are the primary triggers. Requires claim-safe workers (#203) first.
- **Database:** vertical-first; add read replica on the documented trigger (§4). Never autoscale the primary on connections — that is what the pooler is for.
- **Edge:** keep a coarse rate limit at the ingress/WAF as a blunt DDoS backstop *in addition to* the per-tenant application limits (defense in depth; the app limits are the precise ones).

---

## 7. High availability & failure isolation

- **Multi-AZ** for API, workers, Postgres (synchronous standby), and the pooler. Spread pods with topology constraints.
- **Stateless API/workers:** any pod can die; K8s reschedules. No session affinity needed (JWT auth).
- **DB failover:** managed primary→standby promotion; the pooler reconnects. App retries idempotent operations (the idempotency + outbox machinery already makes this safe).
- **Redis outage:** **must be non-fatal** — fail-open for business traffic, fail-closed for auth (#223). Redis is deliberately *not* on the money path, so a Redis outage degrades rate limiting, not lending.
- **Worker pod death mid-batch:** DB-queue rows persist; lease/claim re-issues them; the disbursement sweeper (#204) resolves ambiguous provider calls. Self-healing by design.
- **Graceful shutdown:** pods drain in-flight requests and in-flight worker tasks on `SIGTERM` (ties to #243 executor shutdown) so deploys don't truncate a batch.

---

## 8. Disaster recovery

- **Postgres:** automated backups + point-in-time recovery (WAL). Target **RPO ≤ 5 min, RTO ≤ 1 h**; both confirmed by a **restore drill**, not by config alone.
- **Object storage:** versioning + cross-region replication for documents/reports; the 8-year archive tier (D6) is lifecycle-policy, portable across providers.
- **Partition-aware retention:** 24 months hot (monthly partitions, #208), older dropped/archived as partitions (instant `DROP PARTITION`) — DR scope stays bounded as the book grows.
- **Infra rebuild:** Terraform + Helm reconstruct the whole environment in a new region/provider from version control; this is the ultimate portability test and should be drilled at least once.

---

## 9. Security at the deployment layer

- **Network:** API behind the L7 LB/WAF; **Postgres, Redis, object storage on private networking only** (no public endpoints); pods in private subnets; egress controlled.
- **TLS everywhere:** terminate at the edge; in-cluster mTLS optional (service mesh) but not required day one.
- **Secrets:** never baked into images; injected from the provider secret store as env (12-factor). The startup validator already refuses default/placeholder secrets outside local/test — keep that as a deploy gate.
- **CORS / origins:** externalized per environment (#237) — no hardcoded localhost in prod.
- **Images:** built from a pinned base, scanned in CI (and the upload AV path #221 for partner files), signed; registries private.
- **Least privilege:** the tenant DB role has only what RLS needs; worker/admin roles separated; pod service accounts scoped to the minimum cloud IAM.
- **`statement_timeout` as a security control** (#201): caps a maliciously expensive "query of death" to one failed request instead of a pool-exhaustion DoS.

---

## 10. Configuration, build & release

- **One image, env-driven config.** A `prod` Spring profile (created in #201/N4) carries sane pool sizes, timeouts, JSON logging (#218), and metrics exposure; everything environment-specific is an env var.
- **CI/CD (provider-neutral):** build → test → scan → push OCI image → `helm upgrade` (rolling, or canary/blue-green for risky releases). Pipeline runs on any CI; only the registry/deploy credentials change per provider.
- **Database migrations:** Flyway, **expand → migrate → contract**. Migrations run as a pre-deploy K8s **Job** (not on every pod boot) so a rolling deploy doesn't race migrations. Partition/DDL migrations follow the migrations runbook; index builds use `CONCURRENTLY` to avoid table locks on the live big-six.
- **Flag-gated rollout:** behavior-changing workers ship disabled-by-flag; a bad deploy is a flag flip, not a rollback (matches the assessment's rollout policy).
- **Environments:** `local` (docker-compose, today) → `staging` (the 2-instance stack, #198, mirrors prod topology at small scale) → `prod` (this document). Staging is where the load suite (#200) and the noisy-neighbor scenario matrix run before any ramp.

---

## 11. What this depends on (gate ordering)

This deployment plan is only *safe to run at volume* once the Phase 1A capacity gate (#247) lands — specifically the items that make the tiers scalable rather than just deployable:

- **#243** multi-thread scheduler — prerequisite for >1 worker pod.
- **#203 / #204** claim-safe disbursement — prerequisite for >1 worker pod and for parallel throughput.
- **#201** prod profile + pool sizing + timeouts — prerequisite for the connection arithmetic in §4.
- **#244** per-tenant bulkhead — prerequisite for the shared-everything multi-tenant model in §5.
- **#245** webhook throughput — prerequisite for draining the event volume across worker pods.
- **#217 / #234** metrics — prerequisite for the autoscaling signals in §6.

Deploying the topology without these gives you a *bigger* version of today's failure modes (more pods contending for an unsized shared pool, workers double-firing across pods). **Topology scales the platform only after the gate makes the components scalable.**

---

## 12. Open items to confirm before provisioning

1. Target cloud (or multi-cloud/on-prem) for the first production environment — picks the Terraform module set; no app impact.
2. Postgres `max_connections` and pooler sizing against the chosen managed offering's limits (§4 arithmetic).
3. RPO/RTO targets confirmed with the business (§8 are proposals).
4. Whether any launch partner is large enough to justify the dedicated-tier escape hatch (§5) on day one (default: no).
5. Region/data-residency requirements (India data-residency may constrain provider/region choice — confirm with compliance).
