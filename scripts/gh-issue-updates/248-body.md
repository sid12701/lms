## What to build

Provider-agnostic deployment substrate for the **decided operating point** (V2-D1): **~1M loans/month** (~33K/day sustained), **~150K loans/day peak** (V2-D2), **10 LSPs**, per `docs/architecture/deployment-strategy.md`. Same container image for API and worker tiers, selected by config flags; everything provider-specific confined to Terraform + Helm values.

**V2-D5:** Implement IaC **in-repo only** — `terraform validate` + `helm template` must pass; **do not `terraform apply` to a real cloud account AFK** without human credentials. **Likely Azure** for first prod; keep AWS/GCP modules for portability. **India region** default (V2-D6). **Prod Postgres:** managed PG behind transaction-mode pooler — **not Supabase** (V2-D4).

Source: `scalability-audit-360-2026-06-14.md` + `docs/architecture/deployment-strategy.md` + `scalability-execution-tracker.md`. Tracked under Phase 1A gate #247. Feeds topology ADR #226.

## Scope (provider-neutral)

- [ ] Containerize as one OCI image; API vs worker tier differ only by env flags (`app.*.worker.enabled`, scheduler/webhook/report/alert flags).
- [ ] Helm chart: API Deployment (HPA **3→10** at V2-D2 peak on CPU + p95/in-flight), worker Deployment (**2→6** on queue depth / oldest-pending-age #234), config/secret wiring via env.
- [ ] Terraform modules per managed service (Postgres, pooler, Redis, object storage, ingress/LB, DNS, secrets, registry) — **Azure reference module** + AWS/GCP stubs; provider differences isolated here only.
- [ ] **Transaction-mode connection pooler** in front of Postgres (mandatory — single tenant app role + RLS-on-GUC, no per-request `SET ROLE`).
- [ ] Postgres Multi-AZ (**India region**), automated backups/PITR; read-replica module behind documented trigger.
- [ ] Object storage with presigned-URL access (#228), versioning + lifecycle for **8-year archive** (V2-D6).
- [ ] Pre-deploy Flyway migration Job (not on pod boot); `CONCURRENTLY` index builds for the big-six.
- [ ] Private networking for DB/Redis/storage; TLS at edge; secrets from provider store (12-factor); image scanning in CI.
- [ ] Prometheus scrape + Grafana + Alertmanager; JSON logs (#218) to a collector.
- [ ] Graceful shutdown (drain requests + in-flight worker tasks) on SIGTERM.
- [ ] **DR:** RPO ≤ 5 min, RTO ≤ 30 min (V2-D7); restore drill before prod sign-off.

## Capacity sizing (V2-D2 peak)

At **150K loans/day peak** with 10 LSPs: ~8 API + ~4 worker pods at HPA max; ~**320 client connections → pooler → ~100–150 PG backends**. Document arithmetic in README.

## Blocked by (topology scales only after components do)

- #243, #203/#204, #201, #244, #217/#234

## Acceptance criteria

- [ ] `helm template` / `terraform validate` pass; switching providers changes only Terraform/Helm values, not app code.
- [ ] Staging (#198) mirrors prod topology; #200 **150K/day all-LSP-spike** passes #247 exit criterion.
- [ ] DR restore drill meets V2-D7 RPO/RTO.
- [ ] No provider SDK in application code.
