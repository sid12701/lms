"""Independent: webhooks+SSRF, PII masking, MIS reports, audit, alerts, borrower 360."""
from __future__ import annotations
import json, re, time, uuid
from client import req, admin_token, check, info, summary, FAIL

ctx = json.load(open("indep_ctx.json"))
t = admin_token()
lsp_id = ctx["lsp_id"]
borrower_id = ctx["borrower_id"]
app_id = ctx["closed_app_id"]
J = {"Content-Type": "application/json"}

AADHAAR_12 = re.compile(r"\b\d{12}\b")
MASKED = re.compile(r"X{4,}\d{4}")

# ===== EC-066 SSRF rejection at save =====
for label, url in [("metadata-ip", "http://169.254.169.254/latest/meta-data"),
                   ("localhost", "http://localhost:9999/hook"),
                   ("127.0.0.1", "http://127.0.0.1:8080/hook"),
                   ("10.x private", "http://10.1.2.3/hook")]:
    r = req("PUT", f"/api/v1/internal/admin/lsps/{lsp_id}/webhook-subscription", token=t, headers=J,
            json={"enabled": True, "endpointUrl": url, "signingSecret": "s3cr3tssssssssss",
                  "eventTypes": ["LOAN_STATUS_CHANGED"]})
    check(f"EC-066.{label}", r.status_code in (400, 422), f"SSRF {url} -> {r.status_code} (want 4xx reject)")

# UC-008 valid public https subscription accepted
r = req("PUT", f"/api/v1/internal/admin/lsps/{lsp_id}/webhook-subscription", token=t, headers=J,
        json={"enabled": True, "endpointUrl": "https://example.com/hook", "signingSecret": "s3cr3tssssssssss",
              "eventTypes": ["LOAN_STATUS_CHANGED"]})
check("UC-008", r.status_code == 200, f"valid https webhook subscription -> {r.status_code}")

# EC-061 'nonexistent.invalid' host: matrix claimed 422 reject; verify actual
r = req("PUT", f"/api/v1/internal/admin/lsps/{lsp_id}/webhook-subscription", token=t, headers=J,
        json={"enabled": True, "endpointUrl": "https://nonexistent.invalid/hook", "signingSecret": "s3cr3tssssssssss",
              "eventTypes": ["LOAN_STATUS_CHANGED"]})
info("EC-061", f"subscribe nonexistent.invalid host -> {r.status_code} (matrix claimed 422; resolvable-but-public should save)")

# ===== UC-050 per-application webhook events were actually queued =====
r = req("GET", f"/api/v1/internal/ops/loan-applications/{app_id}/webhook-events", token=t)
evs = r.json() if r.ok else []
info("UC-050", f"webhook-events for closed loan -> {r.status_code} count={len(evs) if isinstance(evs, list) else evs}")
if isinstance(evs, list) and evs:
    types = [e.get("eventType") for e in evs]
    info("UC-050.types", f"event types: {types[:10]}")
    # EC-105 ordering monotonic by createdAt
    times = [e.get("createdAt") for e in evs if e.get("createdAt")]
    check("EC-105", times == sorted(times) or times == sorted(times, reverse=True),
          f"webhook events ordered by createdAt ({len(times)} rows)")

# ===== EC-073 borrower aadhaar masked on read =====
r = req("GET", f"/api/v1/internal/admin/borrowers/{borrower_id}", token=t)
if r.ok:
    bj = r.json(); txt = json.dumps(bj)
    aad = bj.get("aadharNumber") or bj.get("aadhaarNumber") or ""
    check("EC-073", not AADHAAR_12.search(str(aad)), f"borrower aadhaar masked: {aad!r}")
    check("UC-034", bool(bj.get("id")), f"borrower 360 returns profile (keys={list(bj)[:8]})")
    # full-body scan for any 12-digit aadhaar leak
    leaks = [m for m in AADHAAR_12.findall(txt) if not bj.get("mobileNumber","").endswith(m)]
    check("EC-073.scan", len(leaks) == 0, f"no raw 12-digit aadhaar in borrower body (found {leaks[:3]})")
else:
    info("EC-073", f"borrower GET -> {r.status_code}")

# ===== EC-067 MIS preview masks aadhaar + bank account =====
qs = "disbursalDateFrom=2026-01-01&disbursalDateTo=2026-12-31"
r = req("GET", f"/api/v1/internal/reports/portfolio-mis/preview?{qs}", token=t)
check("UC-037.preview", r.status_code == 200, f"MIS preview -> {r.status_code}")
if r.ok:
    txt = r.text
    aad_leaks = AADHAAR_12.findall(txt)
    check("EC-067", len(aad_leaks) == 0, f"MIS preview: no raw 12-digit aadhaar (found {aad_leaks[:3]})")
    info("EC-067.sample", f"preview body sample: {txt[:300]}")

# UC-037 sync CSV
r = req("GET", f"/api/v1/internal/reports/portfolio-mis?{qs}", token=t)
ct = r.headers.get("Content-Type", "")
check("UC-037", r.status_code == 200 and "csv" in ct.lower(), f"MIS sync CSV -> {r.status_code} ct={ct}")
if r.ok and "csv" in ct.lower():
    check("EC-067.csv", len(AADHAAR_12.findall(r.text)) == 0, "MIS CSV: no raw 12-digit aadhaar")

# EC-070 empty future range
r = req("GET", f"/api/v1/internal/reports/portfolio-mis?disbursalDateFrom=2099-01-01&disbursalDateTo=2099-12-31", token=t)
check("EC-070", r.status_code == 200, f"MIS future-only range -> {r.status_code} (no 500)")

# UC-038 async MIS request
r = req("POST", "/api/v1/internal/reports/portfolio-mis/requests", token=t, headers=J,
        json={"disbursalDateFrom": "2026-01-01", "disbursalDateTo": "2026-12-31"})
info("UC-038.submit", f"async MIS request -> {r.status_code} {r.text[:140]}")
if r.ok:
    rid_ = r.json().get("id") or r.json().get("requestId")
    final_st = None
    for _ in range(30):
        rr = req("GET", "/api/v1/internal/reports/requests", token=t)
        if rr.ok:
            mine = [x for x in rr.json() if x.get("id") == rid_]
            if mine:
                final_st = mine[0].get("status")
                if final_st in ("COMPLETED", "FAILED"):
                    break
        time.sleep(1)
    check("UC-038", final_st == "COMPLETED", f"async MIS report status -> {final_st}")
    if final_st == "COMPLETED":
        dl = req("GET", f"/api/v1/internal/reports/requests/{rid_}/download", token=t)
        check("UC-038.dl", dl.status_code == 200, f"async MIS download -> {dl.status_code}")

# ===== UC-035 home dashboard =====
r = req("GET", "/api/v1/internal/home/overview", token=t)
check("UC-035", r.status_code == 200, f"home overview -> {r.status_code}")
if r.ok:
    h = r.json()
    info("UC-035.kpi", f"keys={list(h)[:12]}")

# ===== UC-044 audit explorer + validation =====
r = req("GET", f"/api/v1/internal/admin/audit-events?loanApplicationId={app_id}&limit=50", token=t)
check("UC-044", r.status_code == 200, f"audit explorer -> {r.status_code}")
if r.ok:
    body = r.json()
    rows = body if isinstance(body, list) else body.get("events", body.get("items", []))
    info("UC-044.rows", f"audit rows for closed loan: {len(rows)}")
    # EC-094 INTAKE aadhaar masking
    blob = json.dumps(body)
    intake_leaks = AADHAAR_12.findall(blob)
    check("EC-094", len(intake_leaks) == 0, f"audit events: no raw 12-digit aadhaar (found {intake_leaks[:3]})")

# EC-091 since>until -> 400
r = req("GET", "/api/v1/internal/admin/audit-events?since=2026-12-31T00:00:00Z&until=2026-01-01T00:00:00Z", token=t)
check("EC-091", r.status_code == 400, f"audit since>until -> {r.status_code} (want 400)")
# EC-093 bad stream -> 400
r = req("GET", "/api/v1/internal/admin/audit-events?streams=BOGUS", token=t)
check("EC-093", r.status_code == 400, f"audit bad stream -> {r.status_code} (want 400)")
# EC-092 limit clamp (should not 500)
r = req("GET", "/api/v1/internal/admin/audit-events?limit=99999", token=t)
check("EC-092", r.status_code == 200, f"audit limit=99999 clamped -> {r.status_code}")

# ===== Alerts UC-039 / EC-077/078/079 =====
r = req("GET", "/api/v1/internal/alerts?status=NEW", token=t)
check("UC-041", r.status_code == 200, f"alerts list -> {r.status_code}")
alerts = r.json() if r.ok else []
alert_rows = alerts if isinstance(alerts, list) else alerts.get("items", alerts.get("alerts", []))
info("UC-041.count", f"NEW alerts: {len(alert_rows) if isinstance(alert_rows, list) else alert_rows}")

# EC-077 acknowledge random alert -> 404
r = req("POST", f"/api/v1/internal/alerts/{uuid.uuid4()}/acknowledge", token=t, headers=J, json={"note": "x"})
check("EC-077", r.status_code == 404, f"ack random alert -> {r.status_code} (want 404)")

if isinstance(alert_rows, list) and alert_rows:
    aid = alert_rows[0].get("id")
    # EC-078 note >500 chars -> 400
    r = req("POST", f"/api/v1/internal/alerts/{aid}/acknowledge", token=t, headers=J, json={"note": "z" * 501})
    check("EC-078", r.status_code == 400, f"ack note>500 -> {r.status_code} (want 400)")
    # UC-039 valid ack
    r = req("POST", f"/api/v1/internal/alerts/{aid}/acknowledge", token=t, headers=J, json={"note": "ack by indep test"})
    check("UC-039", r.status_code in (200, 204), f"acknowledge alert -> {r.status_code}")
    # EC-079 double ack
    r2 = req("POST", f"/api/v1/internal/alerts/{aid}/acknowledge", token=t, headers=J, json={"note": "again"})
    info("EC-079", f"double-ack -> {r2.status_code} (matrix: 409)")
else:
    info("UC-039", "no NEW alerts to acknowledge")

summary()
raise SystemExit(1 if FAIL else 0)
