"""End-to-end API workflows for load testing."""
from __future__ import annotations

import io
import time
import uuid
from typing import Any

import requests

from fixtures import DOC_TYPES, loan_body
from _common import MetricsCollector, lsp_token, timed_request

PDF_BYTES = (
    b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
    b"2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
    b"3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n"
    b"xref\n0 4\n0000000000 65535 f \n0000000009 00000 n \n0000000052 00000 n \n"
    b"0000000101 00000 n \ntrailer<</Size 4/Root 1 0 R>>\nstartxref\n164\n%%EOF\n"
)


def _base(cfg: dict) -> str:
    return cfg["BASE_URL"]


def workflow_origination(
    cfg: dict,
    metrics: MetricsCollector,
    fixture: dict,
    *,
    doc_count: int = 8,
    poll_approval: bool = True,
) -> dict[str, Any]:
    """LSP token → create application → upload docs → poll until approved."""
    base = _base(cfg)
    lt = lsp_token(cfg, fixture["clientId"], fixture["clientSecret"])
    lh = {"Authorization": f"Bearer {lt}"}
    sfx = f"{fixture['suffix']}-{uuid.uuid4().hex[:6]}"
    body = loan_body(fixture["lspId"], fixture["productId"], sfx)
    idem = str(uuid.uuid4())

    r = timed_request(
        metrics, method="POST", url=f"{base}/api/v1/lsp/loan-applications",
        workflow="origination.create", path_label="POST /api/v1/lsp/loan-applications",
        headers={**lh, "Content-Type": "application/json", "Idempotency-Key": idem},
        json=body, timeout=90,
    )
    if r.status_code not in (200, 201):
        return {"ok": False, "step": "create", "status": r.status_code}
    app_id = r.json()["id"]

    upload_headers = {k: v for k, v in lh.items() if k.lower() != "content-type"}
    for dt in DOC_TYPES[:doc_count]:
        timed_request(
            metrics, method="POST",
            url=f"{base}/api/v1/lsp/loan-applications/{app_id}/documents",
            workflow="origination.upload_doc",
            path_label="POST /api/v1/lsp/loan-applications/{id}/documents",
            headers=upload_headers,
            files={"file": (f"{dt.lower()}.pdf", io.BytesIO(PDF_BYTES), "application/pdf")},
            data={"documentType": dt}, timeout=60,
        )

    timed_request(
        metrics, method="GET", url=f"{base}/api/v1/lsp/loan-applications/{app_id}",
        workflow="origination.status_read",
        path_label="GET /api/v1/lsp/loan-applications/{id}",
        headers=lh, timeout=30,
    )

    if poll_approval:
        from _common import admin_token
        admin = admin_token(cfg)
        ah = {"Authorization": f"Bearer {admin}"}
        status = None
        for _ in range(30):
            pr = timed_request(
                metrics, method="GET",
                url=f"{base}/api/v1/internal/ops/loan-applications/{app_id}",
                workflow="origination.approval_poll",
                path_label="GET /api/v1/internal/ops/loan-applications/{id}",
                headers=ah, timeout=30,
            )
            status = pr.json().get("status") if pr.ok else None
            if status in ("APPROVED_PENDING_DISBURSAL", "DISBURSED", "REJECTED"):
                break
            time.sleep(0.5)

    return {"ok": True, "applicationId": app_id, "lspLoanId": body["lspLoanId"]}


def workflow_disbursement(cfg: dict, metrics: MetricsCollector, fixture: dict, app_id: str, admin_token: str) -> dict:
    base = _base(cfg)
    ah = {"Authorization": f"Bearer {admin_token}", "Content-Type": "application/json"}

    timed_request(
        metrics, method="POST",
        url=f"{base}/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests",
        workflow="disbursement.initiate",
        path_label="POST /api/v1/internal/ops/loan-applications/{id}/disbursement-requests",
        headers=ah, timeout=60,
    )

    for _ in range(20):
        dr = timed_request(
            metrics, method="GET",
            url=f"{base}/api/v1/internal/ops/loan-applications/{app_id}",
            workflow="disbursement.poll",
            path_label="GET /api/v1/internal/ops/loan-applications/{id}",
            headers=ah, timeout=30,
        )
        if dr.ok and dr.json().get("status") == "DISBURSED":
            return {"ok": True, "status": "DISBURSED"}
        time.sleep(1)

    # Mock outcome for local profile
    timed_request(
        metrics, method="POST",
        url=f"{base}/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests/mock-outcome",
        workflow="disbursement.mock_outcome",
        path_label="POST .../disbursement-requests/mock-outcome",
        headers=ah, json={"outcome": "SUCCESS"}, timeout=60,
    )
    return {"ok": True, "status": "mock_resolved"}


def workflow_repayment(cfg: dict, metrics: MetricsCollector, fixture: dict, loan_id: str, admin_token: str, amount: float = 5000) -> dict:
    base = _base(cfg)
    lt = lsp_token(cfg, fixture["clientId"], fixture["clientSecret"])
    lh = {"Authorization": f"Bearer {lt}", "Content-Type": "application/json"}

    timed_request(
        metrics, method="GET", url=f"{base}/api/v1/lsp/loans/{loan_id}/repayment-schedule",
        workflow="repayment.schedule_read",
        path_label="GET /api/v1/lsp/loans/{id}/repayment-schedule",
        headers=lh, timeout=30,
    )

    pr = timed_request(
        metrics, method="POST", url=f"{base}/api/v1/lsp/loans/{loan_id}/payments",
        workflow="repayment.post_payment",
        path_label="POST /api/v1/lsp/loans/{id}/payments",
        headers={**lh, "Idempotency-Key": str(uuid.uuid4())},
        json={"amount": amount, "paymentMode": "UPI", "referenceNumber": f"PERF-{uuid.uuid4().hex[:8]}"},
        timeout=60,
    )
    return {"ok": pr.status_code in (200, 201), "status": pr.status_code}


def workflow_admin_reads(cfg: dict, metrics: MetricsCollector, admin_token: str, lsp_id: str) -> None:
    base = _base(cfg)
    ah = {"Authorization": f"Bearer {admin_token}"}
    endpoints = [
        ("GET", "/api/v1/internal/home/overview", "admin.dashboard"),
        ("GET", "/api/v1/internal/ops/loan-applications?page=0&size=20", "admin.loan_list"),
        ("GET", f"/api/v1/internal/reports/portfolio-mis/preview?lspId={lsp_id}", "admin.report_preview"),
        ("GET", "/api/v1/internal/alerts?status=OPEN&offset=0&limit=20", "admin.alerts"),
        ("GET", "/api/v1/internal/admin/audit-events?page=0&size=20", "admin.audit"),
    ]
    for method, path, wf in endpoints:
        timed_request(
            metrics, method=method, url=f"{base}{path}", workflow=wf,
            path_label=f"{method} {path.split('?')[0]}", headers=ah, timeout=60,
        )


def workflow_report_async(cfg: dict, metrics: MetricsCollector, admin_token: str, lsp_id: str) -> dict:
    base = _base(cfg)
    ah = {"Authorization": f"Bearer {admin_token}", "Content-Type": "application/json"}
    cr = timed_request(
        metrics, method="POST", url=f"{base}/api/v1/internal/reports/portfolio-mis/requests",
        workflow="report.async_create",
        path_label="POST /api/v1/internal/reports/portfolio-mis/requests",
        headers=ah, json={"lspId": lsp_id}, timeout=30,
    )
    if cr.status_code not in (200, 201):
        return {"ok": False}
    req_id = cr.json().get("id")
    for _ in range(15):
        timed_request(
            metrics, method="GET", url=f"{base}/api/v1/internal/reports/requests",
            workflow="report.poll",
            path_label="GET /api/v1/internal/reports/requests",
            headers=ah, timeout=30,
        )
        if req_id:
            dl = timed_request(
                metrics, method="GET",
                url=f"{base}/api/v1/internal/reports/requests/{req_id}/download",
                workflow="report.download",
                path_label="GET /api/v1/internal/reports/requests/{id}/download",
                headers=ah, timeout=120,
            )
            if dl.status_code == 200:
                return {"ok": True, "requestId": req_id}
        time.sleep(2)
    return {"ok": False, "requestId": req_id}


def workflow_race_duplicate_disburse(cfg: dict, metrics: MetricsCollector, app_id: str, admin_token: str, threads: int = 5) -> list[int]:
    """Fire concurrent disbursement initiations — expect single success / idempotent handling."""
    from concurrent.futures import ThreadPoolExecutor, as_completed
    base = _base(cfg)
    statuses: list[int] = []

    def _fire() -> int:
        ah = {"Authorization": f"Bearer {admin_token}", "Content-Type": "application/json"}
        r = timed_request(
            metrics, method="POST",
            url=f"{base}/api/v1/internal/ops/loan-applications/{app_id}/disbursement-requests",
            workflow="race.disbursement",
            path_label="POST .../disbursement-requests",
            headers=ah, timeout=60,
        )
        return r.status_code

    with ThreadPoolExecutor(max_workers=threads) as pool:
        futs = [pool.submit(_fire) for _ in range(threads)]
        for f in as_completed(futs):
            statuses.append(f.result())
    return statuses


def workflow_idempotency_replay(cfg: dict, metrics: MetricsCollector, fixture: dict) -> dict:
    base = _base(cfg)
    lt = lsp_token(cfg, fixture["clientId"], fixture["clientSecret"])
    lh = {"Authorization": f"Bearer {lt}", "Content-Type": "application/json"}
    sfx = f"idem-{uuid.uuid4().hex[:8]}"
    body = loan_body(fixture["lspId"], fixture["productId"], sfx)
    idem = str(uuid.uuid4())
    r1 = timed_request(
        metrics, method="POST", url=f"{base}/api/v1/lsp/loan-applications",
        workflow="race.idempotency_create",
        path_label="POST /api/v1/lsp/loan-applications",
        headers={**lh, "Idempotency-Key": idem}, json=body, timeout=90,
    )
    r2 = timed_request(
        metrics, method="POST", url=f"{base}/api/v1/lsp/loan-applications",
        workflow="race.idempotency_replay",
        path_label="POST /api/v1/lsp/loan-applications",
        headers={**lh, "Idempotency-Key": idem}, json=body, timeout=90,
    )
    same_id = r1.ok and r2.ok and r1.json().get("id") == r2.json().get("id")
    return {"ok": same_id, "status1": r1.status_code, "status2": r2.status_code}
